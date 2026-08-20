package com.qrint.ppocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ModelDictionaryTest {
    @Test
    fun parsesUnicodeAndYamlQuotedScalars() {
        val yaml = """
            Global:
              model_name: test
            PostProcess:
              name: CTCLabelDecode
              character_dict:
              - 你
              - '!'
              - ''''
              - "\\t"
            NextSection:
              value: ignored
        """.trimIndent()

        assertEquals(listOf("你", "!", "'", "\t"), ModelDictionary.parseYaml(yaml))
    }

    @Test
    fun bundledV6SmallDictionaryMatchesTheRecognitionHead() {
        val config = assetFile(PpOcrModelSpec.DICTIONARY_ASSET).readText(Charsets.UTF_8)
        val characters = ModelDictionary.loadModelCharacters(config)

        assertEquals(PpOcrModelSpec.DICTIONARY_ENTRY_COUNT + 1, characters.size)
        assertEquals(" ", characters.last())
        assertEquals(PpOcrModelSpec.RECOGNITION_CLASS_COUNT, characters.size + 1)
    }

    private fun assetFile(assetPath: String): File {
        val relative = assetPath.replace('/', File.separatorChar)
        return sequenceOf(
            File("src/main/assets", relative),
            File("ppocr-mobile/src/main/assets", relative),
        ).firstOrNull(File::isFile)
            ?: error("Missing test asset: $assetPath")
    }
}
