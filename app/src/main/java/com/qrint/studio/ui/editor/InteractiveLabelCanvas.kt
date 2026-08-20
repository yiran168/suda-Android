package com.qrint.studio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.PaperShape
import com.qrint.studio.model.PaperViewport
import com.qrint.studio.render.RenderedLabel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun InteractiveLabelCanvas(
    document: LabelDocument,
    rendered: RenderedLabel,
    selectedId: String?,
    selectedIds: Set<String>,
    snapGuides: SnapGuides,
    onSelect: (String?) -> Unit,
    onToggleSelection: (String) -> Unit,
    onDoubleTap: (String) -> Unit,
    onTransformStart: (String) -> Unit,
    onTransform: (dxDots: Float, dyDots: Float, zoom: Float) -> Unit,
    onResize: (dxDots: Float, dyDots: Float, edges: ResizeEdges) -> Unit,
    onTransformEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        // The editor fills the available width with exactly the dot columns that can be printed.
        // Final physical-paper preview still uses PaperViewport.from() and shows real side margins.
        val viewport = PaperViewport.forEditor(document.paper)
        val printToScreen = printDotPreviewScale(constraints.maxWidth, viewport.paperWidthDots)
        val displayWidth = with(LocalDensity.current) {
            (viewport.paperWidthDots * printToScreen).toDp()
        }
        val bitmapHeight = rendered.bitmap.height.coerceAtLeast(1)
        val yOffset = document.paper.mmToDots(document.paper.offsetYmm)
        val xOffset = document.paper.horizontalCalibrationDots()
        val selected = document.elements.firstOrNull { it.id == selectedId }
        val selectedElements = document.elements.filter { it.id in selectedIds }
        val latestElements = rememberUpdatedState(document.elements)
        val latestSelected = rememberUpdatedState(selected)
        val latestSelectedIds = rememberUpdatedState(selectedIds)
        val latestSelectedElements = rememberUpdatedState(selectedElements)
        val selectionColor = MaterialTheme.colorScheme.primary
        val paperShape = when (document.paper.shape) {
            PaperShape.RECTANGLE -> RoundedCornerShape(4.dp)
            PaperShape.ROUNDED -> RoundedCornerShape(18.dp)
            PaperShape.OVAL -> CircleShape
        }
        Box(
            Modifier.width(displayWidth)
                .aspectRatio(viewport.paperWidthDots.toFloat() / bitmapHeight)
                .clip(paperShape)
                .background(Color.White)
                .pointerInput(printToScreen, yOffset, xOffset, viewport) {
                    detectTapGestures(
                        onTap = { point ->
                            val x = viewport.paperToHeadX(point.x / printToScreen) - xOffset
                            val y = point.y / printToScreen - yOffset
                            onSelect(hitTest(latestElements.value, x, y)?.id)
                        },
                        onLongPress = { point ->
                            val x = viewport.paperToHeadX(point.x / printToScreen) - xOffset
                            val y = point.y / printToScreen - yOffset
                            hitTest(latestElements.value, x, y)?.let { onToggleSelection(it.id) }
                        },
                        onDoubleTap = { point ->
                            val x = viewport.paperToHeadX(point.x / printToScreen) - xOffset
                            val y = point.y / printToScreen - yOffset
                            hitTest(latestElements.value, x, y)?.let { onSelect(it.id); onDoubleTap(it.id) }
                        },
                    )
                }
                .pointerInput(printToScreen, yOffset, xOffset, viewport, selectedId, selectedIds) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val x = viewport.paperToHeadX(down.position.x / printToScreen) - xOffset
                        val y = down.position.y / printToScreen - yOffset
                        val handleRadiusDots = 22.dp.toPx() / printToScreen
                        val resizeEdges = latestSelectedElements.value
                            .filterNot { it.locked }
                            .takeIf { it.isNotEmpty() }
                            ?.let { resizeHandleAt(it, x, y, handleRadiusDots) }
                        val current = latestSelected.value?.takeIf { resizeEdges?.active == true }
                            ?: hitTest(latestElements.value, x, y)
                            ?: return@awaitEachGesture
                        if (current.locked) return@awaitEachGesture
                        if (current.id !in latestSelectedIds.value) onSelect(current.id)
                        var transformed = false
                        try {
                            var pointersDown = true
                            while (pointersDown) {
                                val event = awaitPointerEvent()
                                if (resizeEdges?.active == true) {
                                    val tracked = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
                                    val delta = tracked?.positionChange() ?: Offset.Zero
                                    if (delta.getDistance() > 0.01f) {
                                        if (!transformed) {
                                            onTransformStart(current.id)
                                            transformed = true
                                        }
                                        val rawDelta = Offset(delta.x / printToScreen, delta.y / printToScreen)
                                        val localDelta = latestSelectedElements.value.singleOrNull()?.let { element ->
                                            rotateVector(rawDelta, -element.rotation)
                                        } ?: rawDelta
                                        onResize(localDelta.x, localDelta.y, resizeEdges)
                                        tracked?.consume()
                                    }
                                } else {
                                    val pan = event.calculatePan()
                                    val zoom = event.calculateZoom()
                                    if (pan.getDistance() > 0.01f || abs(zoom - 1f) > 0.001f) {
                                        if (!transformed) {
                                            onTransformStart(current.id)
                                            transformed = true
                                        }
                                        onTransform(
                                            pan.x / printToScreen,
                                            pan.y / printToScreen,
                                            zoom.coerceIn(0.75f, 1.33f),
                                        )
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                pointersDown = event.changes.any { it.pressed }
                            }
                        } finally {
                            if (transformed) onTransformEnd()
                        }
                    }
                },
        ) {
            if (rendered.bitmap.isRecycled || rendered.bitmap.width <= 0 || rendered.bitmap.height <= 0) {
                return@Box
            }
            val image = rendered.bitmap.asImageBitmap()
            Canvas(Modifier.fillMaxSize()) {
                // The document may update one frame before its replacement raster. Clamp the source
                // window to the bitmap actually on screen so a paper/profile switch cannot issue an
                // out-of-bounds drawImage call.
                val sourceStart = viewport.sourceStartX.coerceIn(0, rendered.bitmap.width - 1)
                val sourceWidth = min(viewport.sourceWidthDots, rendered.bitmap.width - sourceStart).coerceAtLeast(1)
                val sourceHeight = min(rendered.heightDots, rendered.bitmap.height).coerceAtLeast(1)
                val destinationWidth = (sourceWidth * printToScreen).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = image,
                    srcOffset = IntOffset(sourceStart, 0),
                    srcSize = IntSize(sourceWidth, sourceHeight),
                    dstOffset = IntOffset((viewport.destinationStartX * printToScreen).roundToInt(), 0),
                    dstSize = IntSize(destinationWidth, size.height.roundToInt().coerceAtLeast(1)),
                    filterQuality = FilterQuality.None,
                )

                snapGuides.verticalDot?.let { guide ->
                    val x = viewport.headToPaperX(guide + xOffset) * printToScreen
                    drawLine(selectionColor.copy(alpha = 0.62f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                snapGuides.horizontalDot?.let { guide ->
                    val y = (guide + yOffset) * printToScreen
                    drawLine(selectionColor.copy(alpha = 0.62f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }

                if (selectedElements.isEmpty()) return@Canvas
                selectedElements.forEach { element ->
                    val left = viewport.headToPaperX(element.x + xOffset.toFloat()) * printToScreen
                    val top = (element.y + yOffset) * printToScreen
                    val elementWidth = element.width * printToScreen
                    val elementHeight = element.height * printToScreen
                    val center = Offset(left + elementWidth / 2f, top + elementHeight / 2f)
                    rotate(element.rotation, center) {
                        drawRect(
                            color = selectionColor.copy(alpha = if (selectedElements.size > 1) 0.62f else 1f),
                            topLeft = Offset(left, top),
                            size = Size(elementWidth, elementHeight),
                            style = Stroke(
                                width = if (selectedElements.size > 1) 1.dp.toPx() else 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())),
                            ),
                        )
                        if (selectedElements.size == 1) {
                            drawSelectionHandles(left, top, elementWidth, elementHeight, selectionColor, 5.dp.toPx())
                        }
                    }
                }
                val group = selectedElements.selectionBounds()
                val left = viewport.headToPaperX(group.left + xOffset) * printToScreen
                val top = (group.top + yOffset) * printToScreen
                val width = group.width * printToScreen
                val height = group.height * printToScreen
                if (selectedElements.size > 1) {
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
                if (selectedElements.size > 1) {
                    drawSelectionHandles(left, top, width, height, selectionColor, 5.dp.toPx())
                }
            }
        }
    }
}

private data class SelectionBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

private fun List<LabelElement>.selectionBounds(): SelectionBounds = SelectionBounds(
    left = flatMap(LabelElement::rotatedCorners).minOf { it.x },
    top = flatMap(LabelElement::rotatedCorners).minOf { it.y },
    right = flatMap(LabelElement::rotatedCorners).maxOf { it.x },
    bottom = flatMap(LabelElement::rotatedCorners).maxOf { it.y },
)

private fun resizeHandleAt(bounds: SelectionBounds, x: Float, y: Float, radius: Float): ResizeEdges? {
    val withinHorizontal = x in (bounds.left - radius)..(bounds.right + radius)
    val withinVertical = y in (bounds.top - radius)..(bounds.bottom + radius)
    if (!withinHorizontal || !withinVertical) return null
    val left = abs(x - bounds.left) <= radius
    val right = abs(x - bounds.right) <= radius
    val top = abs(y - bounds.top) <= radius
    val bottom = abs(y - bounds.bottom) <= radius
    return ResizeEdges(left = left, top = top, right = right, bottom = bottom).takeIf { it.active }
}

private fun resizeHandleAt(elements: List<LabelElement>, x: Float, y: Float, radius: Float): ResizeEdges? {
    val single = elements.singleOrNull()
    if (single != null) {
        val local = rotatePoint(Offset(x, y), single.center(), -single.rotation)
        return resizeHandleAt(
            SelectionBounds(single.x.toFloat(), single.y.toFloat(), single.right().toFloat(), single.bottom().toFloat()),
            local.x,
            local.y,
            radius,
        )
    }
    return resizeHandleAt(elements.selectionBounds(), x, y, radius)
}

private fun hitTest(elements: List<LabelElement>, x: Float, y: Float): LabelElement? =
    elements.asReversed().firstOrNull { element ->
        val local = rotatePoint(Offset(x, y), element.center(), -element.rotation)
        local.x >= element.x && local.x <= element.right() && local.y >= element.y && local.y <= element.bottom()
    }

private fun LabelElement.center() = Offset(x + width / 2f, y + height / 2f)

private fun LabelElement.rotatedCorners(): List<Offset> {
    val center = center()
    return listOf(
        Offset(x.toFloat(), y.toFloat()),
        Offset(right().toFloat(), y.toFloat()),
        Offset(right().toFloat(), bottom().toFloat()),
        Offset(x.toFloat(), bottom().toFloat()),
    ).map { rotatePoint(it, center, rotation) }
}

private fun rotatePoint(point: Offset, center: Offset, degrees: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    val cosine = cos(radians).toFloat()
    val sine = sin(radians).toFloat()
    val dx = point.x - center.x
    val dy = point.y - center.y
    return Offset(center.x + dx * cosine - dy * sine, center.y + dx * sine + dy * cosine)
}

private fun rotateVector(vector: Offset, degrees: Float): Offset =
    rotatePoint(vector, Offset.Zero, degrees)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHandles(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    color: Color,
    radius: Float,
) {
    val handles = listOf(
        Offset(left, top), Offset(left + width / 2f, top), Offset(left + width, top),
        Offset(left, top + height / 2f), Offset(left + width, top + height / 2f),
        Offset(left, top + height), Offset(left + width / 2f, top + height), Offset(left + width, top + height),
    )
    handles.forEach { center ->
        drawCircle(Color.White, radius + 2.dp.toPx(), center)
        drawCircle(color, radius, center)
    }
}
