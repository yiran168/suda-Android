package com.qrint.studio.ui.editor

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    onUseCrop: (CameraScanRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var loadFailed by remember(uri) { mutableStateOf(false) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var region by remember(uri) { mutableStateOf(CameraScanRegion(0.06f, 0.06f, 0.94f, 0.94f)) }
    var activeHandle by remember { mutableStateOf<CameraScanHandle?>(null) }

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
            Text("选择照片打印区域", style = MaterialTheme.typography.headlineSmall)
            Text(
                "拖动蓝框内部可移动，拖动四边或四角可调整范围；也可直接使用整张照片。",
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
                    val handleRadiusPx = with(density) { 7.dp.toPx() }
                    val touchRadiusPx = with(density) { 24.dp.toPx() }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(frame, processing) {
                            if (frame.width <= 0f || frame.height <= 0f || processing) return@pointerInput
                            detectDragGestures(
                                onDragStart = { point ->
                                    val normalizedX = ((point.x - frame.left) / frame.width).coerceIn(0f, 1f)
                                    val normalizedY = ((point.y - frame.top) / frame.height).coerceIn(0f, 1f)
                                    activeHandle = hitCameraScanRegion(
                                        region,
                                        normalizedX,
                                        normalizedY,
                                        touchRadiusPx / frame.width,
                                        touchRadiusPx / frame.height,
                                    )
                                },
                                onDragCancel = { activeHandle = null },
                                onDragEnd = { activeHandle = null },
                                onDrag = { change, dragAmount ->
                                    val handle = activeHandle ?: return@detectDragGestures
                                    change.consume()
                                    region = transformCameraScanRegion(
                                        region,
                                        handle,
                                        dragAmount.x / frame.width,
                                        dragAmount.y / frame.height,
                                        minimumWidth = 0.08f,
                                        minimumHeight = 0.08f,
                                    )
                                },
                            )
                        },
                    ) {
                        if (frame.width <= 0f || frame.height <= 0f) return@Canvas
                        val selected = Rect(
                            frame.left + region.left * frame.width,
                            frame.top + region.top * frame.height,
                            frame.left + region.right * frame.width,
                            frame.top + region.bottom * frame.height,
                        )
                        val shade = Color.Black.copy(alpha = 0.52f)
                        drawRect(shade, Offset(frame.left, frame.top), androidx.compose.ui.geometry.Size(frame.width, selected.top - frame.top))
                        drawRect(shade, Offset(frame.left, selected.bottom), androidx.compose.ui.geometry.Size(frame.width, frame.bottom - selected.bottom))
                        drawRect(shade, Offset(frame.left, selected.top), androidx.compose.ui.geometry.Size(selected.left - frame.left, selected.height))
                        drawRect(shade, Offset(selected.right, selected.top), androidx.compose.ui.geometry.Size(frame.right - selected.right, selected.height))
                        drawRect(
                            color = Color(0xFF3265FF),
                            topLeft = selected.topLeft,
                            size = selected.size,
                            style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                        )
                        val handles = listOf(
                            selected.topLeft,
                            Offset(selected.center.x, selected.top),
                            selected.topRight,
                            Offset(selected.left, selected.center.y),
                            Offset(selected.right, selected.center.y),
                            selected.bottomLeft,
                            Offset(selected.center.x, selected.bottom),
                            selected.bottomRight,
                        )
                        handles.forEach { point ->
                            drawCircle(Color.White, handleRadiusPx + 2.dp.toPx(), point)
                            drawCircle(Color(0xFF3265FF), handleRadiusPx, point)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onUseAll, enabled = bitmap != null && !processing, modifier = Modifier.weight(1f)) {
                    Text("使用整张")
                }
                Button(onClick = { onUseCrop(region) }, enabled = bitmap != null && !processing, modifier = Modifier.weight(1f)) {
                    if (processing) CircularProgressIndicator(strokeWidth = 2.dp)
                    else Text("使用圈选区域")
                }
            }
        }
    }
}
