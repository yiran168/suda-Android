package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiSelectionPanel(
    count: Int,
    persistentlyGrouped: Boolean,
    onAlign: (SelectionAlignment) -> Unit,
    onDistributeHorizontal: () -> Unit,
    onDistributeVertical: () -> Unit,
    onRotateStart: () -> Unit,
    onRotateBy: (Float) -> Unit,
    onRotateEnd: () -> Unit,
    onGroup: () -> Unit,
    onUngroup: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var relativeRotation by remember(count) { mutableFloatStateOf(0f) }
    var typedRotation by remember(count) { mutableStateOf("0") }
    var rotating by remember(count) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            Text("已选择 $count 个元素", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("拖动可整体移动，双指或外框八个控制点可整体缩放", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("整组外框在画布中的位置", fontWeight = FontWeight.SemiBold)
        Text(
            "左、中、右及上、中、下均以全部所选蓝色框的整体外边界为准，不改变框内文字或图片的排版。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(
                SelectionAlignment.LEFT to "整组靠左",
                SelectionAlignment.HORIZONTAL_CENTER to "整组水平居中",
                SelectionAlignment.RIGHT to "整组靠右",
                SelectionAlignment.TOP to "整组靠上",
                SelectionAlignment.VERTICAL_CENTER to "整组垂直居中",
                SelectionAlignment.BOTTOM to "整组靠下",
            ).forEach { (action, label) ->
                FilterChip(selected = false, onClick = { onAlign(action) }, label = { Text(label) })
            }
        }
        Text("等距分布（至少 3 个）", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onDistributeHorizontal, enabled = count >= 3, modifier = Modifier.weight(1f)) {
                Text("水平等距")
            }
            FilledTonalButton(onClick = onDistributeVertical, enabled = count >= 3, modifier = Modifier.weight(1f)) {
                Text("垂直等距")
            }
        }
        Text("整组旋转", fontWeight = FontWeight.SemiBold)
        Text("滑动可无级旋转，元素的相对位置与各自选框会同步变化", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = relativeRotation,
            onValueChange = { next ->
                if (!rotating) {
                    rotating = true
                    onRotateStart()
                }
                onRotateBy(next - relativeRotation)
                relativeRotation = next
                typedRotation = next.toInt().toString()
            },
            onValueChangeFinished = {
                if (rotating) onRotateEnd()
                rotating = false
            },
            valueRange = -180f..180f,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = typedRotation,
                onValueChange = { typedRotation = it.filter { char -> char.isDigit() || char == '-' || char == '.' } },
                label = { Text("相对角度（°）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = {
                    typedRotation.toFloatOrNull()?.coerceIn(-360f, 360f)?.let { degrees ->
                        onRotateStart()
                        onRotateBy(degrees)
                        onRotateEnd()
                        relativeRotation = (relativeRotation + degrees).coerceIn(-180f, 180f)
                    }
                },
            ) { Text("旋转") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = if (persistentlyGrouped) onUngroup else onGroup,
                modifier = Modifier.weight(1f),
            ) {
                Icon(if (persistentlyGrouped) Icons.Rounded.GroupOff else Icons.Rounded.Group, null)
                Spacer(Modifier.width(7.dp))
                Text(if (persistentlyGrouped) "取消组合" else "组合")
            }
            FilledTonalButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.ContentCopy, null)
                Spacer(Modifier.width(7.dp))
                Text("复制整组")
            }
        }
        OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Text("删除所选元素", color = MaterialTheme.colorScheme.error)
        }
    }
}
