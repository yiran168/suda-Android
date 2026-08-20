package com.qrint.ppocr

import android.content.Context
import android.graphics.Bitmap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide PP-OCRv6 Small entry point.
 *
 * Detection and recognition sessions are reused and all inference is serialized. Serial execution
 * is intentional: CameraX and gallery OCR never allocate two large activation graphs at once.
 */
object PpOcrMobile {
    const val MODEL_NAME = PpOcrModelSpec.DISPLAY_NAME

    // Stable ComponentCallbacks2 contract values. Android 14 deprecated only the symbolic
    // RUNNING_* constants, while older Android versions still deliver these levels.
    private const val TRIM_RUNNING_LOW = 10
    private const val TRIM_RUNNING_CRITICAL = 15
    private const val TRIM_UI_HIDDEN = 20

    private val engineLock = ReentrantLock(true)

    @Volatile
    private var engine: PpOcrEngine? = null

    fun recognize(
        context: Context,
        bitmap: Bitmap,
        options: PpOcrOptions = PpOcrOptions(),
    ): PpOcrResult {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw PpOcrException.InvalidImage("OCR 图片为空或已经释放")
        }
        return engineLock.withLock {
            val active = engine ?: PpOcrEngine(context.applicationContext).also { engine = it }
            active.recognize(bitmap, options)
        }
    }

    /** Releases native model sessions. The next recognition recreates them lazily. */
    fun release() {
        engineLock.withLock(::closeEngineLocked)
    }

    /** Never blocks the UI thread behind an in-flight camera inference. */
    fun onTrimMemory(level: Int) {
        val shouldRelease = level == TRIM_RUNNING_LOW ||
            level == TRIM_RUNNING_CRITICAL ||
            level >= TRIM_UI_HIDDEN
        if (!shouldRelease || !engineLock.tryLock()) return
        try {
            closeEngineLocked()
        } finally {
            engineLock.unlock()
        }
    }

    /** A close failure must not leave a closed native session reachable by the next OCR request. */
    private fun closeEngineLocked() {
        val active = engine
        engine = null
        runCatching { active?.close() }
    }
}
