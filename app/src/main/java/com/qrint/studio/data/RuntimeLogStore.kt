package com.qrint.studio.data

import android.content.Context
import com.qrint.studio.ProductIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RuntimeLogLevel { INFO, WARNING, ERROR }

/** User-selectable log channels. Content logs contain redacted metadata only. */
enum class RuntimeLogCategory(
    val title: String,
    val description: String,
    val fileKey: String,
) {
    DIAGNOSTICS("运行与打印", "连接、纸张参数、打印阶段、完成份数和错误", "diagnostics"),
    CONTENT("标签内容", "标签文字、图片或变量数据的编辑摘要（不保存原文或图片）", "content"),
}

data class RuntimeLogEntry(
    val timestamp: Long,
    val level: RuntimeLogLevel,
    val event: String,
    val detail: String,
    val category: RuntimeLogCategory = RuntimeLogCategory.DIAGNOSTICS,
)

/** Small persistent ring log with separate diagnostic and redacted content channels. */
class RuntimeLogStore(context: Context) {
    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_FILE_BYTES = 512 * 1024L
        private const val MAX_FIELD_LENGTH = 600
    }

    private val directory = File(context.filesDir, "runtime_logs")
    private val file = File(directory, "runtime.log")
    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<RuntimeLogEntry>> = _entries.asStateFlow()

    fun info(
        event: String,
        detail: String = "",
        category: RuntimeLogCategory = RuntimeLogCategory.DIAGNOSTICS,
    ) = record(RuntimeLogLevel.INFO, event, detail, category)

    fun warning(
        event: String,
        detail: String = "",
        category: RuntimeLogCategory = RuntimeLogCategory.DIAGNOSTICS,
    ) = record(RuntimeLogLevel.WARNING, event, detail, category)

    fun error(
        event: String,
        detail: String = "",
        category: RuntimeLogCategory = RuntimeLogCategory.DIAGNOSTICS,
    ) = record(RuntimeLogLevel.ERROR, event, detail, category)

    fun record(
        level: RuntimeLogLevel,
        event: String,
        detail: String = "",
        category: RuntimeLogCategory = RuntimeLogCategory.DIAGNOSTICS,
    ) {
        val entry = RuntimeLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            event = sanitize(event),
            detail = sanitize(detail),
            category = category,
        )
        synchronized(lock) {
            val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
            _entries.value = next
            runCatching {
                check(directory.exists() || directory.mkdirs()) { "无法创建运行日志目录" }
                if (file.length() > MAX_FILE_BYTES) rewrite(next) else file.appendText(entry.toStorageLine() + "\n", Charsets.UTF_8)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyList()
            runCatching { if (file.exists()) file.delete() }
        }
    }

    fun filteredEntries(category: RuntimeLogCategory? = null): List<RuntimeLogEntry> =
        category?.let { selected -> _entries.value.filter { it.category == selected } } ?: _entries.value

    fun asMarkdown(category: RuntimeLogCategory? = null): String = buildString {
        val selectedEntries = filteredEntries(category)
        val title = category?.title?.let { "${ProductIdentity.NAME}${it}日志" } ?: "${ProductIdentity.NAME}运行日志"
        appendLine("# $title")
        appendLine()
        appendLine(category?.description ?: "按类别记录连接、纸张参数、打印阶段、完成份数、错误，以及标签内容编辑摘要。")
        appendLine("内容类别只保存元素类型、数量、尺寸等摘要，不保存标签原文、图片内容或变量单元格值。")
        appendLine()
        if (selectedEntries.isEmpty()) appendLine("暂无${category?.title ?: "运行"}日志。")
        selectedEntries.asReversed().forEach { entry ->
            append("- ").append(formatTime(entry.timestamp))
                .append(" · ").append(entry.category.title)
                .append(" · ").append(entry.level.name)
                .append(" · ").append(entry.event)
            if (entry.detail.isNotBlank()) append(" — ").append(entry.detail)
            appendLine()
        }
    }

    fun markdownFileName(category: RuntimeLogCategory? = null): String {
        val suffix = category?.let { "-${it.fileKey}" }.orEmpty()
        return "${ProductIdentity.NAME}运行日志$suffix-${fileTime(System.currentTimeMillis())}.md"
    }

    private fun load(): List<RuntimeLogEntry> = runCatching {
        if (!file.isFile) return@runCatching emptyList()
        file.readLines(Charsets.UTF_8).mapNotNull(::parseStorageLine).takeLast(MAX_ENTRIES)
    }.getOrDefault(emptyList())

    private fun rewrite(entries: List<RuntimeLogEntry>) {
        check(directory.exists() || directory.mkdirs()) { "无法创建运行日志目录" }
        file.bufferedWriter(Charsets.UTF_8).use { writer ->
            entries.forEach { writer.appendLine(it.toStorageLine()) }
        }
    }

    private fun RuntimeLogEntry.toStorageLine(): String =
        listOf(timestamp.toString(), level.name, event, detail, category.name).joinToString("\t")

    private fun parseStorageLine(line: String): RuntimeLogEntry? {
        // Version 1 lines have four fields; the optional fifth field is the category.
        val fields = line.split('\t', limit = 5)
        if (fields.size < 3) return null
        return RuntimeLogEntry(
            timestamp = fields[0].toLongOrNull() ?: return null,
            level = runCatching { RuntimeLogLevel.valueOf(fields[1]) }.getOrDefault(RuntimeLogLevel.INFO),
            event = fields[2],
            detail = fields.getOrElse(3) { "" },
            category = runCatching { RuntimeLogCategory.valueOf(fields.getOrElse(4) { "" }) }
                .getOrDefault(RuntimeLogCategory.DIAGNOSTICS),
        )
    }

    private fun sanitize(value: String): String = value
        .replace('\t', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .take(MAX_FIELD_LENGTH)

    private fun formatTime(value: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(value))

    private fun fileTime(value: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(value))
}
