package com.qrint.studio.render

import android.content.Context
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.qrint.studio.model.BarcodeType

data class DecodedBarcode(val content: String, val type: BarcodeType)

object BarcodeDecoder {
    private val supportedFormats = listOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.PDF_417,
        BarcodeFormat.AZTEC,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODABAR,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.ITF,
    )
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.POSSIBLE_FORMATS to supportedFormats,
    )

    fun decode(context: Context, uri: Uri): Result<DecodedBarcode> = runCatching {
        val bitmap = ImageLoader.load(context, uri.toString(), 1_600, 1_600)
            ?: error("无法读取所选图片")
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            decodeSource(source)
        } finally {
            bitmap.recycle()
        }
    }

    /** Fast camera path: decodes the compact, correctly rotated Y plane without creating a Bitmap. */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): Result<DecodedBarcode> = runCatching {
        require(width > 0 && height > 0 && luminance.size >= width * height) { "无效的相机帧" }
        decodeSources(
            listOf(
                PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false),
                PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, true),
            ),
        )
    }

    private fun decodeSource(source: com.google.zxing.LuminanceSource): DecodedBarcode =
        decodeSources(listOf(source))

    private fun decodeSources(baseSources: List<com.google.zxing.LuminanceSource>): DecodedBarcode {
        var lastFailure: Throwable? = null
        val sources = baseSources.flatMap(::rotatedSources).flatMap { listOf(it, it.invert()) }
        sources.forEach { candidate ->
            val binaries = listOf(
                BinaryBitmap(HybridBinarizer(candidate)),
                BinaryBitmap(GlobalHistogramBinarizer(candidate)),
            )
            binaries.forEach { binary ->
                val reader = MultiFormatReader()
                try {
                    val result = reader.decode(binary, hints)
                    require(result.text.isNotBlank()) { "识别结果为空" }
                    return DecodedBarcode(result.text, result.barcodeFormat.toModel())
                } catch (failure: Throwable) {
                    lastFailure = failure
                } finally {
                    reader.reset()
                }
            }
        }
        throw lastFailure ?: com.google.zxing.NotFoundException.getNotFoundInstance()
    }

    private fun rotatedSources(source: com.google.zxing.LuminanceSource): List<com.google.zxing.LuminanceSource> {
        if (!source.isRotateSupported) return listOf(source)
        return buildList {
            var current = source
            repeat(4) {
                add(current)
                current = current.rotateCounterClockwise()
            }
        }
    }

    internal fun BarcodeFormat.toModel(): BarcodeType = when (this) {
        BarcodeFormat.QR_CODE -> BarcodeType.QR_CODE
        BarcodeFormat.DATA_MATRIX -> BarcodeType.DATA_MATRIX
        BarcodeFormat.PDF_417 -> BarcodeType.PDF_417
        BarcodeFormat.AZTEC -> BarcodeType.AZTEC
        BarcodeFormat.CODE_39 -> BarcodeType.CODE_39
        BarcodeFormat.CODE_93 -> BarcodeType.CODE_93
        BarcodeFormat.CODABAR -> BarcodeType.CODABAR
        BarcodeFormat.EAN_13 -> BarcodeType.EAN_13
        BarcodeFormat.EAN_8 -> BarcodeType.EAN_8
        BarcodeFormat.UPC_A -> BarcodeType.UPC_A
        BarcodeFormat.UPC_E -> BarcodeType.UPC_E
        BarcodeFormat.ITF -> BarcodeType.ITF
        else -> BarcodeType.CODE_128
    }
}
