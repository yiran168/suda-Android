package com.qrint.studio.data

import android.content.Context
import android.net.Uri
import com.qrint.studio.ProductIdentity

/** Permission-free Storage Access Framework save and FileProvider share helpers. */
object CrashReportExporter {
    fun writeMarkdown(context: Context, destination: Uri, report: CrashReport): Result<Unit> =
        MarkdownFileExporter.write(context, destination, report.asMarkdown())

    fun shareMarkdown(context: Context, report: CrashReport): Result<Unit> = MarkdownFileExporter.share(
        context = context,
        directoryName = "crash_reports",
        fileName = report.markdownFileName(),
        subject = "${ProductIdentity.NAME}异常报告",
        content = report.asMarkdown(),
    )
}
