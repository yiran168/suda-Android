package com.qrint.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PaperShape
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.MAX_PAPER_WIDTH_MM
import com.qrint.studio.model.MIN_PAPER_WIDTH_MM
import com.qrint.studio.ui.NoteStyle
import com.qrint.studio.ui.QuickCreateKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateLabelSheet(
    kind: QuickCreateKind,
    defaults: PaperSettings,
    onDismiss: () -> Unit,
    onCreate: (title: String, paper: PaperSettings, noteStyle: NoteStyle) -> Unit,
) {
    var title by remember(kind) { mutableStateOf(kind.title) }
    var noteStyle by remember { mutableStateOf(NoteStyle.RULED) }
    var paper by remember(kind, defaults) { mutableStateOf(defaultPaper(kind, defaults)) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 760.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Column {
                Text("新建 · ${kind.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(kind.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PaperMiniPreview(paper)
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(40) },
                label = { Text(if (paper.mode == PaperMode.LABEL) "标签名称" else "打印任务名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (kind == QuickCreateKind.NOTE) {
                Text("便签版式", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    NoteStyle.entries.forEach { style ->
                        FilterChip(selected = noteStyle == style, onClick = { noteStyle = style }, label = { Text(style.title) })
                    }
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                PaperControls(paper = paper, onChange = { paper = it }, modifier = Modifier.padding(16.dp))
            }
            Text(
                if (paper.mode == PaperMode.CONTINUOUS)
                    "连续纸无需长度传感器：应用会按最下方内容、留白和 DPI 自动计算实际光栅行数。"
                else "标签纸将严格按 ${pretty(paper.contentWidthMm)} × ${pretty(paper.labelHeightMm)} mm 生成画布和打印长度。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val safe = paper.copy(
                        mediaWidthMm = paper.contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
                        contentWidthMm = paper.contentWidthMm.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM),
                        labelHeightMm = paper.labelHeightMm.coerceIn(5f, 1000f),
                    )
                    onCreate(title.ifBlank { kind.title }, safe, noteStyle)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) { Text("创建并进入实时画布", modifier = Modifier.padding(vertical = 7.dp)) }
        }
    }
}

@Composable
private fun PaperMiniPreview(paper: PaperSettings) {
    val ratio = (paper.contentWidthMm / if (paper.mode == PaperMode.LABEL) paper.labelHeightMm else 28f).coerceIn(1.1f, 4.5f)
    val shape = when (paper.shape) {
        PaperShape.RECTANGLE -> RoundedCornerShape(3.dp)
        PaperShape.ROUNDED -> RoundedCornerShape(15.dp)
        PaperShape.OVAL -> CircleShape
    }
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.82f).aspectRatio(ratio),
            shape = shape,
            color = androidx.compose.ui.graphics.Color.White,
            shadowElevation = 2.dp,
        ) {}
    }
}

private fun defaultPaper(kind: QuickCreateKind, source: PaperSettings): PaperSettings = when (kind) {
    QuickCreateKind.QR -> source.copy(mode = PaperMode.LABEL, shape = PaperShape.ROUNDED, mediaWidthMm = 50f, contentWidthMm = 50f, labelHeightMm = 50f, horizontalAnchor = HorizontalAnchor.LEFT)
    QuickCreateKind.BARCODE -> source.copy(mode = PaperMode.LABEL, shape = PaperShape.RECTANGLE, mediaWidthMm = 50f, contentWidthMm = 50f, labelHeightMm = 30f, horizontalAnchor = HorizontalAnchor.LEFT)
    QuickCreateKind.CANVAS -> source.copy(mode = PaperMode.LABEL, shape = PaperShape.ROUNDED, mediaWidthMm = 50f, contentWidthMm = 50f, labelHeightMm = 50f, horizontalAnchor = HorizontalAnchor.LEFT)
    QuickCreateKind.NOTE -> source.copy(mode = PaperMode.CONTINUOUS, shape = PaperShape.RECTANGLE, mediaWidthMm = 57f, contentWidthMm = 57f, horizontalAnchor = HorizontalAnchor.LEFT)
    else -> source.copy(mode = PaperMode.CONTINUOUS, shape = PaperShape.RECTANGLE, mediaWidthMm = 57f, contentWidthMm = 57f, horizontalAnchor = HorizontalAnchor.LEFT)
}

private fun pretty(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
