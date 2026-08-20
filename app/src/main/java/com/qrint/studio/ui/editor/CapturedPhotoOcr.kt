package com.qrint.studio.ui.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import com.qrint.ppocr.PpOcrMobile
import com.qrint.ppocr.PpOcrOptions
import com.qrint.studio.render.ImageLoader
import com.qrint.studio.render.OfflineTextRecognizer
import com.qrint.studio.render.OfflineTextScan
import java.io.File
import java.util.concurrent.Executor

/** High-resolution still-photo OCR shared by text import and scan-to-template. */
internal object CapturedPhotoOcr {
    private const val MAX_DECODE_EDGE = 2_600
    private val STILL_PHOTO_OPTIONS = PpOcrOptions(
        // Still photos are processed once rather than on every camera frame, so a moderately
        // larger detector input improves small-print recall without keeping a second OCR engine.
        detectionLongSide = 1_152,
    )

    fun capture(
        context: Context,
        imageCapture: ImageCapture,
        region: CameraScanRegion,
        worker: Executor,
        sourceTag: String,
        onComplete: (Result<OfflineTextScan>) -> Unit,
    ) {
        val directory = File(context.cacheDir, "captured-ocr")
        if (!directory.isDirectory && !directory.mkdirs()) {
            onComplete(Result.failure(IllegalStateException("无法创建拍照识别缓存目录")))
            return
        }
        val photo = runCatching { File.createTempFile("lingyin-", ".jpg", directory) }
            .getOrElse { error ->
                onComplete(Result.failure(IllegalStateException("无法准备拍照文件", error)))
                return
            }
        val output = ImageCapture.OutputFileOptions.Builder(photo).build()
        try {
            imageCapture.takePicture(
                output,
                worker,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        val recognized = runCatching { recognizePhoto(context, photo, region, sourceTag) }
                        if (photo.exists()) photo.delete()
                        onComplete(recognized)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (photo.exists()) photo.delete()
                        onComplete(
                            Result.failure(
                                IllegalStateException(
                                    "拍照失败：${exception.message ?: "相机没有返回照片"}",
                                    exception,
                                ),
                            ),
                        )
                    }
                },
            )
        } catch (error: Throwable) {
            if (photo.exists()) photo.delete()
            onComplete(Result.failure(IllegalStateException("无法启动拍照", error)))
        }
    }

    private fun recognizePhoto(
        context: Context,
        photo: File,
        region: CameraScanRegion,
        sourceTag: String,
    ): OfflineTextScan {
        val source = ImageLoader.load(context, photo.absolutePath, MAX_DECODE_EDGE, MAX_DECODE_EDGE)
            ?: error("无法读取刚拍摄的照片")
        val crop = region.toPixelCrop(source.width, source.height)
        val selected = Bitmap.createBitmap(source, crop.left, crop.top, crop.width, crop.height)
        try {
            val result = PpOcrMobile.recognize(context, selected, STILL_PHOTO_OPTIONS)
            val scan = OfflineTextRecognizer.fromPpOcrResult("camera://capture/$sourceTag", result)
            require(scan.lines.isNotEmpty()) {
                "蓝框内没有识别到清晰文字，请让文字占满选区、点按对焦后重新拍照"
            }
            return scan
        } finally {
            if (selected !== source && !selected.isRecycled) selected.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }
}
