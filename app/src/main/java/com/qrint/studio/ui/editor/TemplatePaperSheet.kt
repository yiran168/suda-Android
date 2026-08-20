package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.fittedToPaper
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperMode

/** Media selection belongs to template entry, so the editor opens with one authoritative canvas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatePaperSheet(
    template: LabelDocument,
    onDismiss: () -> Unit,
    onConfirm: (LabelDocument) -> Unit,
) {
    var paper by remember(template.id) {
        mutableStateOf(
            template.paper.copy(
                mode = PaperMode.LABEL,
                mediaWidthMm = template.paper.contentWidthMm,
                horizontalAnchor = if (template.paper.contentWidthMm < 55f) HorizontalAnchor.LEFT else template.paper.horizontalAnchor,
            ).normalized(),
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 780.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(template.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("先选择实际装入的纸张，再进入画布", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                PaperControls(
                    paper = paper,
                    onChange = { paper = it.normalized() },
                    modifier = Modifier.padding(16.dp),
                    continuousWidthOnly = true,
                )
            }
            Text(
                if (paper.mode == PaperMode.LABEL) {
                    "标签纸将按 ${prettyPaper(paper.contentWidthMm)} × ${prettyPaper(paper.labelHeightMm)} mm 固定输出。"
                } else {
                    "连续纸只需纸宽；长度会按模板最下方内容、留白和 203 dpi 自动计算。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onConfirm(template.fittedToPaper(paper)) },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
            ) { Text("按此纸张进入实时画布", modifier = Modifier.padding(vertical = 7.dp)) }
        }
    }
}

private fun prettyPaper(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
