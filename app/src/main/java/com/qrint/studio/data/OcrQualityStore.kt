package com.qrint.studio.data

import android.content.Context
import android.util.AtomicFile
import com.qrint.studio.render.OfflineTextScan
import com.qrint.studio.render.canonicalRecognitionText
import com.qrint.studio.render.measureTextAccuracy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

data class OcrQualityStats(
    val validatedSamples: Long = 0,
    val characters: Long = 0,
    val errors: Long = 0,
) {
    val accuracy: Float
        get() = if (characters <= 0L) 0f else (1f - errors.toFloat() / characters).coerceIn(0f, 1f)
}

internal data class OcrCorrectionRule(
    val raw: String,
    val corrected: String,
    val confirmations: Int,
    val updatedAt: Long,
)

internal fun updateOcrQualityStats(
    current: OcrQualityStats,
    reference: String,
    prediction: String,
): OcrQualityStats {
    val measurement = measureTextAccuracy(reference, prediction)
    return current.copy(
        validatedSamples = current.validatedSamples + 1,
        characters = current.characters + measurement.characters,
        errors = current.errors + measurement.errors,
    )
}

/**
 * Stores only user-validated ground truth. Exact line corrections require two confirmations before
 * automatic use, avoiding an unreviewed OCR guess becoming a global substitution rule.
 */
class OcrQualityStore(private val context: Context) {
    companion object {
        private const val MAX_RULES = 500
        private const val MIN_AUTO_CONFIRMATIONS = 2
    }

    private val file = File(context.filesDir, "ocr_quality.json")
    private val mutex = Mutex()
    private val loaded = load()
    private val _stats = MutableStateFlow(loaded.first)
    val stats: StateFlow<OcrQualityStats> = _stats.asStateFlow()
    private var rules: List<OcrCorrectionRule> = loaded.second

    fun applyValidatedCorrections(scan: OfflineTextScan): OfflineTextScan {
        val confirmed = rules.asSequence()
            .filter { it.confirmations >= MIN_AUTO_CONFIRMATIONS }
            .associateBy(OcrCorrectionRule::raw)
        if (confirmed.isEmpty()) return scan
        return scan.copy(
            lines = scan.lines.map { line ->
                val key = canonicalRecognitionText(line.text)
                confirmed[key]?.let { rule -> line.copy(text = rule.corrected) } ?: line
            },
        )
    }

    suspend fun recordValidated(original: OfflineTextScan, corrected: OfflineTextScan) = withContext(Dispatchers.IO) {
        mutex.withLock {
            _stats.value = updateOcrQualityStats(_stats.value, corrected.plainText, original.plainText)
            val mutable = rules.associateByTo(linkedMapOf(), OcrCorrectionRule::raw)
            original.lines.zip(corrected.lines).forEach { (rawLine, correctedLine) ->
                val raw = canonicalRecognitionText(rawLine.text).take(256)
                val truth = canonicalRecognitionText(correctedLine.text).take(256)
                if (raw.isBlank() || truth.isBlank() || raw == truth) return@forEach
                val previous = mutable[raw]
                mutable[raw] = if (previous?.corrected == truth) {
                    previous.copy(confirmations = (previous.confirmations + 1).coerceAtMost(100), updatedAt = System.currentTimeMillis())
                } else {
                    OcrCorrectionRule(raw, truth, 1, System.currentTimeMillis())
                }
            }
            rules = mutable.values.sortedByDescending(OcrCorrectionRule::updatedAt).take(MAX_RULES)
            write(_stats.value, rules)
        }
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _stats.value = OcrQualityStats()
            rules = emptyList()
            write(_stats.value, rules)
        }
    }

    private fun load(): Pair<OcrQualityStats, List<OcrCorrectionRule>> = runCatching {
        if (!file.isFile) return@runCatching OcrQualityStats() to emptyList()
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val statsJson = root.optJSONObject("stats") ?: JSONObject()
        val stats = OcrQualityStats(
            validatedSamples = statsJson.optLong("validatedSamples", 0L).coerceAtLeast(0L),
            characters = statsJson.optLong("characters", 0L).coerceAtLeast(0L),
            errors = statsJson.optLong("errors", 0L).coerceAtLeast(0L),
        )
        val array = root.optJSONArray("rules") ?: JSONArray()
        val savedRules = buildList {
            for (index in 0 until minOf(array.length(), MAX_RULES)) {
                val item = array.optJSONObject(index) ?: continue
                val raw = canonicalRecognitionText(item.optString("raw")).take(256)
                val corrected = canonicalRecognitionText(item.optString("corrected")).take(256)
                if (raw.isBlank() || corrected.isBlank() || raw == corrected) continue
                add(
                    OcrCorrectionRule(
                        raw,
                        corrected,
                        item.optInt("confirmations", 1).coerceIn(1, 100),
                        item.optLong("updatedAt", 0L),
                    ),
                )
            }
        }.distinctBy(OcrCorrectionRule::raw)
        stats to savedRules
    }.getOrDefault(OcrQualityStats() to emptyList())

    private fun write(stats: OcrQualityStats, rules: List<OcrCorrectionRule>) {
        val text = JSONObject().apply {
            put("stats", JSONObject().apply {
                put("validatedSamples", stats.validatedSamples)
                put("characters", stats.characters)
                put("errors", stats.errors)
            })
            put("rules", JSONArray().apply {
                rules.forEach { rule ->
                    put(JSONObject().apply {
                        put("raw", rule.raw)
                        put("corrected", rule.corrected)
                        put("confirmations", rule.confirmations)
                        put("updatedAt", rule.updatedAt)
                    })
                }
            })
        }.toString()
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            OutputStreamWriter(stream, Charsets.UTF_8).apply { write(text); flush() }
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }
}
