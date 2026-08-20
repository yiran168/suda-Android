package com.qrint.ppocr

import android.content.Context

internal object ModelDictionary {
    fun load(context: Context): List<String> {
        val content = try {
            context.assets.open(PpOcrModelSpec.DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (error: Throwable) {
            throw PpOcrException.ModelConfig("无法读取 PP-OCR 字符字典", error)
        }
        return loadModelCharacters(content)
    }

    internal fun loadModelCharacters(content: String): List<String> {
        val hasExpectedModel = content.lineSequence().any {
            it.trim() == "model_name: ${PpOcrModelSpec.RECOGNITION_MODEL_NAME}"
        }
        if (!hasExpectedModel) {
            throw PpOcrException.ModelConfig("PP-OCR 配置与内置识别模型不匹配")
        }
        val dictionary = parseYaml(content)
        if (dictionary.size != PpOcrModelSpec.DICTIONARY_ENTRY_COUNT) {
            throw PpOcrException.ModelConfig(
                "PP-OCR 字符字典数量 ${dictionary.size}，应为 ${PpOcrModelSpec.DICTIONARY_ENTRY_COUNT}",
            )
        }
        // The exported CTC head reserves one class for a regular space after the YAML dictionary.
        return dictionary + " "
    }

    internal fun parseYaml(content: String): List<String> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').lines()
        val header = lines.indexOfFirst { it.trim() == "character_dict:" }
        if (header < 0) throw PpOcrException.ModelConfig("PP-OCR 字符字典缺少 character_dict")
        val headerIndent = leadingSpaces(lines[header])
        val output = ArrayList<String>(18_400)
        for (index in header + 1 until lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank() || rawLine.trimStart().startsWith('#')) continue
            val indent = leadingSpaces(rawLine)
            val trimmed = rawLine.substring(indent)
            if (trimmed.startsWith("-")) {
                output += parseYamlScalar(trimmed.substring(1))
            } else if (indent <= headerIndent) {
                break
            }
        }
        if (output.isEmpty()) throw PpOcrException.ModelConfig("PP-OCR 字符字典为空")
        return output
    }

    private fun leadingSpaces(value: String): Int {
        var count = 0
        while (count < value.length && value[count] == ' ') count++
        return count
    }

    private fun parseYamlScalar(raw: String): String {
        val value = raw.trimStart()
        if (value.length >= 2 && value.first() == '\'' && value.last() == '\'') {
            return value.substring(1, value.lastIndex).replace("''", "'")
        }
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            return value.substring(1, value.lastIndex)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
        }
        return value
    }
}
