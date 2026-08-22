package com.qrint.studio.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream

/**
 * File providers (especially WPS) occasionally expose every Office file as an Excel MIME type.
 * Detecting the package from its bytes before consulting MIME prevents Word/PPT files from being
 * sent to the legacy spreadsheet parser.
 */
internal enum class LocalDocumentKind {
    PDF,
    DOCX,
    PPTX,
    SPREADSHEET,
    LEGACY_WORD,
    LEGACY_POWERPOINT,
    TEXT,
    UNKNOWN,
}

internal object LocalDocumentFormatDetector {
    private val oleSignature = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    fun inspect(context: Context, uri: Uri, sourceName: String, mimeType: String?): LocalDocumentKind {
        val header = context.contentResolver.openInputStream(uri)?.use { input ->
            ByteArray(8).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
            }
        } ?: ByteArray(0)
        val signatureKind = when {
            header.startsWithAscii("%PDF") -> LocalDocumentKind.PDF
            header.startsWithBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
                header.startsWithBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06)) ||
                header.startsWithBytes(byteArrayOf(0x50, 0x4B, 0x07, 0x08)) -> inspectZip(context, uri)
            header.startsWithBytes(oleSignature) -> legacyKind(sourceName, mimeType)
            else -> LocalDocumentKind.UNKNOWN
        }
        return if (signatureKind != LocalDocumentKind.UNKNOWN) signatureKind
        else detectFromMetadata(sourceName, mimeType)
    }

    internal fun detectFromMetadata(sourceName: String, mimeType: String?): LocalDocumentKind {
        val name = sourceName.trim().lowercase()
        val mime = mimeType.orEmpty().trim().lowercase()
        // A concrete extension is more trustworthy than a provider's generic/mistaken MIME type.
        return when {
            name.endsWith(".pdf") -> LocalDocumentKind.PDF
            name.endsWith(".docx") || name.endsWith(".wpsx") -> LocalDocumentKind.DOCX
            name.endsWith(".pptx") || name.endsWith(".dpsx") -> LocalDocumentKind.PPTX
            name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".et") -> LocalDocumentKind.SPREADSHEET
            name.endsWith(".doc") || name.endsWith(".wps") -> LocalDocumentKind.LEGACY_WORD
            name.endsWith(".ppt") || name.endsWith(".dps") -> LocalDocumentKind.LEGACY_POWERPOINT
            name.endsWith(".txt") || name.endsWith(".md") -> LocalDocumentKind.TEXT
            mime == "application/pdf" -> LocalDocumentKind.PDF
            mime == DOCX_MIME -> LocalDocumentKind.DOCX
            mime == PPTX_MIME -> LocalDocumentKind.PPTX
            mime in spreadsheetMimes -> LocalDocumentKind.SPREADSHEET
            mime == "application/msword" -> LocalDocumentKind.LEGACY_WORD
            mime == "application/vnd.ms-powerpoint" -> LocalDocumentKind.LEGACY_POWERPOINT
            mime == "text/plain" || mime == "text/markdown" -> LocalDocumentKind.TEXT
            else -> LocalDocumentKind.UNKNOWN
        }
    }

    private fun inspectZip(context: Context, uri: Uri): LocalDocumentKind {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    repeat(MAX_ZIP_ENTRIES) {
                        val entry = zip.nextEntry ?: return@use LocalDocumentKind.UNKNOWN
                        packageEntryKind(entry.name).takeIf { it != LocalDocumentKind.UNKNOWN }
                            ?.let { return@use it }
                    }
                    LocalDocumentKind.UNKNOWN
                }
            } ?: LocalDocumentKind.UNKNOWN
        }.getOrDefault(LocalDocumentKind.UNKNOWN)
    }

    internal fun packageEntryKind(entryName: String): LocalDocumentKind {
        val path = entryName.replace('\\', '/').lowercase()
        return when {
            path == "word/document.xml" -> LocalDocumentKind.DOCX
            path == "ppt/presentation.xml" || path.startsWith("ppt/slides/slide") -> LocalDocumentKind.PPTX
            path == "xl/workbook.xml" -> LocalDocumentKind.SPREADSHEET
            else -> LocalDocumentKind.UNKNOWN
        }
    }

    private fun legacyKind(sourceName: String, mimeType: String?): LocalDocumentKind =
        detectFromMetadata(sourceName, mimeType).takeIf {
            it == LocalDocumentKind.LEGACY_WORD ||
                it == LocalDocumentKind.LEGACY_POWERPOINT ||
                it == LocalDocumentKind.SPREADSHEET
        } ?: LocalDocumentKind.UNKNOWN

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        startsWithBytes(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArray.startsWithBytes(value: ByteArray): Boolean =
        size >= value.size && value.indices.all { this[it] == value[it] }

    private const val MAX_ZIP_ENTRIES = 4_096
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private val spreadsheetMimes = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
        "application/x-et",
    )
}
