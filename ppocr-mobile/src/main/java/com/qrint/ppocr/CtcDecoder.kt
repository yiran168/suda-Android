package com.qrint.ppocr

import java.nio.FloatBuffer

internal data class DecodedText(val text: String, val confidence: Float)

internal object CtcDecoder {
    private const val BLANK_INDEX = 0

    fun decode(
        output: FloatBuffer,
        shape: LongArray,
        characters: List<String>,
    ): DecodedText {
        require(shape.size == 3 && shape[0] == 1L) { "Unexpected recognition output: ${shape.contentToString()}" }
        val timeSteps = shape[1].toInt()
        val classCount = shape[2].toInt()
        require(classCount == characters.size + 1) {
            "Recognition classes $classCount do not match dictionary ${characters.size + 1}"
        }
        val values = output.duplicate()
        values.rewind()
        return decodeValues(timeSteps, classCount, characters) { index -> values.get(index) }
    }

    internal fun decode(
        output: FloatArray,
        timeSteps: Int,
        classCount: Int,
        characters: List<String>,
    ): DecodedText {
        require(output.size >= timeSteps * classCount)
        return decodeValues(timeSteps, classCount, characters) { index -> output[index] }
    }

    private inline fun decodeValues(
        timeSteps: Int,
        classCount: Int,
        characters: List<String>,
        valueAt: (Int) -> Float,
    ): DecodedText {
        val text = StringBuilder()
        var previous = -1
        var confidenceSum = 0.0
        var kept = 0
        for (time in 0 until timeSteps) {
            val offset = time * classCount
            var bestIndex = 0
            var bestValue = valueAt(offset)
            for (candidate in 1 until classCount) {
                val value = valueAt(offset + candidate)
                if (value > bestValue) {
                    bestIndex = candidate
                    bestValue = value
                }
            }
            if (bestIndex != BLANK_INDEX && bestIndex != previous) {
                val characterIndex = bestIndex - 1
                if (characterIndex in characters.indices) {
                    text.append(characters[characterIndex])
                    confidenceSum += bestValue.coerceIn(0f, 1f)
                    kept++
                }
            }
            previous = bestIndex
        }
        return DecodedText(
            text = text.toString(),
            confidence = if (kept == 0) 0f else (confidenceSum / kept).toFloat(),
        )
    }
}
