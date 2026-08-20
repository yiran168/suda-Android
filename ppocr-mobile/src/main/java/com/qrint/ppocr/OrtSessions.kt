package com.qrint.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.security.MessageDigest

internal class OrtSessions(context: Context) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val detection: OrtSession
    private val recognition: OrtSession
    private val detectionInputName: String
    private val recognitionInputName: String
    val recognitionClassCount: Int
    val modelLoadMs: Long

    init {
        val started = System.currentTimeMillis()
        try {
            val installed = ModelAssetInstaller.installAll(context)
            val threadCount = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(threadCount)
                setInterOpNumThreads(1)
            }
            try {
                detection = environment.createSession(installed.detection.absolutePath, options)
                try {
                    recognition = environment.createSession(installed.recognition.absolutePath, options)
                } catch (error: Throwable) {
                    detection.close()
                    throw error
                }
            } finally {
                options.close()
            }
            detectionInputName = detection.inputNames.firstOrNull()
                ?: error("Detection model has no input")
            recognitionInputName = recognition.inputNames.firstOrNull()
                ?: error("Recognition model has no input")
            recognitionClassCount = validateModelContract()
        } catch (error: PpOcrException) {
            throw error
        } catch (error: Throwable) {
            throw PpOcrException.ModelLoad(error)
        }
        modelLoadMs = System.currentTimeMillis() - started
    }

    private fun validateModelContract(): Int {
        val detectionInput = tensorShape(detection, detectionInputName, input = true, stage = "检测输入")
        requireModel(detectionInput.size == 4 && detectionInput[1] == 3L) {
            "PP-OCRv6 Small 检测输入应为 N×3×H×W，实际为 ${detectionInput.contentToString()}"
        }
        val detectionOutput = firstOutputShape(detection, "检测输出")
        requireModel(detectionOutput.size == 4 && detectionOutput[1] == 1L) {
            "PP-OCRv6 Small 检测输出应为 N×1×H×W，实际为 ${detectionOutput.contentToString()}"
        }

        val recognitionInput = tensorShape(recognition, recognitionInputName, input = true, stage = "识别输入")
        requireModel(
            recognitionInput.size == 4 &&
                recognitionInput[1] == 3L &&
                recognitionInput[2] == PpOcrModelSpec.RECOGNITION_HEIGHT.toLong(),
        ) {
            "PP-OCRv6 Small 识别输入应为 N×3×${PpOcrModelSpec.RECOGNITION_HEIGHT}×W，实际为 ${recognitionInput.contentToString()}"
        }
        val recognitionOutput = firstOutputShape(recognition, "识别输出")
        requireModel(
            recognitionOutput.size == 3 &&
                recognitionOutput.last() == PpOcrModelSpec.RECOGNITION_CLASS_COUNT.toLong(),
        ) {
            "PP-OCRv6 Small 识别类别应为 ${PpOcrModelSpec.RECOGNITION_CLASS_COUNT}，实际为 ${recognitionOutput.contentToString()}"
        }
        return recognitionOutput.last().toInt()
    }

    private fun firstOutputShape(session: OrtSession, stage: String): LongArray {
        val outputName = session.outputNames.firstOrNull()
            ?: throw PpOcrException.ModelConfig("PP-OCR ${stage}没有输出")
        return tensorShape(session, outputName, input = false, stage = stage)
    }

    private fun tensorShape(session: OrtSession, name: String, input: Boolean, stage: String): LongArray {
        val node = if (input) session.inputInfo[name] else session.outputInfo[name]
        val tensor = node?.info as? TensorInfo
            ?: throw PpOcrException.ModelConfig("PP-OCR ${stage}不是浮点张量")
        return tensor.shape
    }

    private inline fun requireModel(condition: Boolean, message: () -> String) {
        if (!condition) throw PpOcrException.ModelConfig(message())
    }

    fun <T> runDetection(input: FloatArray, shape: LongArray, readOutput: (FloatBuffer, LongArray) -> T): T =
        run(detection, detectionInputName, input, shape, "文字检测", readOutput)

    fun <T> runRecognition(input: FloatArray, shape: LongArray, readOutput: (FloatBuffer, LongArray) -> T): T =
        run(recognition, recognitionInputName, input, shape, "文字识别", readOutput)

    private fun <T> run(
        session: OrtSession,
        inputName: String,
        input: FloatArray,
        shape: LongArray,
        stage: String,
        readOutput: (FloatBuffer, LongArray) -> T,
    ): T {
        val tensor = try {
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape)
        } catch (error: Throwable) {
            throw PpOcrException.Inference(stage, error)
        }
        var result: OrtSession.Result? = null
        try {
            result = session.run(mapOf(inputName to tensor))
            // Index access keeps the result path allocation-free and explicit.
            val value = result.get(0)
            val output = value as? OnnxTensor ?: error("Model output is not a float tensor")
            return readOutput(output.floatBuffer, output.info.shape)
        } catch (error: PpOcrException) {
            throw error
        } catch (error: Throwable) {
            throw PpOcrException.Inference(stage, error)
        } finally {
            result?.close()
            tensor.close()
        }
    }

    override fun close() {
        try {
            detection.close()
        } finally {
            recognition.close()
        }
    }
}

internal object ModelAssetInstaller {
    private const val TAG = "PpOcrModels"

    data class InstalledModels(
        val detection: File,
        val recognition: File,
    )

    fun installAll(context: Context): InstalledModels {
        removeLegacyCaches(context)
        val directory = File(context.noBackupFilesDir, PpOcrModelSpec.CACHE_DIRECTORY).also {
            if (!it.isDirectory && !it.mkdirs()) error("Cannot create OCR model directory")
        }
        return InstalledModels(
            detection = installAsset(context, directory, PpOcrModelSpec.DETECTION),
            recognition = installAsset(context, directory, PpOcrModelSpec.RECOGNITION),
        )
    }

    private fun installAsset(context: Context, directory: File, asset: ModelAsset): File {
        val target = File(directory, asset.fileName)
        if (target.isFile && target.length() == asset.bytes && sha256(target) == asset.sha256) return target

        val temporary = File(directory, ".${asset.fileName}.${android.os.Process.myPid()}.tmp")
        if (temporary.exists() && !temporary.delete()) error("Cannot clear stale OCR model file")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open(asset.assetPath).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val actualHash = digest.digest().toHex()
            check(temporary.length() == asset.bytes && actualHash == asset.sha256) {
                "Bundled OCR model checksum mismatch: ${asset.fileName}"
            }
            if (target.exists() && !target.delete()) error("Cannot replace old OCR model")
            if (!temporary.renameTo(target)) error("Cannot activate OCR model")
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun removeLegacyCaches(context: Context) {
        PpOcrModelSpec.LEGACY_CACHE_DIRECTORIES.forEach { directoryName ->
            val legacy = File(context.noBackupFilesDir, directoryName)
            if (legacy.exists() && !legacy.deleteRecursively()) {
                Log.w(TAG, "Unable to remove obsolete OCR model cache: $directoryName")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}
