package com.qrint.studio.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PostAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.OcrQualityStore
import com.qrint.studio.model.PaperMode
import com.qrint.studio.render.OfflineTextScan
import com.qrint.studio.render.textAccuracy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrImportSheet(
    scan: OfflineTextScan,
    paperMode: PaperMode,
    qualityStore: OcrQualityStore,
    onValidated: (original: OfflineTextScan, corrected: OfflineTextScan) -> Unit,
    onReplace: (OfflineTextScan) -> Unit,
    onAppend: (OfflineTextScan) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(scan.sourceUri) { mutableStateListOf<Int>().apply { addAll(scan.lines.indices) } }
    val edited = remember(scan.sourceUri) { mutableStateListOf<String>().apply { addAll(scan.lines.map { it.text }) } }
    var validated by remember(scan.sourceUri) { mutableStateOf(false) }
    val stats by qualityStore.stats.collectAsState()
    val corrected = scan.copy(lines = scan.lines.mapIndexed { index, line -> line.copy(text = edited[index]) })
    val filtered = corrected.copy(lines = corrected.lines.filterIndexed { index, _ -> index in selected })
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Column {
                Text("离线扫描成可编辑草稿", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "PP-OCRv6 Small 已完整内置；中英文识别结果按原图坐标变成独立文字层。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                scan.meanConfidence?.let { confidence ->
                    Text(
                        "模型置信度 ${"%.1f".format(confidence * 100f)}% · 完全离线且不上传图片",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("识别到 ${scan.lines.size} 行", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    if (selected.size == scan.lines.size) selected.clear()
                    else {
                        selected.clear()
                        selected.addAll(scan.lines.indices)
                    }
                }) { Text(if (selected.size == scan.lines.size) "取消全选" else "全选") }
            }
            scan.lines.forEachIndexed { index, line ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (index in selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = index in selected,
                            onCheckedChange = { checked -> if (checked) selected.add(index) else selected.remove(index) },
                        )
                        OutlinedTextField(
                            value = edited[index],
                            onValueChange = { edited[index] = it },
                            label = { Text("第 ${index + 1} 行 · ${line.width}×${line.height}") },
                            minLines = 1,
                            maxLines = 4,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (selected.isEmpty()) {
                Text("至少选择一行再导入。", color = MaterialTheme.colorScheme.error)
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("对照原图逐字校对", fontWeight = FontWeight.Bold)
                            Text(
                                "仅打开此开关的样本才计入真实字符准确率，并用于本机纠错学习。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(checked = validated, onCheckedChange = { validated = it })
                    }
                    if (validated) {
                        Text("本次校对后准确率 ${"%.2f".format(textAccuracy(corrected.plainText, scan.plainText) * 100f)}%")
                    }
                    Text(
                        if (stats.validatedSamples == 0L) "累计：尚无带标准答案的校对样本"
                        else "累计：${stats.validatedSamples} 份 / ${stats.characters} 字符 / ${"%.2f".format(stats.accuracy * 100f)}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Button(
                onClick = {
                    if (validated) onValidated(scan, corrected)
                    onReplace(filtered)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Rounded.DocumentScanner, null)
                Spacer(Modifier.width(8.dp))
                Text("替换画布为可编辑文字层")
            }
            FilledTonalButton(
                onClick = {
                    if (validated) onValidated(scan, corrected)
                    onAppend(filtered)
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Rounded.PostAdd, null)
                Spacer(Modifier.width(8.dp))
                Text(if (paperMode == PaperMode.CONTINUOUS) "追加到连续纸末尾" else "追加并自动适配标签高度")
            }
            Text(
                "导入后每一行都能单独拖动、缩放、改字体和内容；原始照片只用于本次识别，不会叠加到打印层。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
