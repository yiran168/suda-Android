package com.qrint.studio.data

import android.content.Context
import android.os.Build
import com.qrint.studio.ProductIdentity
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CrashReport(
    val timestamp: Long,
    val stage: String,
    val summary: String,
    val details: String,
) {
    fun asText(): String = buildString {
        appendLine("${ProductIdentity.NAME} crash report")
        appendLine("time=$timestamp")
        appendLine("stage=$stage")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        appendLine("summary=$summary")
        append(details)
    }

    fun asMarkdown(): String = buildString {
        val occurredAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.getDefault()).format(Date(timestamp))
        appendLine("# ${ProductIdentity.NAME}异常报告")
        appendLine()
        appendLine("- 发生时间：$occurredAt（$timestamp）")
        appendLine("- 异常阶段：${stage.singleLine()}")
        appendLine("- 设备：${Build.MANUFACTURER.orEmpty()} ${Build.MODEL.orEmpty()}")
        appendLine("- Android：${Build.VERSION.RELEASE.orEmpty()}（SDK ${Build.VERSION.SDK_INT}）")
        appendLine("- 摘要：${summary.singleLine()}")
        appendLine()
        appendLine("## 完整诊断")
        appendLine()
        appendLine("````text")
        appendLine(details.trimEnd())
        appendLine("````")
        appendLine()
        appendLine("> 本报告由${ProductIdentity.NAME}在本机生成，不包含标签正文或用户图片。")
    }

    fun markdownFileName(): String {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(timestamp))
        return "${ProductIdentity.NAME}-异常报告-$date.md"
    }

    private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ').trim()
}

/**
 * Minimal synchronous crash journal. It has no network dependency and deliberately stores only the
 * latest stack trace plus a coarse app stage; label contents and user images are never collected.
 */
class CrashReportStore(context: Context) {
    private val preferences = context.getSharedPreferences("crash_journal", Context.MODE_PRIVATE)

    fun setStage(stage: String) {
        preferences.edit().putString(KEY_STAGE, stage.take(160)).apply()
    }

    fun latest(): CrashReport? {
        val details = preferences.getString(KEY_DETAILS, null) ?: return null
        return CrashReport(
            timestamp = preferences.getLong(KEY_TIME, 0L),
            stage = preferences.getString(KEY_CRASH_STAGE, "unknown").orEmpty(),
            summary = preferences.getString(KEY_SUMMARY, "unknown error").orEmpty(),
            details = details,
        )
    }

    fun clear() {
        preferences.edit()
            .remove(KEY_TIME)
            .remove(KEY_CRASH_STAGE)
            .remove(KEY_SUMMARY)
            .remove(KEY_DETAILS)
            .apply()
    }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is RecordingExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(RecordingExceptionHandler(this, previous))
    }

    private fun record(thread: Thread, error: Throwable) {
        runCatching {
            val writer = StringWriter()
            error.printStackTrace(PrintWriter(writer))
            preferences.edit()
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_CRASH_STAGE, preferences.getString(KEY_STAGE, "unknown"))
                .putString(KEY_SUMMARY, "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(500))
                .putString(KEY_DETAILS, "thread=${thread.name}\n${writer}".take(48_000))
                .commit()
        }
    }

    private class RecordingExceptionHandler(
        private val store: CrashReportStore,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            store.record(thread, error)
            previous?.uncaughtException(thread, error)
                ?: android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private companion object {
        const val KEY_STAGE = "stage"
        const val KEY_TIME = "crash_time"
        const val KEY_CRASH_STAGE = "crash_stage"
        const val KEY_SUMMARY = "crash_summary"
        const val KEY_DETAILS = "crash_details"
    }
}
