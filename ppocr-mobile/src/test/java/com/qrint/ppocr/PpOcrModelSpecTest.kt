package com.qrint.ppocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PpOcrModelSpecTest {
    @Test
    fun bundledOfficialModelsMatchPinnedSizesAndHashes() {
        listOf(PpOcrModelSpec.DETECTION, PpOcrModelSpec.RECOGNITION).forEach { model ->
            val file = assetFile(model.assetPath)
            assertEquals("Unexpected size for ${model.fileName}", model.bytes, file.length())
            assertEquals("Unexpected hash for ${model.fileName}", model.sha256, file.sha256())
        }
    }

    @Test
    fun accuracyDefaultsMatchTheOfficialV6SmallDetectionConfig() {
        val options = PpOcrOptions()
        assertEquals(0.20f, options.detectionPixelThreshold)
        assertEquals(0.45f, options.detectionBoxThreshold)
        assertEquals(1.40f, options.unclipRatio)
        assertEquals(48, PpOcrModelSpec.RECOGNITION_HEIGHT)
        assertEquals(18_710, PpOcrModelSpec.RECOGNITION_CLASS_COUNT)
    }

    private fun assetFile(assetPath: String): File {
        val relative = assetPath.replace('/', File.separatorChar)
        return sequenceOf(
            File("src/main/assets", relative),
            File("ppocr-mobile/src/main/assets", relative),
        ).firstOrNull(File::isFile)
            ?: error("Missing test asset: $assetPath")
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
