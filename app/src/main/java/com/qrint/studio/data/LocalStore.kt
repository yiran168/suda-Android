package com.qrint.studio.data

import android.content.Context
import android.util.AtomicFile
import com.qrint.studio.model.AppPreferences
import com.qrint.studio.model.AppThemeStyle
import com.qrint.studio.model.DEFAULT_TAIL_FEED_MM
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PrintSoundPreset
import com.qrint.studio.model.PrintHistoryItem
import com.qrint.studio.model.labelDocumentFromJson
import com.qrint.studio.model.paperSettingsFromJson
import com.qrint.studio.model.printHistoryFromJson
import com.qrint.studio.model.toJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.UUID

class LocalStore(private val context: Context) {
    companion object {
        private const val MAX_HISTORY = 100
        private const val PREFS = "qrint_settings"
        private const val PAPER = "paper"
        private const val APP_THEME = "app_theme"
        private const val PRINT_SOUND = "print_sound"
        private const val PAPER_DEFAULT_FEED_5MM_MIGRATED = "paper_default_feed_5mm_migrated"
        private const val LEGACY_DEFAULT_TAIL_FEED_MM = 8f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val userFonts = UserFontStore(context)
    val templateUsage = TemplateUsageStore(context)
    val products = ProductLibraryStore(context)
    val ocrQuality = OcrQualityStore(context)
    private val mutex = Mutex()
    private val templatesFile = File(context.filesDir, "user_templates.json")
    private val historyFile = File(context.filesDir, "print_history.json")
    private val _templates = MutableStateFlow<List<LabelDocument>>(emptyList())
    val templates: StateFlow<List<LabelDocument>> = _templates.asStateFlow()
    private val _history = MutableStateFlow<List<PrintHistoryItem>>(emptyList())
    val history: StateFlow<List<PrintHistoryItem>> = _history.asStateFlow()
    private val _paper = MutableStateFlow(loadPaper())
    val paper: StateFlow<PaperSettings> = _paper.asStateFlow()
    private val _appPreferences = MutableStateFlow(loadAppPreferences())
    val appPreferences: StateFlow<AppPreferences> = _appPreferences.asStateFlow()

    init { scope.launch { loadFiles() } }

    fun saveTemplate(document: LabelDocument, title: String = document.title) {
        scope.launch {
            mutex.withLock {
                val saved = document.copy(
                    id = if (document.builtIn) UUID.randomUUID().toString() else document.id,
                    title = title.ifBlank { "未命名模板" }, builtIn = false, updatedAt = System.currentTimeMillis(),
                )
                val list = _templates.value.toMutableList()
                val index = list.indexOfFirst { it.id == saved.id }
                if (index >= 0) list[index] = saved else list.add(0, saved)
                _templates.value = list
                writeDocuments(templatesFile, list)
            }
        }
    }

    fun deleteTemplate(id: String) {
        scope.launch { mutex.withLock {
            _templates.value = _templates.value.filterNot { it.id == id }
            writeDocuments(templatesFile, _templates.value)
        } }
    }

    fun addHistory(item: PrintHistoryItem) {
        scope.launch { mutex.withLock {
            _history.value = (listOf(item) + _history.value).take(MAX_HISTORY)
            writeHistory(_history.value)
        } }
    }

    fun deleteHistory(id: String) {
        scope.launch { mutex.withLock {
            _history.value = _history.value.filterNot { it.id == id }
            writeHistory(_history.value)
        } }
    }

    fun clearHistory() {
        scope.launch { mutex.withLock { _history.value = emptyList(); writeHistory(emptyList()) } }
    }

    fun savePaper(settings: PaperSettings) {
        _paper.value = settings
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PAPER, settings.toJson().toString()).apply()
    }

    fun setTheme(theme: AppThemeStyle) = updateAppPreferences(_appPreferences.value.copy(theme = theme))

    fun setPrintSound(sound: PrintSoundPreset) =
        updateAppPreferences(_appPreferences.value.copy(printSound = sound))

    private fun updateAppPreferences(value: AppPreferences) {
        _appPreferences.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(APP_THEME, value.theme.name)
            .putString(PRINT_SOUND, value.printSound.name)
            .apply()
    }

    fun exportDocument(document: LabelDocument): String = JSONObject().apply {
        put("format", "qrint-label")
        put("version", 1)
        put("document", document.toJson())
    }.toString(2)

    fun importDocument(text: String): Result<LabelDocument> = runCatching {
        val root = JSONObject(text)
        val json = if (root.optString("format") == "qrint-label") root.getJSONObject("document") else root
        labelDocumentFromJson(json).copy(id = UUID.randomUUID().toString(), builtIn = false)
    }

    private suspend fun loadFiles() = mutex.withLock {
        _templates.value = readArray(templatesFile).mapNotNull { runCatching { labelDocumentFromJson(it) }.getOrNull() }
        _history.value = readArray(historyFile).mapNotNull { runCatching { printHistoryFromJson(it) }.getOrNull() }
    }

    private fun loadPaper(): PaperSettings = runCatching {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = preferences.getString(PAPER, null)
        val loaded = if (text.isNullOrBlank()) PaperSettings() else paperSettingsFromJson(JSONObject(text))
        if (preferences.getBoolean(PAPER_DEFAULT_FEED_5MM_MIGRATED, false)) return@runCatching loaded

        // Version 2.5 and earlier persisted the old 8 mm default. Migrate that exact value once;
        // every user-selected non-default value remains untouched.
        val migrated = if (loaded.tailFeedMm == LEGACY_DEFAULT_TAIL_FEED_MM) {
            loaded.copy(tailFeedMm = DEFAULT_TAIL_FEED_MM)
        } else {
            loaded
        }
        preferences.edit()
            .putBoolean(PAPER_DEFAULT_FEED_5MM_MIGRATED, true)
            .putString(PAPER, migrated.toJson().toString())
            .apply()
        migrated
    }.getOrDefault(PaperSettings())

    private fun loadAppPreferences(): AppPreferences {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = runCatching {
            AppThemeStyle.valueOf(preferences.getString(APP_THEME, AppThemeStyle.AURORA.name).orEmpty())
        }.getOrDefault(AppThemeStyle.AURORA)
        val sound = runCatching {
            PrintSoundPreset.valueOf(preferences.getString(PRINT_SOUND, PrintSoundPreset.PAPER_TICK.name).orEmpty())
        }.getOrDefault(PrintSoundPreset.PAPER_TICK)
        return AppPreferences(theme = theme, printSound = sound)
    }

    private fun readArray(file: File): List<JSONObject> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let(::add) }
    }.getOrDefault(emptyList())

    private fun writeDocuments(file: File, documents: List<LabelDocument>) =
        atomicWrite(file, JSONArray().apply { documents.forEach { put(it.toJson()) } }.toString())

    private fun writeHistory(items: List<PrintHistoryItem>) =
        atomicWrite(historyFile, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString())

    private fun atomicWrite(file: File, text: String) {
        val atomic = AtomicFile(file)
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
