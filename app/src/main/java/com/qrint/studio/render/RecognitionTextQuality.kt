package com.qrint.studio.render

import java.text.Normalizer
import kotlin.math.max

internal fun canonicalRecognitionText(value: String): String = Normalizer
    .normalize(value, Normalizer.Form.NFKC)
    .lineSequence()
    .map { line -> line.trim().replace(RECOGNITION_WHITESPACE, " ") }
    .filter(String::isNotBlank)
    .joinToString("\n")

/** Accuracy is only meaningful when [reference] is labelled ground truth. */
internal fun textAccuracy(reference: String, prediction: String): Float =
    measureTextAccuracy(reference, prediction).accuracy

internal data class TextAccuracyMeasurement(
    val characters: Long,
    val errors: Long,
) {
    val accuracy: Float
        get() = if (characters <= 0L) 1f else (1f - errors.toFloat() / characters).coerceIn(0f, 1f)
}

internal fun measureTextAccuracy(reference: String, prediction: String): TextAccuracyMeasurement {
    val truth = canonicalRecognitionText(reference)
    val output = canonicalRecognitionText(prediction)
    val characters = max(truth.length, output.length).toLong()
    return TextAccuracyMeasurement(characters, levenshteinDistance(truth, output).toLong())
}

private fun levenshteinDistance(left: String, right: String): Int {
    if (left.isEmpty()) return right.length
    if (right.isEmpty()) return left.length
    var previous = IntArray(right.length + 1) { it }
    var current = IntArray(right.length + 1)
    for (leftIndex in left.indices) {
        current[0] = leftIndex + 1
        for (rightIndex in right.indices) {
            val substitution = previous[rightIndex] + if (left[leftIndex] == right[rightIndex]) 0 else 1
            current[rightIndex + 1] = minOf(
                current[rightIndex] + 1,
                previous[rightIndex + 1] + 1,
                substitution,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[right.length]
}

private val RECOGNITION_WHITESPACE = Regex("[\\t\\x0B\\f\\r ]+")
