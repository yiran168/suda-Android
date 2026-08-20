package com.qrint.studio.ui.screens

import com.qrint.studio.ProductIdentity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.ListAlt
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ColorLens
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrint.studio.audio.PrintSoundEngine
import com.qrint.studio.data.LocalStore
import com.qrint.studio.data.MarkdownFileExporter
import com.qrint.studio.data.RuntimeLogEntry
import com.qrint.studio.data.RuntimeLogCategory
import com.qrint.studio.data.RuntimeLogLevel
import com.qrint.studio.data.RuntimeLogStore
import com.qrint.studio.model.AppThemeStyle
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PrintProtocol
import com.qrint.studio.model.PrintSoundPreset
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.ui.AppIdentity
import com.qrint.studio.ui.components.DeviceSheet
import com.qrint.studio.ui.components.DraftNumberField
import com.qrint.studio.ui.components.PageHeader
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    padding: PaddingValues,
    store: LocalStore,
    printer: BluetoothPrinterManager,
    logs: RuntimeLogStore,
) {
    val context = LocalContext.current
    val stored by store.paper.collectAsState()
    val appPreferences by store.appPreferences.collectAsState()
    val autoReconnect by printer.autoReconnectEnabled.collectAsState()
    val printerState by printer.state.collectAsState()
    val ocrStats by store.ocrQuality.stats.collectAsState()
    val scope = rememberCoroutineScope()
    val logEntries by logs.entries.collectAsState()
    var deviceProfile by remember(stored) { mutableStateOf(stored) }
    var showDevices by remember { mutableStateOf(false) }
    var showPaperCalibration by remember { mutableStateOf(false) }
    var detailPage by remember { mutableStateOf<SettingsDetailPage?>(null) }
    var logCategory by remember { mutableStateOf<RuntimeLogCategory?>(null) }
    val logSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            MarkdownFileExporter.write(context, uri, logs.asMarkdown(logCategory)).fold(
                onSuccess = { Toast.makeText(context, "运行日志已保存", Toast.LENGTH_SHORT).show() },
                onFailure = { Toast.makeText(context, "保存失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show() },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().padding(padding).windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()),
    ) {
        PageHeader("personal studio", "偏好与设备", "界面、声音和连接行为均保存在本机")

        SettingsCard("打印机与连接", Icons.Rounded.Bluetooth) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = if (printerState.connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Print, null, tint = MaterialTheme.colorScheme.onPrimary) }
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(if (printerState.connected) printerState.deviceName else "尚未连接打印机", fontWeight = FontWeight.Bold)
                            Text(
                                if (printerState.connected) {
                                    "${printerState.model.ifBlank { "SPP 通道" }} · ${printerState.progressText.ifBlank { "设备已就绪" }}"
                                } else {
                                    printerState.lastError.ifBlank {
                                        printerState.progressText.ifBlank { "点击下方按钮扫描或选择已配对设备" }
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeviceMetric("电量", printerState.batteryPercent?.let { "$it%" } ?: "—", Modifier.weight(1f))
                        DeviceMetric("纸张", if (!printerState.connected) "—" else if (printerState.hardware?.noPaper == true) "缺纸" else "就绪", Modifier.weight(1f))
                        DeviceMetric("机身", if (!printerState.connected) "—" else if (printerState.healthy) "正常" else "检查", Modifier.weight(1f))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动重连上次设备", fontWeight = FontWeight.SemiBold)
                    Text("断线后按 1、2、4、8、15、30 秒退避重试", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoReconnect, onCheckedChange = printer::setAutoReconnectEnabled)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = deviceProfile.protocol == PrintProtocol.QRING_SPP,
                    onClick = {
                        deviceProfile = deviceProfile.copy(protocol = PrintProtocol.QRING_SPP)
                        store.savePaper(deviceProfile)
                    },
                    label = { Text("Qring SPP") },
                )
                FilterChip(
                    selected = deviceProfile.protocol == PrintProtocol.GENERIC_ESC_POS,
                    onClick = {
                        deviceProfile = deviceProfile.copy(protocol = PrintProtocol.GENERIC_ESC_POS)
                        store.savePaper(deviceProfile)
                    },
                    label = { Text("通用 ESC/POS") },
                )
            }
            FilledTonalButton(onClick = { showDevices = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Bluetooth, null)
                Spacer(Modifier.width(8.dp))
                Text(if (printerState.connected) "更换打印机" else "连接打印机")
            }
        }

        SettingsCard("视觉主题", Icons.Rounded.ColorLens) {
            Text("每套主题拥有独立形状、字体、卡片边界与首页构图。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppThemeStyle.entries.forEach { style ->
                    ThemePreview(
                        style = style,
                        selected = appPreferences.theme == style,
                        onClick = { store.setTheme(style) },
                    )
                }
            }
        }

        SettingsCard("OCR 识别质量", Icons.AutoMirrored.Rounded.FactCheck) {
            Text(
                    "相机先拍摄高分辨率照片，再由 PP-OCRv6 Small 本地扫描；真实准确率只统计你对照原图逐字确认的样本。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        if (ocrStats.validatedSamples == 0L) "真实准确率：等待校对样本"
                        else "真实准确率：${"%.2f".format(ocrStats.accuracy * 100f)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${ocrStats.validatedSamples} 份已确认样本 · ${ocrStats.characters} 字符 · ${ocrStats.errors} 处编辑距离错误",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ocrStats.validatedSamples > 0L) {
                        Text(
                            when {
                                ocrStats.accuracy >= 0.95f -> "当前样本达到 95% 优秀水平（仅代表当前样本集）"
                                ocrStats.accuracy >= 0.90f -> "当前样本达到 90% 实用目标，继续积累不同光线和字体样本"
                                else -> "当前样本低于 90%，请改善对焦/光线并继续校对样本"
                            },
                            color = if (ocrStats.accuracy >= 0.90f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { scope.launch { store.ocrQuality.reset() } },
                enabled = ocrStats.validatedSamples > 0L,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清空本机校对统计与纠错规则") }
        }

        SettingsCard("发送打印音效", Icons.Rounded.GraphicEq) {
            Text("12 种内置音效，也可每次随机选择或用算法即时生成旋律。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                PrintSoundPreset.entries.forEach { sound ->
                    FilterChip(
                        selected = appPreferences.printSound == sound,
                        onClick = { store.setPrintSound(sound); PrintSoundEngine.play(sound) },
                        label = { Text(sound.title) },
                    )
                }
            }
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(appPreferences.printSound.title, fontWeight = FontWeight.Bold)
                        Text(appPreferences.printSound.description, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { PrintSoundEngine.play(appPreferences.printSound) }) {
                        Icon(Icons.Rounded.AutoAwesome, null)
                        Spacer(Modifier.width(6.dp))
                        Text("试听")
                    }
                }
            }
        }

        SettingsCard("设备校准", Icons.Rounded.Straighten) {
            Text("硬件参数与纸宽偏移都保存在本机；每次新建仍可独立选择 10–57 mm 纸宽。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(
                onClick = { showPaperCalibration = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Rounded.Straighten, null)
                Spacer(Modifier.width(8.dp))
                Text("打开实际纸宽与偏移校准")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    "打印头点数",
                    deviceProfile.headDots.toString(),
                    { value -> value.toIntOrNull()?.let { deviceProfile = deviceProfile.copy(headDots = it.coerceIn(128, 2048)) } },
                    Modifier.weight(1f),
                )
                NumberField(
                    "分辨率 DPI",
                    deviceProfile.dpi.toString(),
                    { value -> value.toIntOrNull()?.let { deviceProfile = deviceProfile.copy(dpi = it.coerceIn(100, 600)) } },
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    "横向偏移 mm",
                    pretty(deviceProfile.offsetXmm),
                    { it.toFloatOrNull()?.let { value -> deviceProfile = deviceProfile.copy(offsetXmm = value.coerceIn(-20f, 20f)) } },
                    Modifier.weight(1f),
                )
                NumberField(
                    "纵向偏移 mm",
                    pretty(deviceProfile.offsetYmm),
                    { it.toFloatOrNull()?.let { value -> deviceProfile = deviceProfile.copy(offsetYmm = value.coerceIn(-20f, 20f)) } },
                    Modifier.weight(1f),
                )
            }
            FilledTonalButton(
                onClick = { store.savePaper(deviceProfile) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = MaterialTheme.shapes.large,
            ) { Icon(Icons.Rounded.Print, null); Spacer(Modifier.width(8.dp)); Text("保存设备配置") }
        }

        SettingsEntry(
            title = "使用方法与功能说明",
            subtitle = "从连接、纸张、画布到批量打印的完整本地指南",
            icon = Icons.AutoMirrored.Rounded.HelpOutline,
            onClick = { detailPage = SettingsDetailPage.HELP },
        )
        SettingsEntry(
            title = "运行日志",
            subtitle = "查看连接和打印阶段；可保存或分享 .md 诊断文件",
            icon = Icons.AutoMirrored.Rounded.ListAlt,
            onClick = { detailPage = SettingsDetailPage.LOGS },
        )
        SettingsEntry(
            title = "关于${ProductIdentity.NAME}",
            subtitle = "应用介绍、开源致谢、协议依据与 MIT 许可证",
            icon = Icons.Rounded.Info,
            onClick = { detailPage = SettingsDetailPage.ABOUT },
        )
        Spacer(Modifier.height(28.dp))
    }
    if (showDevices) DeviceSheet(printer) { showDevices = false }
    if (showPaperCalibration) {
        PaperCalibrationSheet(
            initial = deviceProfile,
            printer = printer,
            store = store,
            onConnect = {
                showPaperCalibration = false
                showDevices = true
            },
            onDismiss = { showPaperCalibration = false },
        )
    }
    detailPage?.let { page ->
        SettingsDetailSheet(
            page = page,
            logEntries = logCategory?.let { selected -> logEntries.filter { it.category == selected } } ?: logEntries,
            selectedLogCategory = logCategory,
            onLogCategoryChange = { logCategory = it },
            onDismiss = { detailPage = null },
            onSaveLogs = { logSaver.launch(logs.markdownFileName(logCategory)) },
            onShareLogs = {
                MarkdownFileExporter.share(
                    context = context,
                    directoryName = "runtime_logs",
                    fileName = logs.markdownFileName(logCategory),
                    subject = "${ProductIdentity.NAME}${logCategory?.title ?: "运行"}日志",
                    content = logs.asMarkdown(logCategory),
                ).onFailure { Toast.makeText(context, "分享失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show() }
            },
            onClearLogs = logs::clear,
        )
    }
}

private enum class SettingsDetailPage { HELP, LOGS, ABOUT }

@Composable
private fun SettingsEntry(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsDetailSheet(
    page: SettingsDetailPage,
    logEntries: List<RuntimeLogEntry>,
    selectedLogCategory: RuntimeLogCategory?,
    onLogCategoryChange: (RuntimeLogCategory?) -> Unit,
    onDismiss: () -> Unit,
    onSaveLogs: () -> Unit,
    onShareLogs: () -> Unit,
    onClearLogs: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    fun openExternal(url: String) {
        runCatching { uriHandler.openUri(url) }
            .onFailure { Toast.makeText(context, "没有可用的浏览器：${it.message.orEmpty()}", Toast.LENGTH_LONG).show() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = MaterialTheme.shapes.extraLarge) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 820.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                when (page) {
        SettingsDetailPage.HELP -> "${ProductIdentity.NAME}使用方法"
                    SettingsDetailPage.LOGS -> "运行日志"
        SettingsDetailPage.ABOUT -> "关于${ProductIdentity.NAME}"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
            )
            when (page) {
                SettingsDetailPage.HELP -> HelpContent()
                SettingsDetailPage.LOGS -> {
                    Text(
                        "日志分为两类：运行与打印诊断、标签内容编辑摘要。内容类只记录元素类型、数量和尺寸等脱敏信息，不保存原文、图片或变量单元格值。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("日志类型", fontWeight = FontWeight.Bold)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        FilterChip(
                            selected = selectedLogCategory == null,
                            onClick = { onLogCategoryChange(null) },
                            label = { Text("全部") },
                        )
                        RuntimeLogCategory.entries.forEach { category ->
                            FilterChip(
                                selected = selectedLogCategory == category,
                                onClick = { onLogCategoryChange(category) },
                                label = { Text(category.title) },
                            )
                        }
                    }
                    Text(
                        selectedLogCategory?.description ?: "当前显示全部日志；可选择一个类别后单独保存或分享。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onSaveLogs, modifier = Modifier.weight(1f)) { Text("保存 .md") }
                        OutlinedButton(onClick = onShareLogs, modifier = Modifier.weight(1f)) { Text("分享") }
                        TextButton(onClick = onClearLogs) { Text("清空全部") }
                    }
                    if (logEntries.isEmpty()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                            Text(
                                "暂无${selectedLogCategory?.title ?: "运行"}日志",
                                Modifier.fillMaxWidth().padding(18.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else logEntries.asReversed().forEach { RuntimeLogRow(it) }
                }
                SettingsDetailPage.ABOUT -> {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(AppIdentity.NAME, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(AppIdentity.TAGLINE, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(AppIdentity.SUMMARY, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    AttributionParagraph("从创作到打印", AppIdentity.PRINT_WORKFLOW)
                    AttributionParagraph("本地优先", AppIdentity.LOCAL_FIRST)
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text("特别感谢 GitHub 开发者 Thisko", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            Text(
                    "感谢他开源 QrintPrint，并公开 Qring / BeePrt BY 系列热敏打印机的通信方式、状态命令与点阵打印流程。正是这份无私分享，让${ProductIdentity.NAME}能够在可靠的开源成果上继续完善 Android 端体验。",
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                    AttributionParagraph(
                        "与上游通信协议保持一致",
                    "${ProductIdentity.NAME}以 QrintPrint 公布的经典蓝牙 SPP 协议为实现依据：Qring 私有 10 FF 状态命令、384 点光栅、每包最多 1024 字节，以及 ESC J 走纸和 GS v 0 光栅输出等核心行为均与上游公开协议保持一致。在此基础上，${ProductIdentity.NAME}独立扩展 Android 界面、画布、模板、OCR、变量数据和本地工作流。",
                    )
                    AttributionParagraph(
                        "沿用 MIT 开源许可证",
                        "本项目继续采用与 QrintPrint 一致的 MIT License，保留 Copyright © 2026 Thisko 及完整许可文本。你可以依照 MIT 条款使用、修改和分发源码；上游软件按许可证以“现状”提供。",
                    )
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Text("项目与其他版本", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text(
                                "查看 Android 源码，也可以使用电脑版或直接在受支持的浏览器中打开 Web 端。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ProjectLinkButton("本项目 · Android 源码", AppIdentity.ANDROID_PROJECT_URL, ::openExternal)
                            ProjectLinkButton("电脑版 · Windows / Web 源码", AppIdentity.DESKTOP_PROJECT_URL, ::openExternal)
                            ProjectLinkButton("Web 端 · 浏览器直接使用", AppIdentity.WEB_APP_URL, ::openExternal)
                        }
                    }
                    OutlinedButton(
                        onClick = { openExternal(AppIdentity.UPSTREAM_PROJECT_URL) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("访问并支持 QrintPrint 原项目") }
                    Text(
                    "${ProductIdentity.NAME}是基于开源成果继续开发的独立第三方应用，与打印机厂商不存在隶属或官方授权关系。再次向 Thisko 及所有开源贡献者致谢。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectLinkButton(
    label: String,
    url: String,
    openUri: (String) -> Unit,
) {
    OutlinedButton(
        onClick = { openUri(url) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(label)
    }
}

@Composable
private fun HelpContent() {
    HelpSection("1 · 连接打印机", "在设置页第一项点击“连接打印机”。Android 12 及以上允许附近设备权限；Qring 会优先显示。开启自动重连后，断线会按退避时间重试。")
    HelpSection("2 · 选择真实纸张", "进入任一功能或模板时先选连续纸/标签纸。纸宽支持 10.0–57.0 mm 无级输入；小于 55 mm 必须选择纸张实际靠左或靠右。标签纸填写宽和长，连续纸只填宽并按内容自动算长。")
    HelpSection("3 · 编辑画布", "单指拖动元素；拖动虚线框四边或八个控制点改变宽高；双指缩放；旋转支持角度输入与无级滑动。多选后对齐会移动整个组合，不破坏内部相对位置。侧边长条可滚动长画布，并随预览一起收起或展开。")
    HelpSection("4 · 添加内容", "底部工具可添加文字、图片、二维码、一维码、形状、日期、流水号、表格和手绘。文字提供连续字重、400/700 快捷值、5 级点阵增强和反色；图片提供本地抖动、阈值、亮度、对比度和反色。")
    HelpSection("5 · 模板与变量批打", "行业入口只显示对应分类。模板元素均可继续修改。CSV、TSV、Excel 与 WPS 表格可绑定 {{字段名}}，预览每行结果后批量打印。")
    HelpSection("6 · 文档与相机", "文档直印会按当前纸张分页；拍照 OCR 使用 PP-OCRv6 Small 本地识别。扫描前让文字充满蓝框并点击对焦，识别结果可校对后转为画布文字。")
    HelpSection("7 · 最终预览与打印", "最终预览就是发送给打印机的同一份 203 dpi 点阵。多份标签会逐份建立会话并等待设备空闲；取消会在当前份结束后停下，可从已完成份数继续。")
    HelpSection("8 · 校准与排错", "设备校准中的横向/纵向偏移直接作用于最终点阵。漏打、上盖误报或断线时，先在运行日志中复现并导出 .md；异常闪退报告也可复制、保存或分享。")
}

@Composable
private fun HelpSection(title: String, body: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RuntimeLogRow(entry: RuntimeLogEntry) {
    val tone = when (entry.level) {
        RuntimeLogLevel.INFO -> MaterialTheme.colorScheme.primary
        RuntimeLogLevel.WARNING -> MaterialTheme.colorScheme.tertiary
        RuntimeLogLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).background(tone, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.event, fontWeight = FontWeight.Bold)
                if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${entry.category.title} · ${formatLogTime(entry.timestamp)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatLogTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date(timestamp))

@Composable
private fun ThemePreview(style: AppThemeStyle, selected: Boolean, onClick: () -> Unit) {
    val palette = themePalette(style)
    val shape = themeShape(style)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(164.dp).height(174.dp),
        shape = shape,
        color = palette.first,
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else palette.third.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            when (style) {
                AppThemeStyle.AURORA -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(3) { Box(Modifier.weight(1f).height((24 + it * 7).dp).background(palette.second, RoundedCornerShape(12.dp))) }
                }
                AppThemeStyle.PAPER -> {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.third))
                    Text("FIELD NOTES", color = palette.third, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Serif)
                }
                AppThemeStyle.INK -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(35.dp).background(palette.third))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(3.dp).background(palette.third))
                }
                AppThemeStyle.MINT -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    repeat(4) { Box(Modifier.size(27.dp).background(palette.second, CutCornerShape(7.dp))) }
                }
                AppThemeStyle.SUNSET -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.width(72.dp).height(31.dp).background(palette.second, CircleShape))
                    Box(Modifier.size(31.dp).background(palette.third, CircleShape))
                }
                AppThemeStyle.NEON -> Text("LOCAL://READY_", color = palette.second, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                AppThemeStyle.FROST_GLASS -> Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(Modifier.fillMaxWidth().height(28.dp).background(Color.White.copy(alpha = 0.72f), RoundedCornerShape(14.dp)))
                    Box(Modifier.fillMaxWidth(0.72f).height(18.dp).background(palette.second.copy(alpha = 0.46f), RoundedCornerShape(9.dp)))
                }
                AppThemeStyle.LIQUID_GLASS -> Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(palette.second.copy(alpha = 0.62f), CircleShape))
                    Box(Modifier.width(76.dp).height(29.dp).background(Color.White.copy(alpha = 0.6f), CircleShape))
                }
                AppThemeStyle.SMOKE_GLASS -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("GLASS://LOCAL", color = palette.second, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.third.copy(alpha = 0.72f)))
                }
                AppThemeStyle.PRISM_GLASS -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(palette.second, Color(0xFFE45E9B), Color(0xFF29B8BE)).forEachIndexed { index, color ->
                        Box(Modifier.weight(1f).height((24 + index * 8).dp).background(color.copy(alpha = 0.64f), CutCornerShape(8.dp)))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text(style.title, color = palette.third, fontWeight = FontWeight.Bold)
            Text(style.description, color = palette.third.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
}

private fun themePalette(style: AppThemeStyle): Triple<Color, Color, Color> = when (style) {
    AppThemeStyle.AURORA -> Triple(Color(0xFFE8ECFF), Color(0xFF7559FF), Color(0xFF17358E))
    AppThemeStyle.PAPER -> Triple(Color(0xFFFFF8EC), Color(0xFFD5B58E), Color(0xFF5C3D2C))
    AppThemeStyle.INK -> Triple(Color.White, Color(0xFFCC3038), Color.Black)
    AppThemeStyle.MINT -> Triple(Color(0xFFE5FAF5), Color(0xFF23A591), Color(0xFF005C52))
    AppThemeStyle.SUNSET -> Triple(Color(0xFFFFE5E0), Color(0xFFFF725E), Color(0xFF8D2E6A))
    AppThemeStyle.NEON -> Triple(Color(0xFF101522), Color(0xFF55F4E2), Color(0xFFD6CBFF))
    AppThemeStyle.FROST_GLASS -> Triple(Color(0xFFDDEFFC), Color(0xFF5FA8C7), Color(0xFF174B68))
    AppThemeStyle.LIQUID_GLASS -> Triple(Color(0xFFDDE5FF), Color(0xFF5A6FDC), Color(0xFF27366F))
    AppThemeStyle.SMOKE_GLASS -> Triple(Color(0xFF1B222C), Color(0xFFD8C3A5), Color(0xFFE6ECF6))
    AppThemeStyle.PRISM_GLASS -> Triple(Color(0xFFFFE8F3), Color(0xFF7C5FE8), Color(0xFF57245E))
}

private fun themeShape(style: AppThemeStyle): Shape = when (style) {
    AppThemeStyle.AURORA -> RoundedCornerShape(28.dp)
    AppThemeStyle.PAPER -> RoundedCornerShape(8.dp)
    AppThemeStyle.INK -> RoundedCornerShape(1.dp)
    AppThemeStyle.MINT -> CutCornerShape(18.dp)
    AppThemeStyle.SUNSET -> RoundedCornerShape(42.dp)
    AppThemeStyle.NEON -> CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp)
    AppThemeStyle.FROST_GLASS -> RoundedCornerShape(30.dp)
    AppThemeStyle.LIQUID_GLASS -> RoundedCornerShape(48.dp)
    AppThemeStyle.SMOKE_GLASS -> RoundedCornerShape(16.dp)
    AppThemeStyle.PRISM_GLASS -> CutCornerShape(topStart = 20.dp, bottomEnd = 24.dp)
}

@Composable
private fun SettingsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
            }
            content()
        }
    }
}

@Composable
private fun DeviceMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f), modifier = modifier) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
private fun AttributionParagraph(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    DraftNumberField(label, value, onChange, modifier)
}

private fun pretty(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)
