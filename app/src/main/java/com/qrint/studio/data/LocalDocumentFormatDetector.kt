package com.qrint.studio.data

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.nio.charset.StandardCharsets
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
    ENCRYPTED_OFFICE,
    TEXT,
    UNKNOWN,
}

internal data class OleStreamEvidence(
    val wordDocument: Boolean = false,
    val powerPointDocument: Boolean = false,
    val workbook: Boolean = false,
    val encryptedPackage: Boolean = false,
) {
    operator fun plus(other: OleStreamEvidence): OleStreamEvidence = OleStreamEvidence(
        wordDocument = wordDocument || other.wordDocument,
        powerPointDocument = powerPointDocument || other.powerPointDocument,
        workbook = workbook || other.workbook,
        encryptedPackage = encryptedPackage || other.encryptedPackage,
    )

    fun kind(): LocalDocumentKind = when {
        encryptedPackage -> LocalDocumentKind.ENCRYPTED_OFFICE
        wordDocument -> LocalDocumentKind.LEGACY_WORD
        powerPointDocument -> LocalDocumentKind.LEGACY_POWERPOINT
        workbook -> LocalDocumentKind.SPREADSHEET
        else -> LocalDocumentKind.UNKNOWN
    }
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
            header.startsWithBytes(oleSignature) -> inspectOle(context, uri, sourceName, mimeType)
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

    /**
     * OLE/CFB providers frequently report Word and PowerPoint files as `application/vnd.ms-excel`.
     * Stream names are stored in the compound file itself, so they are a stronger discriminator
     * than either the display name or MIME type. The probe is streaming and bounded to avoid a
     * second full-document allocation before the real importer runs.
     */
    private fun inspectOle(
        context: Context,
        uri: Uri,
        sourceName: String,
        mimeType: String?,
    ): LocalDocumentKind {
        val streamKind = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(OLE_PROBE_CHUNK_BYTES)
                var carry = ByteArray(0)
                var evidence = OleStreamEvidence()
                var total = 0L
                while (total < MAX_OLE_PROBE_BYTES) {
                    val maximumRead = minOf(buffer.size.toLong(), MAX_OLE_PROBE_BYTES - total).toInt()
                    val read = input.read(buffer, 0, maximumRead)
                    if (read < 0) break
                    if (read == 0) break
                    total += read
                    val probe = ByteArray(carry.size + read)
                    carry.copyInto(probe)
                    buffer.copyInto(probe, destinationOffset = carry.size, endIndex = read)
                    evidence += detectOleStreamEvidence(probe)
                    val carryStart = (probe.size - OLE_PROBE_OVERLAP_BYTES).coerceAtLeast(0)
                    carry = probe.copyOfRange(carryStart, probe.size)
                }
                evidence.kind()
            } ?: LocalDocumentKind.UNKNOWN
        }.getOrDefault(LocalDocumentKind.UNKNOWN)
        return if (streamKind != LocalDocumentKind.UNKNOWN) streamKind
        else legacyKind(sourceName, mimeType)
    }

    internal fun detectOleStreamEvidence(bytes: ByteArray): OleStreamEvidence = OleStreamEvidence(
        wordDocument = bytes.containsAnyEncoding("WordDocument"),
        powerPointDocument = bytes.containsAnyEncoding("PowerPoint Document"),
        workbook = bytes.containsAnyEncoding("Workbook") || bytes.containsAnyEncoding("Book"),
        encryptedPackage = bytes.containsAnyEncoding("EncryptedPackage"),
    )

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

    private fun ByteArray.containsAnyEncoding(value: String): Boolean =
        containsSequence(value.toByteArray(StandardCharsets.UTF_16LE)) ||
            containsSequence(value.toByteArray(StandardCharsets.US_ASCII))

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
        if (sequence.isEmpty() || size < sequence.size) return false
        val lastStart = size - sequence.size
        for (start in 0..lastStart) {
            var index = 0
            while (index < sequence.size && this[start + index] == sequence[index]) index++
            if (index == sequence.size) return true
        }
        return false
    }

    private const val MAX_ZIP_ENTRIES = 4_096
    private const val MAX_OLE_PROBE_BYTES = 96L * 1024L * 1024L
    private const val OLE_PROBE_CHUNK_BYTES = 64 * 1024
    private const val OLE_PROBE_OVERLAP_BYTES = 128
    private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    private val spreadsheetMimes = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.ms-excel",
        "application/x-et",
    )
}
