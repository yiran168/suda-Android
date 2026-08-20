package com.qrint.studio.ui.editor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.MAX_PAPER_WIDTH_MM
import com.qrint.studio.model.MIN_PAPER_WIDTH_MM
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PaperShape
import com.qrint.studio.ui.components.DraftNumberField
import kotlin.math.abs
import kotlin.math.roundToInt

data class LabelSizePreset(val widthMm: Float, val heightMm: Float)

val LABEL_SIZE_PRESETS = listOf(
    LabelSizePreset(30f, 20f),
    LabelSizePreset(40f, 30f),
    LabelSizePreset(50f, 30f),
    LabelSizePreset(50f, 50f),
    LabelSizePreset(57f, 30f),
)

val CONTINUOUS_WIDTH_PRESETS = listOf(57f, 50f, 40f, 30f)

/** Creation-only media controls. The editor deliberately does not repeat these settings. */
@Composable
fun PaperControls(
    paper: PaperSettings,
    onChange: (PaperSettings) -> Unit,
    modifier: Modifier = Modifier,
    continuousWidthOnly: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        Text("纸张类型", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaperModeTile(
                title = "连续纸",
                subtitle = "内容决定长度",
                selected = paper.mode == PaperMode.CONTINUOUS,
                onClick = {
                    val width = CONTINUOUS_WIDTH_PRESETS.minBy { abs(it - paper.contentWidthMm) }
                    onChange(paper.withPaperWidth(width).copy(mode = PaperMode.CONTINUOUS, shape = PaperShape.RECTANGLE))
                },
                modifier = Modifier.weight(1f),
            )
            PaperModeTile(
                title = "标签纸",
                subtitle = "固定宽度和长度",
                selected = paper.mode == PaperMode.LABEL,
                onClick = {
                    val size = LABEL_SIZE_PRESETS.minBy { abs(it.widthMm - paper.contentWidthMm) }
                    onChange(paper.withPaperWidth(size.widthMm).copy(mode = PaperMode.LABEL, labelHeightMm = size.heightMm))
                },
                modifier = Modifier.weight(1f),
            )
        }

        Text(if (paper.mode == PaperMode.LABEL) "常用标签尺寸" else "常用连续纸宽度", style = MaterialTheme.typography.labelLarge)
        if (paper.mode == PaperMode.LABEL) {
            PresetRows(LABEL_SIZE_PRESETS) { size ->
                val selected = abs(paper.contentWidthMm - size.widthMm) < 0.1f &&
                    abs(paper.labelHeightMm - size.heightMm) < 0.1f
                PresetChip(
                    label = "${size.widthMm.toInt()} × ${size.heightMm.toInt()} mm",
                    selected = selected,
                    onClick = {
                        onChange(
                            paper.withPaperWidth(size.widthMm).copy(labelHeightMm = size.heightMm),
                        )
                    },
                )
            }
        } else {
            PresetRows(CONTINUOUS_WIDTH_PRESETS) { width ->
                PresetChip(
                    label = "${width.toInt()} mm",
                    selected = abs(paper.contentWidthMm - width) < 0.1f,
                    onClick = { onChange(paper.withPaperWidth(width)) },
                )
            }
        }

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("无级纸宽", style = MaterialTheme.typography.labelLarge)
                Text("${pretty(paper.contentWidthMm)} mm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Slider(
                value = paper.contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
                onValueChange = { onChange(paper.withPaperWidth((it * 10f).roundToInt() / 10f)) },
                valueRange = MIN_PAPER_WIDTH_MM..MAX_PAPER_WIDTH_MM,
            )
            Text(
                "10.0–57.0 mm 连续调节，精度 0.1 mm；上方尺寸仅是快捷预设。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DecimalField(
                "实际纸张宽度 mm",
                paper.contentWidthMm,
                onValue = { if (it in MIN_PAPER_WIDTH_MM..MAX_PAPER_WIDTH_MM) onChange(paper.withPaperWidth(it)) },
                Modifier.weight(1f),
            )
            if (paper.mode == PaperMode.LABEL) {
                DecimalField(
                    "标签长度 mm",
                    paper.labelHeightMm,
                    onValue = { if (it in 5f..1000f) onChange(paper.copy(labelHeightMm = it)) },
                    Modifier.weight(1f),
                )
            } else if (!continuousWidthOnly) {
                DecimalField(
                    "尾部走纸 mm",
                    paper.tailFeedMm,
                    onValue = { if (it in 0f..50f) onChange(paper.copy(tailFeedMm = it)) },
                    Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }

        if (paper.mode == PaperMode.LABEL) {
            Text("标签外形（画布与裁切参考）", style = MaterialTheme.typography.labelLarge)
            EqualChipRow(
                values = PaperShape.entries,
                selected = paper.shape,
                label = { shape ->
                    when (shape) {
                        PaperShape.RECTANGLE -> "直角"
                        PaperShape.ROUNDED -> "圆角"
                        PaperShape.OVAL -> "椭圆"
                    }
                },
                onSelect = { onChange(paper.copy(shape = it)) },
            )
            DecimalField("标签间隙 / 额外走纸 mm", paper.labelGapMm, {
                if (it in 0f..30f) onChange(paper.copy(labelGapMm = it))
            })
        }

        if (paper.requiresNarrowLoading()) {
            Text("窄纸装入位置", style = MaterialTheme.typography.labelLarge)
            Text(
                "请选择纸卷在打印头下实际靠左还是靠右；应用只发送纸面覆盖区域内的有效内容。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EqualChipRow(
                values = listOf(HorizontalAnchor.LEFT, HorizontalAnchor.RIGHT),
                selected = paper.horizontalAnchor,
                label = { anchor -> if (anchor == HorizontalAnchor.LEFT) "纸张靠左" else "纸张靠右" },
                onSelect = { onChange(paper.copy(horizontalAnchor = it)) },
            )
        }
    }
}

@Composable
private fun PaperModeTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun <T> PresetRows(values: List<T>, chip: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        values.chunked(2).forEach { rowValues ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                rowValues.forEach { value ->
                    Row(Modifier.weight(1f)) { chip(value) }
                }
                if (rowValues.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun <T> EqualChipRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label(value), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DecimalField(
    label: String,
    value: Float,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    DraftNumberField(
        label = label,
        value = pretty(value),
        onDraftChange = { draft -> draft.toFloatOrNull()?.let(onValue) },
        modifier = modifier,
        inputTransform = ::signedDecimalDraft,
    )
}

private fun signedDecimalDraft(value: String): String = buildString {
    var decimalSeen = false
    value.take(9).forEachIndexed { index, character ->
        when {
            character.isDigit() -> append(character)
            character == '-' && index == 0 -> append(character)
            (character == '.' || character == ',') && !decimalSeen -> {
                append('.')
                decimalSeen = true
            }
        }
    }
}

private fun pretty(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

private fun PaperSettings.withPaperWidth(widthMm: Float): PaperSettings {
    val safeWidth = widthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM)
    val needsSide = safeWidth < com.qrint.studio.model.NARROW_LOADING_THRESHOLD_MM
    return copy(
        mediaWidthMm = safeWidth,
        contentWidthMm = safeWidth,
        horizontalAnchor = if (needsSide && horizontalAnchor == HorizontalAnchor.CENTER) HorizontalAnchor.LEFT else horizontalAnchor,
    )
}
