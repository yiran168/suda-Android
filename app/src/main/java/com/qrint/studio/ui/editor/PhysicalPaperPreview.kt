package com.qrint.studio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperShape
import com.qrint.studio.model.PaperViewport
import com.qrint.studio.render.RenderedLabel
import kotlin.math.min
import kotlin.math.roundToInt

/** Fits a real-size paper viewport without stretching the print bitmap. */
@Composable
fun PhysicalPaperPreview(
    document: LabelDocument,
    rendered: RenderedLabel,
    modifier: Modifier = Modifier,
) {
    val viewport = PaperViewport.from(document.paper)
    val density = LocalDensity.current
    val paperShape = when (document.paper.shape) {
        PaperShape.RECTANGLE -> RoundedCornerShape(3.dp)
        PaperShape.ROUNDED -> RoundedCornerShape(14.dp)
        PaperShape.OVAL -> CircleShape
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scale = min(
            constraints.maxWidth.toFloat() / viewport.paperWidthDots,
            constraints.maxHeight.toFloat() / rendered.heightDots.coerceAtLeast(1),
        ).coerceAtLeast(0.01f)
        val widthPx = (viewport.paperWidthDots * scale).roundToInt().coerceAtLeast(1)
        val heightPx = (rendered.heightDots * scale).roundToInt().coerceAtLeast(1)
        val widthDp = with(density) { widthPx.toDp() }
        val heightDp = with(density) { heightPx.toDp() }
        val image = rendered.bitmap.asImageBitmap()
        Canvas(
            Modifier.size(widthDp, heightDp).clip(paperShape).background(Color.White),
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset(viewport.sourceStartX, 0),
                srcSize = IntSize(viewport.sourceWidthDots, rendered.heightDots),
                dstOffset = IntOffset((viewport.destinationStartX * scale).roundToInt(), 0),
                dstSize = IntSize(
                    (viewport.sourceWidthDots * scale).roundToInt().coerceAtLeast(1),
                    heightPx,
                ),
                filterQuality = FilterQuality.None,
            )
        }
    }
}
