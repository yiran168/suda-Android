package com.qrint.studio.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.qrint.studio.data.CapturedMediaStore
import com.qrint.studio.render.ImageLoader
import kotlin.math.max
import kotlin.math.roundToInt

/** Crops the same EXIF-oriented pixels shown by [PhotoCropSheet]. */
internal object CapturedPhotoCropper {
    private const val EDITING_MAX_DIMENSION = 1_600

    fun loadPreview(context: Context, uri: Uri): Bitmap? {
        val dimensions = ImageLoader.dimensions(context, uri.toString()) ?: return null
        val scale = (EDITING_MAX_DIMENSION.toFloat() / max(dimensions.width, dimensions.height))
            .coerceAtMost(1f)
        return ImageLoader.load(
            context,
            uri.toString(),
            (dimensions.width * scale).roundToInt().coerceAtLeast(1),
            (dimensions.height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    fun cropFreehand(
        context: Context,
        uri: Uri,
        selection: FreehandPhotoSelection,
    ): Result<Uri> = runCatching {
        require(selection.isUsable) { "请先用手指沿要打印的内容画一圈" }
        val source = loadPreview(context, uri) ?: error("无法读取拍摄的照片")
        var output: Bitmap? = null
        try {
            val bounds = selection.pixelBounds(source.width, source.height)
            output = Bitmap.createBitmap(bounds.width, bounds.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val clip = Path().apply {
                selection.points.forEachIndexed { index, point ->
                    val x = point.x * source.width - bounds.left
                    val y = point.y * source.height - bounds.top
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            val checkpoint = canvas.save()
            canvas.clipPath(clip)
            canvas.drawBitmap(
                source,
                -bounds.left.toFloat(),
                -bounds.top.toFloat(),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
            )
            canvas.restoreToCount(checkpoint)
            CapturedMediaStore.saveBitmap(context, output, "photo-lasso").getOrThrow()
        } finally {
            output?.takeIf { it !== source && !it.isRecycled }?.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }
}
