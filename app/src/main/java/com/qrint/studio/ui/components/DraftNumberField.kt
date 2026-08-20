package com.qrint.studio.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction

/**
 * Numeric field with an independent editing draft.
 *
 * A numeric model cannot represent an empty or partially typed value such as `-` or `12.`.
 * Binding the field directly to that model immediately restores the previous number, so users
 * cannot clear and replace it. Keep the draft while focused, report edits for validation, and
 * resynchronise with the canonical model value only after focus leaves the field.
 */
@Composable
fun DraftNumberField(
    label: String,
    value: String,
    onDraftChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    inputTransform: (String) -> String = { it },
    onCommit: (String) -> Unit = {},
) {
    var draft by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(value, focused) {
        if (!focused) draft = value
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { raw ->
            val next = inputTransform(raw)
            draft = next
            onDraftChange(next)
        },
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(draft) }),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                val wasFocused = focused
                focused = state.isFocused
                if (wasFocused && !focused) {
                    onCommit(draft)
                    draft = value
                }
            },
    )
}
