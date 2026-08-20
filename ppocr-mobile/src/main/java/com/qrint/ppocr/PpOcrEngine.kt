package com.qrint.ppocr

import android.content.Context
import android.graphics.Bitmap
import java.io.Closeable

internal class PpOcrEngine(context: Context) : Closeable {
    private val sessions: OrtSessions
    private val characters: List<String>
    private var reportColdLoad = true
    private var closed = false

    init {
        val activeSessions = OrtSessions(context)
        try {
            characters = ModelDictionary.load(context)
            if (characters.size + 1 != activeSessions.recognitionClassCount) {
                throw PpOcrException.ModelConfig(
                    "PP-OCR 字典与识别模型不匹配：${characters.size + 1}/${activeSessions.recognitionClassCount}",
                )
            }
            sessions = activeSessions
        } catch (error: Throwable) {
            runCatching { activeSessions.close() }
            throw error
        }
    }

    fun recognize(source: Bitmap, options: PpOcrOptions): PpOcrResult {
        check(!closed) { "PP-OCR engine has been released" }
        val totalStarted = System.currentTimeMillis()
        val detectionStarted = totalStarted
        val detectionInput = ImageTensorFactory.detection(source, options.detectionLongSide)
        val boxes = sessions.runDetection(detectionInput.values, detectionInput.shape) { values, shape ->
            DbPostProcessor.process(
                output = values,
                shape = shape,
                originalWidth = source.width,
                originalHeight = source.height,
                options = options,
            )
        }
        val detectionMs = System.currentTimeMillis() - detectionStarted

        val recognitionStarted = System.currentTimeMillis()
        val lines = ArrayList<PpOcrLine>(boxes.size)
        for (detected in boxes) {
            val crop = try {
                QuadCropper.crop(source, detected.quad)
            } catch (_: PpOcrException.InvalidImage) {
                continue
            }
            try {
                val input = ImageTensorFactory.recognition(crop, options.recognitionMaxWidth)
                val decoded = sessions.runRecognition(input.values, input.shape) { values, shape ->
                    CtcDecoder.decode(values, shape, characters)
                }
                val text = decoded.text.trim()
                if (text.isNotEmpty() && decoded.confidence >= options.recognitionScoreThreshold) {
                    lines += PpOcrLine(
                        text = text,
                        confidence = decoded.confidence,
                        box = detected.quad,
                    )
                }
            } finally {
                crop.recycle()
            }
        }
        val recognitionMs = System.currentTimeMillis() - recognitionStarted
        val coldLoad = if (reportColdLoad) sessions.modelLoadMs else 0L
        reportColdLoad = false
        return PpOcrResult(
            imageWidth = source.width,
            imageHeight = source.height,
            lines = lines,
            timings = PpOcrTimings(
                modelLoadMs = coldLoad,
                detectionMs = detectionMs,
                recognitionMs = recognitionMs,
                totalMs = System.currentTimeMillis() - totalStarted,
            ),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        sessions.close()
    }
}
