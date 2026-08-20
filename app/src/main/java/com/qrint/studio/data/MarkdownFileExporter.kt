package com.qrint.studio.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.qrint.studio.BuildConfig
import java.io.File

/** Shared permission-free Storage Access Framework save and FileProvider share implementation. */
object MarkdownFileExporter {
    fun write(context: Context, destination: Uri, content: String): Result<Unit> = runCatching {
        context.contentResolver.openOutputStream(destination, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
            it.write(content)
        } ?: error("无法打开所选保存位置")
    }

    fun share(
        context: Context,
        directoryName: String,
        fileName: String,
        subject: String,
        content: String,
    ): Result<Unit> = runCatching {
        val directory = File(context.filesDir, directoryName).apply {
            check(exists() || mkdirs()) { "无法创建报告目录" }
        }
        val file = File(directory, fileName).apply { writeText(content, Charsets.UTF_8) }
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(subject, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享$subject").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
