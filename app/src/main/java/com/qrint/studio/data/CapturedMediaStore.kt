package com.qrint.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.qrint.studio.BuildConfig
import java.io.File
import java.util.UUID

/** Keeps captured originals in app-private storage so saved templates never point at temp files. */
object CapturedMediaStore {
    private const val MAX_IMPORTED_IMAGE_BYTES = 32 * 1024 * 1024

    fun createImageUri(context: Context): Uri {
        val directory = mediaDirectory(context)
        val file = File(directory, "capture-${System.currentTimeMillis()}-${UUID.randomUUID()}.jpg")
        check(file.createNewFile()) { "无法创建照片文件" }
        return FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
    }

    fun deleteIfEmpty(context: Context, uri: Uri) {
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
            File(File(context.filesDir, "captured_media"), name).takeIf { it.exists() && it.length() == 0L }?.delete()
        }
    }

    /** Validates camera output without decoding a full-resolution photo into the UI process. */
    fun validateImage(context: Context, uri: Uri): Result<Unit> = runCatching {
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: error("相机没有返回照片文件")
        descriptor.use {
            require(it.length != 0L) { "相机返回了空照片" }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val input = context.contentResolver.openInputStream(uri) ?: error("无法读取拍摄的照片")
        input.use { BitmapFactory.decodeStream(it, null, bounds) }
        requireImageBounds(
            bounds.outWidth,
            bounds.outHeight,
            invalidMessage = "拍摄结果不是可识别的图片",
            oversizedMessage = "照片尺寸过大，请降低相机分辨率后重试",
        )
    }

    /** Copies a shared image into private storage so a saved template never loses URI access. */
    fun importImage(context: Context, source: Uri): Result<Uri> = runCatching {
        val directory = mediaDirectory(context)
        val extension = when (context.contentResolver.getType(source)?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(directory, "import-${System.currentTimeMillis()}-${UUID.randomUUID()}.$extension")
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                file.outputStream().buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_IMPORTED_IMAGE_BYTES) { "图片超过 32 MB 限制" }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("无法读取分享的图片")
            require(file.length() > 0L) { "分享的图片为空" }
            val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
            validateImage(context, uri).getOrThrow()
            uri
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun saveBitmap(context: Context, bitmap: Bitmap, prefix: String = "document-page"): Result<Uri> = runCatching {
        val directory = mediaDirectory(context)
        val file = File(directory, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}.png")
        try {
            file.outputStream().buffered().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法保存文档页面" }
            }
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Saves an embedded Office image after validating both byte size and decode bounds. */
    fun saveImageBytes(
        context: Context,
        bytes: ByteArray,
        extension: String,
        prefix: String = "office-media",
    ): Result<Uri> = runCatching {
        require(bytes.isNotEmpty()) { "文档内嵌图片为空" }
        require(bytes.size <= MAX_IMPORTED_IMAGE_BYTES) { "文档内嵌图片超过 32 MB 限制" }
        val safeExtension = extension.lowercase().takeIf { it in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp") } ?: "png"
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        requireImageBounds(
            options.outWidth,
            options.outHeight,
            invalidMessage = "文档内嵌媒体不是可识别图片",
            oversizedMessage = "文档内嵌图片尺寸过大",
        )
        val directory = mediaDirectory(context)
        val file = File(directory, "$prefix-${System.currentTimeMillis()}-${UUID.randomUUID()}.$safeExtension")
        try {
            file.outputStream().buffered().use { it.write(bytes) }
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun delete(context: Context, uri: Uri) {
        runCatching {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
            File(File(context.filesDir, "captured_media"), name).takeIf(File::exists)?.delete()
        }
    }

    private fun requireImageBounds(
        width: Int,
        height: Int,
        invalidMessage: String,
        oversizedMessage: String,
    ) {
        require(ImageBoundsPolicy.hasPixels(width, height)) { invalidMessage }
        require(ImageBoundsPolicy.isSafe(width, height)) { oversizedMessage }
    }

    private fun mediaDirectory(context: Context): File =
        File(context.filesDir, "captured_media").also {
            check(it.isDirectory || it.mkdirs()) { "无法创建拍照存储目录" }
        }
}

/** Pure image metadata policy shared by camera, gallery and embedded Office media paths. */
internal object ImageBoundsPolicy {
    const val MAX_DIMENSION = 32_768

    fun hasPixels(width: Int, height: Int): Boolean = width > 0 && height > 0

    fun isSafe(width: Int, height: Int): Boolean =
        hasPixels(width, height) && width <= MAX_DIMENSION && height <= MAX_DIMENSION
}
