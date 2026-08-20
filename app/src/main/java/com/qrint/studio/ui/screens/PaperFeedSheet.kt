package com.qrint.studio.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.printer.PrintJobPolicy
import kotlinx.coroutines.launch

private val feedQuickOptions = listOf(1f, 2f, 3f, 4f, 5f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperFeedSheet(
    paper: PaperSettings,
    printer: BluetoothPrinterManager,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val printerState by printer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("5") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val distance = input.replace(',', '.').toFloatOrNull()
    val validDistance = distance?.takeIf { PrintJobPolicy.isManualFeedDistanceValid(it) }
    val rangeText = "${formatFeedMm(PrintJobPolicy.MIN_MANUAL_FEED_MM)}–${formatFeedMm(PrintJobPolicy.MAX_MANUAL_FEED_MM)}"

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Column {
                Text("单独走纸", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "不打印内容，只让机器按设定的毫米数向前送纸。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.large,
            ) {
                Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.size(52.dp),
                        ) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.ArrowDownward, "走纸", tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text("走纸距离", fontWeight = FontWeight.Bold)
                            Text(
                                validDistance?.let { "${formatFeedMm(it)} mm · ${paper.mmToDots(it)} 点 · ${paper.dpi} dpi" }
                                    ?: "可输入 $rangeText mm",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { raw ->
                            input = sanitizeFeedInput(raw)
                            result = null
                        },
                        label = { Text("距离 mm") },
                        supportingText = {
                            Text(if (input.isNotBlank() && validDistance == null) "请输入 $rangeText mm 的有效数值" else "支持 0.1 mm 小数精度")
                        },
                        isError = input.isNotBlank() && validDistance == null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("快捷选项", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        feedQuickOptions.forEach { option ->
                            FilterChip(
                                selected = validDistance == option,
                                onClick = {
                                    input = formatFeedMm(option)
                                    result = null
                                },
                                label = { Text("${option.toInt()} mm") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            result?.let { (success, message) ->
                Surface(
                    color = if (success) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (success) Icons.Rounded.CheckCircle else Icons.Rounded.ArrowDownward, null)
                        Spacer(Modifier.size(9.dp))
                        Text(message, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (!printerState.connected) {
                FilledTonalButton(
                    onClick = { onDismiss(); onOpenSettings() },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(Icons.Rounded.Bluetooth, null)
                    Spacer(Modifier.size(8.dp))
                    Text("先连接打印机")
                }
            } else {
                Button(
                    onClick = {
                        val safeDistance = validDistance ?: return@Button
                        scope.launch {
                            busy = true
                            result = null
                            val sent = printer.feedPaper(safeDistance, paper)
                            result = sent.success to sent.message
                            busy = false
                        }
                    },
                    enabled = !busy && validDistance != null,
                    modifier = Modifier.fillMaxWidth().height(58.dp),
                    shape = MaterialTheme.shapes.large,
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.ArrowDownward, null)
                    Spacer(Modifier.size(9.dp))
                    Text(if (busy) "正在走纸" else "开始走纸")
                }
            }
        }
    }
}

internal fun sanitizeFeedInput(raw: String): String {
    val normalized = raw.replace(',', '.')
    val filtered = buildString {
        var decimalSeen = false
        normalized.forEach { character ->
            when {
                character.isDigit() -> append(character)
                character == '.' && !decimalSeen -> {
                    decimalSeen = true
                    if (isEmpty()) append('0')
                    append(character)
                }
            }
        }
    }
    return filtered.take(6)
}

private fun formatFeedMm(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
