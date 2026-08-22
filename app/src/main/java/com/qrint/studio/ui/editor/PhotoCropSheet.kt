package com.qrint.studio.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun fittedPhotoRect(viewport: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    if (viewport.width <= 0 || viewport.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
    val imageRatio = imageWidth.toFloat() / imageHeight
    val viewportRatio = viewport.width.toFloat() / viewport.height
    val width: Float
    val height: Float
    if (imageRatio >= viewportRatio) {
        width = viewport.width.toFloat()
        height = width / imageRatio
    } else {
        height = viewport.height.toFloat()
        width = height * imageRatio
    }
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoCropSheet(
    uri: Uri,
    processing: Boolean,
    onUseAll: () -> Unit,
    onUseCrop: (FreehandPhotoSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var drawing by remember(uri) { mutableStateOf(false) }
    val points = remember(uri) { mutableStateListOf<NormalizedPhotoPoint>() }
    val selection by remember { derivedStateOf { finalizeFreehandPhotoSelection(points.toList()) } }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { CapturedPhotoCropper.loadPreview(context, uri) }
        loadFailed = bitmap == null
    }
    DisposableEffect(bitmap) {
        val owned = bitmap
        onDispose { owned?.takeIf { !it.isRecycled }?.recycle() }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!processing) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("手绘圈选照片内容", style = MaterialTheme.typography.headlineSmall)
            Text(
                "按住照片并沿要打印内容的边缘画一圈，松手后自动闭合；圈外内容会变成白色，也可以直接使用整张照片。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().height(430.dp).background(Color.Black).onSizeChanged { viewport = it },
                contentAlignment = Alignment.Center,
            ) {
                val currentBitmap = bitmap
                if (currentBitmap == null) {
                    if (loadFailed) Text("照片读取失败", color = Color.White)
                    else CircularProgressIndicator()
                } else {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    val frame = fittedPhotoRect(viewport, currentBitmap.width, currentBitmap.height)
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(frame, processing) {
                            if (frame.width <= 0f || frame.height <= 0f || processing) return@pointerInput
                            fun normalized(point: Offset): NormalizedPhotoPoint = NormalizedPhotoPoint(
                                x = ((point.x - frame.left) / frame.width).coerceIn(0f, 1f),
                                y = ((point.y - frame.top) / frame.height).coerceIn(0f, 1f),
                            )
                            detectDragGestures(
                                onDragStart = { point ->
                                    if (frame.contains(point)) {
                                        points.clear()
                                        points.add(normalized(point))
                                        drawing = true
                                    }
                                },
                                onDragCancel = { drawing = false },
                                onDragEnd = {
                                    drawing = false
                                    val finalized = finalizeFreehandPhotoSelection(points.toList())
                                    points.clear()
                                    points.addAll(finalized.points)
                                },
                                onDrag = { change, _ ->
                                    if (!drawing) return@detectDragGestures
                                    change.consume()
                                    val point = normalized(change.position)
                                    if (shouldAppendFreehandPhotoPoint(points, point)) points.add(point)
                                },
                            )
                        },
                    ) {
                        if (frame.width <= 0f || frame.height <= 0f) return@Canvas
                        drawRect(
                            color = Color.White.copy(alpha = 0.28f),
                            topLeft = frame.topLeft,
                            size = frame.size,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                        if (points.isEmpty()) return@Canvas
                        val path = Path().apply {
                            val first = points.first()
                            moveTo(frame.left + first.x * frame.width, frame.top + first.y * frame.height)
                            points.drop(1).forEach { point ->
                                lineTo(frame.left + point.x * frame.width, frame.top + point.y * frame.height)
                            }
                            if (!drawing && selection.isUsable) close()
                        }
                        if (!drawing && selection.isUsable) {
                            drawPath(path, Color(0xFF3265FF).copy(alpha = 0.16f), style = Fill)
                        }
                        drawPath(
                            path = path,
                            color = Color(0xFF3265FF),
                            style = Stroke(
                                width = 3.dp.toPx(),
                                pathEffect = if (drawing) null else PathEffect.dashPathEffect(floatArrayOf(14f, 9f)),
                            ),
                        )
                        val start = points.first()
                        drawCircle(
                            color = Color.White,
                            radius = 7.dp.toPx(),
                            center = Offset(frame.left + start.x * frame.width, frame.top + start.y * frame.height),
                        )
                        drawCircle(
                            color = Color(0xFF3265FF),
                            radius = 5.dp.toPx(),
                            center = Offset(frame.left + start.x * frame.width, frame.top + start.y * frame.height),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { points.clear() },
                    enabled = points.isNotEmpty() && !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("重新圈选") }
                OutlinedButton(
                    onClick = onUseAll,
                    enabled = bitmap != null && !processing,
                    modifier = Modifier.weight(1f),
                ) { Text("使用整张") }
            }
            Button(
                onClick = { onUseCrop(selection) },
                enabled = bitmap != null && selection.isUsable && !drawing && !processing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (processing) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(22.dp))
                else Text("使用手绘圈选区域")
            }
        }
    }
}
