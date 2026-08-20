package com.qrint.studio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.placement

/** Shared physical head/paper map used by calibration and final preview. */
@Composable
fun PaperPlacementBar(
    paper: PaperSettings,
    modifier: Modifier = Modifier,
    showCalibrationOffset: Boolean = true,
) {
    val placement = paper.placement()
    val headColor = MaterialTheme.colorScheme.outline
    val paperColor = MaterialTheme.colorScheme.primary
    val offsetColor = MaterialTheme.colorScheme.tertiary
    val side = when (paper.horizontalAnchor) {
        HorizontalAnchor.LEFT -> "靠左"
        HorizontalAnchor.CENTER -> "居中"
        HorizontalAnchor.RIGHT -> "靠右"
    }
    Canvas(modifier.semantics {
        contentDescription =
            "从出纸方向看，纸张$side，覆盖打印头第 ${placement.paperStartDot} 到 ${placement.paperEndDotExclusive} 点"
    }) {
        val top = size.height * 0.2f
        val trackHeight = size.height * 0.54f
        drawRoundRect(
            color = headColor,
            topLeft = Offset(1f, top),
            size = Size((size.width - 2f).coerceAtLeast(1f), trackHeight),
            cornerRadius = CornerRadius(18f),
            style = Stroke(width = 3f),
        )
        val left = size.width * placement.paperStartDot / placement.headDots
        val right = size.width * placement.paperEndDotExclusive / placement.headDots
        drawRoundRect(
            color = paperColor.copy(alpha = 0.24f),
            topLeft = Offset(left, top + 4f),
            size = Size((right - left).coerceAtLeast(2f), (trackHeight - 8f).coerceAtLeast(1f)),
            cornerRadius = CornerRadius(14f),
        )
        drawLine(
            paperColor,
            Offset(left, top),
            Offset(left, top + trackHeight),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
        drawLine(
            paperColor,
            Offset(right, top),
            Offset(right, top + trackHeight),
            strokeWidth = 5f,
            cap = StrokeCap.Round,
        )
        if (showCalibrationOffset) {
            val shift = size.width * placement.calibrationOffsetDots / placement.headDots
            val center = (left + right) / 2f + shift
            drawLine(
                offsetColor,
                Offset(center, top - 9f),
                Offset(center, top + trackHeight + 9f),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
    }
}
