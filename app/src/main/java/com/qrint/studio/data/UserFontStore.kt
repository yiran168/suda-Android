package com.qrint.studio.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.util.AtomicFile
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
import java.security.MessageDigest

data class UserFont(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val importedAt: Long,
) {
    val key: String get() = "${UserFontStore.KEY_PREFIX}$id"
}

/**
 * Owns user-imported TTF/OTF files. Fonts are copied into private app storage so preview and
 * printing never depend on a temporary document-provider permission or a removable file.
 */
class UserFontStore(private val context: Context) {
    companion object {
        const val KEY_PREFIX = "userfont:"
        const val MAX_FONT_BYTES = 20L * 1024L * 1024L
        const val MAX_USER_FONTS = 64
        private val SAFE_ID = Regex("^[0-9a-f]{24}$")

        fun displayNameFrom(fileName: String): String = fileName
            .substringAfterLast('/')
            .substringBeforeLast('.', fileName.substringAfterLast('/'))
            .replace(Regex("[\\p{Cntrl}\\r\\n\\t]+"), " ")
            .trim()
            .take(80)
            .ifBlank { "本地字体" }

        /** Path resolution is deliberately derived from a validated digest, preventing traversal. */
        fun resolveFile(context: Context, key: String): File? {
            val id = key.removePrefix(KEY_PREFIX)
            if (!key.startsWith(KEY_PREFIX) || !SAFE_ID.matches(id)) return null
            val directory = File(context.filesDir, "user_fonts")
            return sequenceOf("ttf", "otf")
                .map { extension -> File(directory, "$id.$extension") }
                .firstOrNull(File::isFile)
        }

        internal fun fontExtension(header: ByteArray): String? {
            if (header.size < 4) return null
            val signature = String(header, 0, 4, Charsets.ISO_8859_1)
            return when {
                header[0] == 0.toByte() && header[1] == 1.toByte() &&
                    header[2] == 0.toByte() && header[3] == 0.toByte() -> "ttf"
                signature == "true" || signature == "typ1" -> "ttf"
                signature == "OTTO" -> "otf"
                else -> null
            }
        }
    }

    private val directory = File(context.filesDir, "user_fonts").apply { mkdirs() }
    private val manifest = File(context.filesDir, "user_fonts.json")
    private val mutex = Mutex()
    private val _fonts = MutableStateFlow(loadManifest())
    val fonts: StateFlow<List<UserFont>> = _fonts.asStateFlow()

    suspend fun import(uri: Uri, originalName: String): Result<UserFont> = withContext(Dispatchers.IO) {
        runCatching {
            mutex.withLock {
                require(_fonts.value.size < MAX_USER_FONTS) { "最多可导入 $MAX_USER_FONTS 款本地字体" }
                val temporary = File.createTempFile("font_import_", ".part", directory)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    val header = ByteArray(4)
                    var headerCount = 0
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(temporary).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                require(total <= MAX_FONT_BYTES) { "字体文件不能超过 20 MB" }
                                if (headerCount < header.size) {
                                    val count = minOf(read, header.size - headerCount)
                                    buffer.copyInto(header, headerCount, 0, count)
                                    headerCount += count
                                }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                        }
                    } ?: error("无法读取所选字体")
                    require(total >= 12L && headerCount == header.size) { "字体文件为空或不完整" }
                    val extension = fontExtension(header)
                        ?: error("仅支持有效的 TrueType/OpenType（TTF/OTF）字体")

                    // Android's own font parser is the final validation gate before persistence.
                    runCatching { Typeface.createFromFile(temporary) }
                        .getOrElse { error("Android 无法解析该字体：${it.message.orEmpty()}") }

                    val id = digest.digest().joinToString("") { byte -> "%02x".format(byte) }.take(24)
                    _fonts.value.firstOrNull { it.id == id }?.let { existing ->
                        temporary.delete()
                        return@withLock existing
                    }
                    val target = File(directory, "$id.$extension")
                    if (!temporary.renameTo(target)) {
                        temporary.copyTo(target, overwrite = true)
                        temporary.delete()
                    }
                    val imported = UserFont(
                        id = id,
                        displayName = displayNameFrom(originalName),
                        fileName = target.name,
                        sizeBytes = total,
                        importedAt = System.currentTimeMillis(),
                    )
                    _fonts.value = (_fonts.value + imported).sortedBy { it.displayName.lowercase() }
                    writeManifest(_fonts.value)
                    imported
                } finally {
                    temporary.takeIf(File::exists)?.delete()
                }
            }
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = _fonts.value.firstOrNull { it.id == id } ?: return@withLock false
            val deleted = File(directory, current.fileName).let { !it.exists() || it.delete() }
            if (deleted) {
                _fonts.value = _fonts.value.filterNot { it.id == id }
                writeManifest(_fonts.value)
            }
            deleted
        }
    }

    private fun loadManifest(): List<UserFont> = runCatching {
        if (!manifest.isFile) return@runCatching emptyList()
        val array = JSONArray(manifest.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val fileName = item.optString("fileName")
                if (!SAFE_ID.matches(id) || fileName != "$id.ttf" && fileName != "$id.otf") continue
                if (!File(directory, fileName).isFile) continue
                add(
                    UserFont(
                        id = id,
                        displayName = item.optString("displayName", "本地字体"),
                        fileName = fileName,
                        sizeBytes = item.optLong("sizeBytes", 0L),
                        importedAt = item.optLong("importedAt", 0L),
                    ),
                )
            }
        }.distinctBy(UserFont::id).take(MAX_USER_FONTS)
    }.getOrDefault(emptyList())

    private fun writeManifest(items: List<UserFont>) {
        val text = JSONArray().apply {
            items.forEach { font ->
                put(JSONObject().apply {
                    put("id", font.id)
                    put("displayName", font.displayName)
                    put("fileName", font.fileName)
                    put("sizeBytes", font.sizeBytes)
                    put("importedAt", font.importedAt)
                })
            }
        }.toString()
        val atomic = AtomicFile(manifest)
        var stream: FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            OutputStreamWriter(stream, Charsets.UTF_8).apply {
                write(text)
                flush()
            }
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }
}
