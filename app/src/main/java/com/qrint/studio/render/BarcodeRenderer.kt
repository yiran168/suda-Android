package com.qrint.studio.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qrint.studio.model.BarcodeType
import kotlin.math.max
import kotlin.math.min

data class BarcodeBitmap(
    val bitmap: Bitmap,
    val normalized: NormalizedBarcode,
    val warning: String? = null,
)

object BarcodeRenderer {
    fun render(
        type: BarcodeType,
        rawContent: String,
        width: Int,
        height: Int,
        caption: Boolean,
        qrErrorCorrection: String,
    ): BarcodeBitmap {
        val safeWidth = width.coerceAtLeast(32)
        val safeHeight = height.coerceAtLeast(32)
        val normalized = BarcodeNormalizer.normalize(type, rawContent)
        return try {
            val bitmap = encode(type, normalized.value, safeWidth, safeHeight, caption, qrErrorCorrection)
            BarcodeBitmap(bitmap, normalized)
        } catch (error: Exception) {
            // A deterministic QR fallback is preferable to a blank preview or a crash.
            val fallbackPayload = utf8Prefix("${type.label}:${normalized.value}", 1_200)
            val fallback = BarcodeNormalizer.normalize(BarcodeType.QR_CODE, fallbackPayload)
            val bitmap = runCatching {
                encode(BarcodeType.QR_CODE, fallback.value, safeWidth, safeHeight, false, "M")
            }.getOrElse {
                // This final payload is deliberately tiny and therefore encodable even at the
                // minimum editor size. No user input can make the preview disappear.
            encode(BarcodeType.QR_CODE, "LINGYIN:ENCODE_ERROR", safeWidth, safeHeight, false, "L")
            }
            BarcodeBitmap(bitmap, normalized, "${type.label} 无法在当前尺寸编码，已用 QR 保留原内容")
        }
    }

    @Throws(WriterException::class)
    private fun encode(
        type: BarcodeType,
        value: String,
        width: Int,
        height: Int,
        caption: Boolean,
        qrErrorCorrection: String,
    ): Bitmap {
        val captionHeight = if (caption && !type.twoDimensional) min(30, max(16, height / 5)) else 0
        val matrixHeight = max(24, height - captionHeight)
        val internalWidth = if (type.twoDimensional) max(width, 96) else max(width, 480)
        val internalHeight = if (type.twoDimensional) max(matrixHeight, 96) else max(matrixHeight, 120)
        val hints = mutableMapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to if (type.twoDimensional) 1 else 8,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        if (type == BarcodeType.QR_CODE || type == BarcodeType.GS1_QR) {
            hints[EncodeHintType.ERROR_CORRECTION] = when (qrErrorCorrection.uppercase()) {
                "L" -> ErrorCorrectionLevel.L
                "Q" -> ErrorCorrectionLevel.Q
                "H" -> ErrorCorrectionLevel.H
                else -> ErrorCorrectionLevel.M
            }
        }
        if (type == BarcodeType.GS1_128 || type == BarcodeType.GS1_QR || type == BarcodeType.GS1_DATA_MATRIX) {
            hints[EncodeHintType.GS1_FORMAT] = true
        }
        val matrix = MultiFormatWriter().encode(value, type.zxingFormat(), internalWidth, internalHeight, hints)
        val matrixBitmap = matrix.toBitmap()
        val scaled = Bitmap.createScaledBitmap(matrixBitmap, width, matrixHeight, false)
        if (scaled !== matrixBitmap) matrixBitmap.recycle()
        if (captionHeight == 0) return scaled

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(scaled, 0f, 0f, null)
        scaled.recycle()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = captionHeight * 0.62f
            textAlign = Paint.Align.CENTER
        }
        val shown = value.take(40)
        val baseline = height - (captionHeight - paint.textSize) / 2f - paint.descent()
        canvas.drawText(shown, width / 2f, baseline.coerceAtMost(height - 1f), paint)
        return output
    }

    private fun BitMatrix.toBitmap(): Bitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            pixels[y * width + x] = if (get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun utf8Prefix(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val output = StringBuilder()
        var used = 0
        for (char in value) {
            val encoded = char.toString().toByteArray(Charsets.UTF_8)
            if (used + encoded.size > maxBytes) break
            output.append(char)
            used += encoded.size
        }
        return output.toString()
    }

}

internal fun BarcodeType.zxingFormat(): BarcodeFormat = when (this) {
    BarcodeType.QR_CODE -> BarcodeFormat.QR_CODE
    BarcodeType.GS1_QR -> BarcodeFormat.QR_CODE
    BarcodeType.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    BarcodeType.GS1_DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    BarcodeType.PDF_417 -> BarcodeFormat.PDF_417
    BarcodeType.AZTEC -> BarcodeFormat.AZTEC
    BarcodeType.CODE_128 -> BarcodeFormat.CODE_128
    BarcodeType.GS1_128 -> BarcodeFormat.CODE_128
    BarcodeType.CODE_39 -> BarcodeFormat.CODE_39
    BarcodeType.CODE_93 -> BarcodeFormat.CODE_93
    BarcodeType.CODABAR -> BarcodeFormat.CODABAR
    BarcodeType.EAN_13 -> BarcodeFormat.EAN_13
    BarcodeType.ISBN_13, BarcodeType.ISSN_13, BarcodeType.JAN_13 -> BarcodeFormat.EAN_13
    BarcodeType.EAN_8 -> BarcodeFormat.EAN_8
    BarcodeType.UPC_A -> BarcodeFormat.UPC_A
    BarcodeType.UPC_E -> BarcodeFormat.UPC_E
    BarcodeType.ITF -> BarcodeFormat.ITF
}

/** Pure ZXing probe used by the all-template smoke test without Android Bitmap mocks. */
internal fun isNativelyEncodable(type: BarcodeType, rawContent: String): Boolean = runCatching {
    val normalized = BarcodeNormalizer.normalize(type, rawContent)
    val hints = mutableMapOf<EncodeHintType, Any>(
        EncodeHintType.MARGIN to if (type.twoDimensional) 1 else 8,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    if (type == BarcodeType.QR_CODE || type == BarcodeType.GS1_QR) {
        hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.M
    }
    if (type == BarcodeType.GS1_128 || type == BarcodeType.GS1_QR || type == BarcodeType.GS1_DATA_MATRIX) {
        hints[EncodeHintType.GS1_FORMAT] = true
    }
    MultiFormatWriter().encode(
        normalized.value,
        type.zxingFormat(),
        if (type.twoDimensional) 160 else 640,
        if (type.twoDimensional) 160 else 180,
        hints,
    )
}.isSuccess
