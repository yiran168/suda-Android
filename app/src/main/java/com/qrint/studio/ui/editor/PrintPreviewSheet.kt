package com.qrint.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.LocalStore
import com.qrint.studio.data.resolveVariables
import com.qrint.studio.audio.PrintSoundEngine
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperViewport
import com.qrint.studio.model.PrintHistoryItem
import com.qrint.studio.model.printReadiness
import com.qrint.studio.model.toJson
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.printer.MAX_PRINT_COPIES
import com.qrint.studio.printer.MIN_PRINT_COPIES
import com.qrint.studio.printer.PrintResult
import com.qrint.studio.render.RenderedLabel
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.ui.components.PaperPlacementBar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintPreviewSheet(
    document: LabelDocument,
    rendered: RenderedLabel,
    batchSourceDocument: LabelDocument? = null,
    batchRows: List<Map<String, String>> = emptyList(),
    batchDocuments: List<LabelDocument> = emptyList(),
    printer: BluetoothPrinterManager,
    store: LocalStore,
    onConnect: () -> Unit,
    onAlignmentChange: (HorizontalAnchor) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val printerState by printer.state.collectAsState()
    val appPreferences by store.appPreferences.collectAsState()
    val scope = rememberCoroutineScope()
    var copies by remember { mutableIntStateOf(1) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var printing by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var resultSuccess by remember { mutableStateOf<Boolean?>(null) }
    var stopAfterCurrent by remember { mutableStateOf(false) }
    var skipFailedRecords by remember { mutableStateOf(false) }
    val printDocuments = remember(document, batchSourceDocument, batchRows, batchDocuments) {
        when {
            batchDocuments.isNotEmpty() -> batchDocuments
            batchSourceDocument != null && batchRows.isNotEmpty() ->
                batchRows.map { values -> batchSourceDocument.resolveVariables(values) }
            else -> listOf(document)
        }
    }
    val printBlocker = remember(printDocuments) {
        printDocuments.mapIndexedNotNull { index, target ->
            target.printReadiness().takeUnless { it.ready }?.let { index to it.message }
        }.firstOrNull()
    }
    val printReady = printBlocker == null
    val batchTaskKey = remember(printDocuments) {
        printDocuments.joinToString("|") { "${it.id}:${it.updatedAt}:${it.elements.size}" }
    }
    val targetUnit = if (batchDocuments.size > 1) "页" else if (batchRows.isNotEmpty()) "条" else "项"
    var resumeCursor by remember(batchTaskKey) { mutableStateOf<BatchResumeCursor?>(null) }
    var completedAcrossRuns by remember(batchTaskKey) { mutableIntStateOf(0) }
    var activeRecord by remember { mutableIntStateOf(0) }
    var activeCopy by remember { mutableIntStateOf(0) }
    val transformState = rememberTransformableState { zoomChange, _, _ -> zoom = (zoom * zoomChange).coerceIn(0.75f, 4f) }
    val paperViewport = PaperViewport.from(document.paper)
    val previewIsCurrent = remember(document, rendered.documentHash) {
        rendered.documentHash == document.normalized().toJson().toString().hashCode()
    }
    val resumeDescription = resumeCursor?.let { cursor ->
        buildString {
            when {
                batchDocuments.size > 1 -> append("第 ${cursor.recordIndex + 1} 页、")
                batchRows.isNotEmpty() -> append("第 ${cursor.recordIndex + 1} 条、")
            }
            append("第 ${cursor.copyIndex + 1} 份")
            if (cursor.rowOffset > 0) append("的点阵第 ${cursor.rowOffset + 1} 行")
            append("继续；已完成 $completedAcrossRuns 张")
        }
    }

    ModalBottomSheet(
        // A row-level checkpoint is valuable only while its task stays alive. Force an explicit
        // "continue" or "end" choice instead of letting a swipe accidentally discard the cursor.
        onDismissRequest = { if (!printing && resumeCursor == null) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("最终打印预览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("此位图将逐点发送给打印头，可双指缩放检查", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                Modifier.fillMaxWidth().height(330.dp).clip(RoundedCornerShape(25.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest).transformable(transformState),
                contentAlignment = Alignment.Center,
            ) {
                if (previewIsCurrent) {
                    PhysicalPaperPreview(
                        document = document,
                        rendered = rendered,
                        modifier = Modifier.fillMaxWidth().padding(20.dp).graphicsLayer(scaleX = zoom, scaleY = zoom),
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 2.dp)
                        Text("正在同步纸张位置与点阵", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("有效点阵", "${paperViewport.sourceWidthDots}×${rendered.heightDots}", Modifier.weight(1f))
                Metric("纸张尺寸", "${pretty(document.paper.contentWidthMm)}×${pretty(document.paper.dotsToMm(rendered.heightDots))} mm", Modifier.weight(1f))
                Metric("模式", if (document.paper.mode.name == "LABEL") "标签纸" else "连续纸", Modifier.weight(1f))
            }
            if (document.paper.requiresNarrowLoading()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("确认窄纸装入位置", fontWeight = FontWeight.Bold)
                        Text("选择必须与纸卷在机器内的实际位置一致，空白打印头区域不会加热。", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(HorizontalAnchor.LEFT, HorizontalAnchor.RIGHT).forEach { anchor ->
                                FilterChip(
                                    selected = document.paper.horizontalAnchor == anchor,
                                    onClick = { onAlignmentChange(anchor) },
                                    label = { Text(if (anchor == HorizontalAnchor.LEFT) "纸张靠左" else "纸张靠右") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        PaperPlacementBar(
                            paper = document.paper,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            showCalibrationOffset = false,
                        )
                        Text(
                            "从出纸方向看：蓝色区域就是实际纸张覆盖的位置。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (rendered.warnings.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.WarningAmber, null)
                        Spacer(Modifier.width(9.dp))
                        Text(rendered.warnings.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            printBlocker?.let { (index, message) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.WarningAmber, null)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            if (printDocuments.size > 1) "第 ${index + 1} 项：$message" else message,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (batchDocuments.size > 1) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("文档分页打印", fontWeight = FontWeight.Bold)
                        Text(
                            "${batchDocuments.size} 页 · 每页 $copies 份 · 共 ${batchDocuments.size * copies} 张",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (printing) {
                            Text(
                                "正在处理第 ${activeRecord + 1}/${batchDocuments.size} 页，第 ${activeCopy + 1}/$copies 份",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else resumeDescription?.let { description ->
                            Text(
                                "可从$description",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (batchSourceDocument != null && batchRows.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("变量数据批量任务", fontWeight = FontWeight.Bold)
                        Text(
                            "${batchRows.size} 条记录 · 每条 $copies 份 · 共 ${batchRows.size * copies} 张",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (printing) {
                            Text(
                                "正在处理第 ${activeRecord + 1}/${batchRows.size} 条，第 ${activeCopy + 1}/$copies 份",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else resumeDescription?.let { description ->
                            Text(
                                "可从$description",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (printDocuments.size == 1 && resumeDescription != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("任务已暂停", fontWeight = FontWeight.Bold)
                        Text(
                            "可从$resumeDescription。可继续剩余内容，或结束并保留已完成记录。",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                batchDocuments.size > 1 -> "每页打印份数"
                                batchRows.isNotEmpty() -> "每条记录份数"
                                else -> "打印份数"
                            },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$copies 份", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = copies.toFloat(),
                        onValueChange = {
                            copies = it.roundToInt()
                            resumeCursor = null
                            completedAcrossRuns = 0
                        },
                        valueRange = MIN_PRINT_COPIES.toFloat()..MAX_PRINT_COPIES.toFloat(),
                        steps = MAX_PRINT_COPIES - MIN_PRINT_COPIES - 1,
                        enabled = !printing,
                    )
                    if (document.elements.any { it.kind == ElementKind.SEQUENCE } && copies > 1) {
                        Text("流水号将按步长逐份递增", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall)
                    }
                    if (printDocuments.size > 1) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("失败时跳过当前$targetUnit", fontWeight = FontWeight.SemiBold)
                                Text("记录失败原因并继续下一$targetUnit；关闭时会停在失败位置以便续打", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = skipFailedRecords,
                                onCheckedChange = { skipFailedRecords = it },
                                enabled = !printing,
                            )
                        }
                    }
                }
            }
            resultMessage?.let {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (resultSuccess == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(it, modifier = Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold) }
            }
            if (!printerState.connected) {
                FilledTonalButton(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(19.dp)) {
                    Icon(Icons.Rounded.Bluetooth, null); Spacer(Modifier.width(8.dp)); Text("先连接打印机")
                }
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            printing = true
                            stopAfterCurrent = false
                            resultMessage = null
                            resultSuccess = null
                            val targets = printDocuments
                            val initialCursor = resumeCursor?.takeIf {
                                it.recordIndex in targets.indices && it.copyIndex in 0 until copies
                            } ?: BatchResumeCursor(0, 0)
                            var nextCursor: BatchResumeCursor? = initialCursor
                            var completed = completedAcrossRuns
                            try {
                                PrintSoundEngine.play(appPreferences.printSound)
                                var failure: String? = null
                                val skippedFailures = mutableListOf<String>()
                                var stopped = false
                                recordLoop@ for (index in initialCursor.recordIndex until targets.size) {
                                    val targetDocument = targets[index]
                                    val firstCopy = if (index == initialCursor.recordIndex) initialCursor.copyIndex else 0
                                    if (stopAfterCurrent) {
                                        stopped = true
                                        break@recordLoop
                                    }
                                    activeRecord = index
                                    activeCopy = firstCopy
                                    val requestedCopies = copies - firstCopy
                                    val printResult = try {
                                        val generated = withContext(Dispatchers.Default) {
                                            LabelRenderer.render(context, targetDocument, firstCopy.toLong())
                                        }
                                        try {
                                            // Submit the remaining copies as one managed batch. The printer layer
                                            // still sends one complete raster session per copy, but gates every
                                            // next session on ACK plus stable motor-idle status so firmware cannot
                                            // swallow alternating labels.
                                            printer.print(
                                                generated,
                                                targetDocument,
                                                requestedCopies,
                                                sequenceStartIndex = firstCopy.toLong(),
                                                initialRowOffset = if (index == initialCursor.recordIndex) {
                                                    initialCursor.rowOffset
                                                } else {
                                                    0
                                                },
                                            )
                                        } finally {
                                            generated.bitmap.takeIf { !it.isRecycled }?.recycle()
                                        }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        PrintResult(false, error.message ?: "渲染或发送失败")
                                    }
                                    val printedCopies = if (printResult.success) {
                                        requestedCopies
                                    } else {
                                        printResult.completedCopies.coerceIn(0, requestedCopies)
                                    }
                                    completed += printedCopies
                                    activeCopy = (firstCopy + printedCopies - 1).coerceAtLeast(firstCopy)
                                    if (!printResult.success) {
                                        val failedCursor = failedBatchCursor(
                                            recordIndex = index,
                                            firstCopy = firstCopy,
                                            completedCopies = printedCopies,
                                            copiesPerRecord = copies,
                                            resumeRowOffset = printResult.resumeRowOffset,
                                        )
                                        if (printResult.cancelled) {
                                            stopped = true
                                            nextCursor = failedCursor
                                            break@recordLoop
                                        }
                                        val failedCopy = failedCursor.copyIndex
                                        val detail = "第 ${index + 1}/${targets.size} $targetUnit、第 ${failedCopy + 1}/$copies 份失败：${printResult.message}"
                                        if (skipFailedRecords && targets.size > 1) {
                                            skippedFailures += detail
                                            nextCursor = nextRecordCursor(index, targets.size)
                                            continue@recordLoop
                                        }
                                        failure = detail
                                        nextCursor = failedCursor
                                        break@recordLoop
                                    }
                                    nextCursor = nextRecordCursor(index, targets.size)
                                }
                                val success = failure == null && !stopped && nextCursor == null && skippedFailures.isEmpty()
                                val completedWithSkips = failure == null && !stopped && nextCursor == null && skippedFailures.isNotEmpty()
                                when {
                                    success -> {
                                        resultMessage = if (targets.size > 1) "批量打印完成，共 $completed 张" else "打印完成"
                                        resultSuccess = true
                                        resumeCursor = null
                                    }
                                    completedWithSkips -> {
                                        resultMessage = "批量任务已结束：完成 $completed 张，跳过 ${skippedFailures.size} 个失败$targetUnit\n" +
                                            skippedFailures.take(3).joinToString("\n") +
                                            if (skippedFailures.size > 3) "\n另有 ${skippedFailures.size - 3} 条失败，完整清单已写入历史" else ""
                                        resultSuccess = false
                                        resumeCursor = null
                                    }
                                    stopped -> {
                                        resumeCursor = nextCursor
                                        resultMessage = "已暂停后续任务，完成 $completed/${targets.size * copies} 张，可选择继续或结束任务"
                                        resultSuccess = false
                                    }
                                    else -> {
                                        resumeCursor = nextCursor
                                        resultMessage = failure ?: "批量任务未完成"
                                        resultSuccess = false
                                    }
                                }
                                completedAcrossRuns = if (success || completedWithSkips) 0 else completed
                                val historyMessage = if (completedWithSkips) {
                                    "完成 $completed 张，跳过 ${skippedFailures.size} 个失败$targetUnit\n${skippedFailures.joinToString("\n")}" 
                                } else resultMessage.orEmpty()
                                store.addHistory(
                                    PrintHistoryItem(
                                        title = when {
                                            batchDocuments.size > 1 -> "${document.title} · ${targets.size} 页"
                                            batchRows.isNotEmpty() -> "${batchSourceDocument?.title ?: document.title} · ${targets.size} 条数据"
                                            else -> document.title
                                        },
                                        copies = completed,
                                        success = success,
                                        message = historyMessage,
                                        document = batchSourceDocument ?: batchDocuments.firstOrNull() ?: document,
                                    ),
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                val message = error.message ?: "打印任务失败"
                                resultMessage = message
                                resultSuccess = false
                                resumeCursor = nextCursor
                                completedAcrossRuns = completed
                                store.addHistory(
                                    PrintHistoryItem(
                                        title = document.title,
                                        copies = completed,
                                        success = false,
                                        message = message,
                                        document = document,
                                    ),
                                )
                            } finally {
                                printing = false
                            }
                        }
                    },
                    enabled = !printing && previewIsCurrent && printReady,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    if (printing || !previewIsCurrent) CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Print, null)
                    Spacer(Modifier.width(9.dp))
                    Text(
                        if (!printReady) "请先补充可打印内容"
                        else if (!previewIsCurrent) "正在更新预览"
                        else if (printing) printerState.progressText.ifBlank { "正在打印" }
                        else if (resumeCursor != null) "继续剩余 ${printDocuments.size * copies - completedAcrossRuns} 张"
                        else if (batchDocuments.size > 1) "确认并打印 ${batchDocuments.size * copies} 张"
                        else if (batchRows.isNotEmpty()) "确认并批量打印 ${batchRows.size * copies} 张"
                        else "确认并打印",
                    )
                }
                if (printing) {
                    OutlinedButton(
                        onClick = {
                            stopAfterCurrent = true
                            printer.requestPauseAfterCurrentCopy()
                        },
                        enabled = !stopAfterCurrent,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        Text(if (stopAfterCurrent) "将在当前张完成后暂停" else "当前张完成后暂停")
                    }
                    OutlinedButton(
                        onClick = {
                            stopAfterCurrent = true
                            printer.requestImmediatePause()
                        },
                        enabled = !stopAfterCurrent,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text("立即暂停并保存点阵断点")
                    }
                } else if (resumeCursor != null) {
                    OutlinedButton(
                        onClick = {
                            resultMessage = "任务已结束，已保留已完成的 $completedAcrossRuns 张记录"
                            resultSuccess = false
                            resumeCursor = null
                            completedAcrossRuns = 0
                            stopAfterCurrent = false
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text("结束任务（不再继续）")
                    }
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(11.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun pretty(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

internal data class BatchResumeCursor(
    val recordIndex: Int,
    val copyIndex: Int,
    val rowOffset: Int = 0,
)

internal fun failedBatchCursor(
    recordIndex: Int,
    firstCopy: Int,
    completedCopies: Int,
    copiesPerRecord: Int,
    resumeRowOffset: Int = 0,
): BatchResumeCursor = BatchResumeCursor(
    recordIndex = recordIndex,
    copyIndex = (firstCopy + completedCopies).coerceIn(0, (copiesPerRecord - 1).coerceAtLeast(0)),
    rowOffset = resumeRowOffset.coerceAtLeast(0),
)

internal fun nextRecordCursor(recordIndex: Int, recordCount: Int): BatchResumeCursor? =
    if (recordIndex + 1 < recordCount) BatchResumeCursor(recordIndex + 1, 0) else null
