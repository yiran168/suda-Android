package com.qrint.studio.ui.screens

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.data.LocalStore
import com.qrint.studio.model.HorizontalAnchor
import com.qrint.studio.model.MAX_PAPER_WIDTH_MM
import com.qrint.studio.model.MIN_PAPER_WIDTH_MM
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.createPaperCalibrationDocument
import com.qrint.studio.model.placement
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.render.RenderedLabel
import com.qrint.studio.ui.components.PaperPlacementBar
import com.qrint.studio.ui.editor.PhysicalPaperPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperCalibrationSheet(
    initial: PaperSettings,
    printer: BluetoothPrinterManager,
    store: LocalStore,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val printerState by printer.state.collectAsState()
    var paper by remember(initial) {
        mutableStateOf(initial.withCalibrationWidth(initial.contentWidthMm))
    }
    var widthInput by remember { mutableStateOf(formatMm(paper.contentWidthMm)) }
    var rendered by remember { mutableStateOf<RenderedLabel?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val document = remember(paper) { createPaperCalibrationDocument(paper) }
    val placement = paper.placement()

    LaunchedEffect(document) {
        val next = withContext(Dispatchers.Default) { LabelRenderer.render(context, document) }
        val previous = rendered
        rendered = next
        previous?.bitmap?.takeIf { !it.isRecycled }?.recycle()
    }
    DisposableEffect(Unit) {
        onDispose { rendered?.bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }

    fun updateWidth(value: Float) {
        paper = paper.withCalibrationWidth(value)
        widthInput = formatMm(paper.contentWidthMm)
        message = null
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 34.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("实际纸宽与横向偏移校准", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "支持 10.0–57.0 mm 无级纸宽；所有画布、预览和打印共用 203 dpi 点阵坐标。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("实际装入纸宽", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("${formatMm(paper.contentWidthMm)} mm", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = paper.contentWidthMm,
                        onValueChange = { updateWidth((it * 10f).roundToInt() / 10f) },
                        valueRange = MIN_PAPER_WIDTH_MM..MAX_PAPER_WIDTH_MM,
                    )
                    OutlinedTextField(
                        value = widthInput,
                        onValueChange = { raw ->
                            widthInput = raw.filterIndexed { index, char -> char.isDigit() || (char == '.' && index > 0) }
                            widthInput.toFloatOrNull()?.takeIf { it in MIN_PAPER_WIDTH_MM..MAX_PAPER_WIDTH_MM }?.let { value ->
                                paper = paper.withCalibrationWidth(value)
                                message = null
                            }
                        },
                        label = { Text("纸宽 mm（可输入小数）") },
                        supportingText = { Text("范围 10.0–57.0 mm，滑块精度 0.1 mm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (paper.requiresNarrowLoading()) {
                        Text("纸张在打印头下的实际位置", fontWeight = FontWeight.SemiBold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(HorizontalAnchor.LEFT, HorizontalAnchor.RIGHT).forEach { anchor ->
                                FilterChip(
                                    selected = paper.horizontalAnchor == anchor,
                                    onClick = { paper = paper.copy(horizontalAnchor = anchor); message = null },
                                    label = { Text(if (anchor == HorizontalAnchor.LEFT) "纸张靠左" else "纸张靠右") },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Text(
                            "当前纸张覆盖完整打印头，内容按打印头居中显示。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HeadPaperMap(paper)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("横向微调", fontWeight = FontWeight.Bold)
                            Text("边框被裁掉时，每次调 0.1 mm 后重打", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(shape = RoundedCornerShape(13.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                "%+.1f mm".format(paper.offsetXmm),
                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Slider(
                        value = paper.offsetXmm.coerceIn(-5f, 5f),
                        onValueChange = {
                            paper = paper.copy(offsetXmm = (it * 10f).roundToInt() / 10f)
                            message = null
                        },
                        valueRange = -5f..5f,
                        steps = 99,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { paper = paper.copy(offsetXmm = ((paper.offsetXmm - 0.1f) * 10f).roundToInt() / 10f) },
                            modifier = Modifier.weight(1f),
                        ) { Text("向左 0.1") }
                        FilledTonalButton(
                            onClick = { paper = paper.copy(offsetXmm = 0f) },
                            modifier = Modifier.weight(1f),
                        ) { Text("归零") }
                        FilledTonalButton(
                            onClick = { paper = paper.copy(offsetXmm = ((paper.offsetXmm + 0.1f) * 10f).roundToInt() / 10f) },
                            modifier = Modifier.weight(1f),
                        ) { Text("向右 0.1") }
                    }
                }
            }

            rendered?.let { preview ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("校准样张预览", fontWeight = FontWeight.Bold)
                        Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                            PhysicalPaperPreview(document, preview, Modifier.fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("纸面 ${placement.paperDots} 点", style = MaterialTheme.typography.bodySmall)
                            Text("有效加热点 ${placement.printableDots} 点", style = MaterialTheme.typography.bodySmall)
                            Text("打印头 ${placement.headDots} 点", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            message?.let { value ->
                Surface(
                    color = if (value.contains("成功") || value.contains("已保存")) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(value, Modifier.padding(13.dp), fontWeight = FontWeight.SemiBold)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = {
                        store.savePaper(paper)
                        message = "已保存 ${formatMm(paper.contentWidthMm)} mm 纸宽与 ${"%+.1f".format(paper.offsetXmm)} mm 偏移"
                    },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Icon(Icons.Rounded.CheckCircle, null)
                    Spacer(Modifier.width(7.dp))
                    Text("保存校准")
                }
                if (!printerState.connected) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Icon(Icons.Rounded.Bluetooth, null)
                        Spacer(Modifier.width(7.dp))
                        Text("连接打印机")
                    }
                } else {
                    Button(
                        onClick = {
                            val output = rendered ?: return@Button
                            scope.launch {
                                busy = true
                                message = null
                                val result = printer.print(output, document, 1)
                                message = if (result.success) "校准样张打印成功；检查四边边框，再按 0.1 mm 微调" else result.message
                                busy = false
                            }
                        },
                        enabled = !busy && rendered != null,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Print, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (busy) "打印中" else "打印校准样张")
                    }
                }
            }

            Text(
                "说明：机器无法自动识别纸宽，应用会保留固定打印头宽度的白列来定位；白列不会加热。真正加热的黑点会被裁切在你设置的纸面区域内。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HeadPaperMap(paper: PaperSettings) {
    val placement = paper.placement()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("打印头 / 纸张位置映射", fontWeight = FontWeight.Bold)
            PaperPlacementBar(paper, Modifier.fillMaxWidth().height(86.dp))
            Text(
                "打印头 ${placement.headDots} 点 · 纸张 ${placement.paperDots} 点 · 起点 ${placement.paperStartDot} · 偏移 ${placement.calibrationOffsetDots} 点",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun PaperSettings.withCalibrationWidth(value: Float): PaperSettings {
    val width = ((value.coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM) * 10f).roundToInt() / 10f)
    val narrow = width < com.qrint.studio.model.NARROW_LOADING_THRESHOLD_MM
    return copy(
        mediaWidthMm = width,
        contentWidthMm = width,
        horizontalAnchor = when {
            narrow && horizontalAnchor == HorizontalAnchor.CENTER -> HorizontalAnchor.LEFT
            !narrow -> HorizontalAnchor.CENTER
            else -> horizontalAnchor
        },
    )
}

private fun formatMm(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
