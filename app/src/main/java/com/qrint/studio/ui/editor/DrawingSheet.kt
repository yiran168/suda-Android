package com.qrint.studio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawingSheet(onSave: (List<Float>) -> Unit, onDismiss: () -> Unit) {
    val points = remember { mutableStateListOf<Float>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("手绘 / 签名", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("在白色区域书写，笔迹会以矢量点保存并随元素缩放", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Canvas(
                Modifier.fillMaxWidth().height(260.dp).background(Color.White, RoundedCornerShape(22.dp))
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(canvasSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                points.add(-1f); points.add(-1f)
                                addPoint(points, offset, canvasSize)
                            },
                            onDrag = { change, _ -> change.consume(); addPoint(points, change.position, canvasSize) },
                        )
                    },
            ) {
                val path = Path()
                var drawing = false
                var index = 0
                while (index + 1 < points.size) {
                    val x = points[index]
                    val y = points[index + 1]
                    if (x < 0f || y < 0f) drawing = false
                    else {
                        val px = x * size.width
                        val py = y * size.height
                        if (drawing) path.lineTo(px, py) else { path.moveTo(px, py); drawing = true }
                    }
                    index += 2
                }
                drawPath(path, Color.Black, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { removeLastStroke(points) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, null); Spacer(Modifier.padding(3.dp)); Text("撤销一笔")
                }
                FilledTonalButton(onClick = points::clear, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.DeleteSweep, null); Spacer(Modifier.padding(3.dp)); Text("清空")
                }
            }
            Button(
                onClick = { onSave(points.toList()) },
                enabled = points.any { it >= 0f },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(19.dp),
            ) { Text("插入画布") }
        }
    }
}

private fun addPoint(points: MutableList<Float>, offset: Offset, size: IntSize) {
    if (size.width <= 0 || size.height <= 0) return
    points.add((offset.x / size.width).coerceIn(0f, 1f))
    points.add((offset.y / size.height).coerceIn(0f, 1f))
}

private fun removeLastStroke(points: MutableList<Float>) {
    if (points.isEmpty()) return
    var marker = points.size - 2
    while (marker >= 0) {
        if (points[marker] < 0f && points.getOrNull(marker + 1)?.let { it < 0f } == true) break
        marker -= 2
    }
    if (marker >= 0) repeat(points.size - marker) { points.removeAt(points.lastIndex) } else points.clear()
}
