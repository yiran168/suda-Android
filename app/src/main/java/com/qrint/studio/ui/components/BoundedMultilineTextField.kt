package com.qrint.studio.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Keeps editable text inside a Compose-safe viewport while preserving the complete value.
 *
 * Property panels are hosted by a vertically scrolling parent, so an unconstrained multiline
 * text field may otherwise request hundreds of thousands of pixels for a long receipt or
 * imported document. The field remains internally scrollable; only its on-screen measurement
 * is bounded and no text is truncated from the model or print pipeline.
 */
@Composable
fun BoundedMultilineTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    minLines: Int = DEFAULT_MULTILINE_MIN_LINES,
    maxLines: Int = DEFAULT_MULTILINE_MAX_LINES,
) {
    val visibleLines = normalizedEditorTextFieldLines(minLines, maxLines)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingText?.let { message -> ({ Text(message) }) },
        minLines = visibleLines.first,
        maxLines = visibleLines.last,
        modifier = modifier.heightIn(max = MAX_MULTILINE_FIELD_HEIGHT_DP.dp),
    )
}

internal const val DEFAULT_MULTILINE_MIN_LINES = 2
internal const val DEFAULT_MULTILINE_MAX_LINES = 8
internal const val MAX_MULTILINE_VISIBLE_LINES = 8
internal const val MAX_MULTILINE_FIELD_HEIGHT_DP = 240

internal fun normalizedEditorTextFieldLines(minLines: Int, maxLines: Int): IntRange {
    val safeMin = minLines.coerceIn(1, MAX_MULTILINE_VISIBLE_LINES)
    val safeMax = maxLines.coerceIn(safeMin, MAX_MULTILINE_VISIBLE_LINES)
    return safeMin..safeMax
}
