package com.qrint.studio.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.qrint.studio.ProductIdentity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddBox
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.qrint.studio.IncomingShare
import com.qrint.studio.QrintApplication
import com.qrint.studio.data.CapturedMediaStore
import com.qrint.studio.data.CrashReportExporter
import com.qrint.studio.data.LocalDocumentImporter
import com.qrint.studio.data.TemplateCatalog
import com.qrint.studio.data.VariableDataParser
import com.qrint.studio.data.VariableDataWorkbook
import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.PaperMode
import com.qrint.studio.ui.editor.EditorScreen
import com.qrint.studio.ui.screens.HistoryScreen
import com.qrint.studio.ui.screens.HomeScreen
import com.qrint.studio.ui.screens.SettingsScreen
import com.qrint.studio.ui.screens.TemplateScreen
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class MainTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun QrintApp(
    incomingShare: IncomingShare? = null,
    onIncomingShareConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val application = context.applicationContext as QrintApplication
    val clipboard = LocalClipboardManager.current
    var tabIndex by remember { mutableIntStateOf(0) }
    var editorDocument by remember { mutableStateOf<LabelDocument?>(null) }
    var editorVariableWorkbook by remember { mutableStateOf<VariableDataWorkbook?>(null) }
    var editorBatchDocuments by remember { mutableStateOf<List<LabelDocument>>(emptyList()) }
    var templateCategory by remember { mutableStateOf("全部") }
    var crashReport by remember { mutableStateOf(application.crashReports.latest()) }
    val crashReportSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val report = crashReport
        if (uri != null && report != null) {
            CrashReportExporter.writeMarkdown(context, uri, report).fold(
                onSuccess = { Toast.makeText(context, "异常报告已保存", Toast.LENGTH_SHORT).show() },
                onFailure = { Toast.makeText(context, "保存失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show() },
            )
        }
    }
    val tabs = remember {
        listOf(
            MainTab("首页", Icons.Rounded.Home),
            MainTab("模板", Icons.Rounded.AddBox),
            MainTab("历史", Icons.Rounded.History),
            MainTab("设置", Icons.Rounded.Settings),
        )
    }
    fun openEditor(
        document: LabelDocument,
        workbook: VariableDataWorkbook? = null,
        batchDocuments: List<LabelDocument> = emptyList(),
    ) {
        application.crashReports.setStage("editor-entry:${document.id.take(24)}:${document.category.take(24)}")
        editorVariableWorkbook = workbook
        editorBatchDocuments = batchDocuments
        editorDocument = document
    }
    LaunchedEffect(incomingShare?.token) {
        val request = incomingShare ?: return@LaunchedEffect
        try {
            val lowerName = request.displayName.lowercase()
            val variableDataFile = listOf(".csv", ".tsv", ".xls", ".xlsx", ".et")
                .any(lowerName::endsWith)
            val directDocumentFile = listOf(".pdf", ".docx", ".pptx", ".doc", ".ppt", ".txt", ".md")
                .any(lowerName::endsWith) || request.mimeType in setOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/msword",
                "application/vnd.ms-powerpoint",
                "text/markdown",
                "text/plain",
            ) && !variableDataFile
            when {
                request.mimeType.startsWith("image/") -> {
                    val importedUri = withContext(Dispatchers.IO) {
                        CapturedMediaStore.importImage(context, request.uri).getOrThrow()
                    }
                    val paper = application.localStore.paper.value
                    val width = paper.contentWidthDots().coerceAtLeast(80)
                    val height = if (paper.mode == PaperMode.LABEL) {
                        paper.fixedHeightDots().coerceAtLeast(80)
                    } else {
                        (width * 0.75f).toInt().coerceAtLeast(80)
                    }
                    openEditor(
                        EditorFactories.blankDocument("分享图片", paper).copy(
                            elements = listOf(
                                EditorFactories.imageElement(importedUri.toString(), x = 0, y = 0).copy(
                                    width = width,
                                    height = height,
                                ),
                            ),
                        ),
                    )
                    Toast.makeText(context, "图片已复制到本地并打开画布", Toast.LENGTH_SHORT).show()
                }
                request.displayName.endsWith(".json", ignoreCase = true) -> {
                    val document = withContext(Dispatchers.IO) {
                        val json = context.contentResolver.openInputStream(request.uri)?.use {
                            it.readTextBounded(8 * 1024 * 1024)
                        } ?: error("无法读取分享的模板")
                        application.localStore.importDocument(json).getOrThrow()
                    }
                    openEditor(document)
                            Toast.makeText(context, "${ProductIdentity.NAME}模板已导入", Toast.LENGTH_SHORT).show()
                }
                directDocumentFile -> {
                    val batch = withContext(Dispatchers.IO) {
                        LocalDocumentImporter.importDocument(
                            context,
                            request.uri,
                            request.displayName,
                            request.mimeType,
                            application.localStore.paper.value,
                        ).getOrThrow()
                    }
                    openEditor(batch.documents.first(), batchDocuments = batch.documents)
                    Toast.makeText(context, "文档已离线解析为 ${batch.documents.size} 页", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    val workbook = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(request.uri)?.use { input ->
                            VariableDataParser.parseWorkbook(request.displayName, input)
                        } ?: error("无法读取分享的数据文件")
                    }
                    openEditor(
                        EditorFactories.blankDocument("${request.displayName} 批打", application.localStore.paper.value),
                        workbook,
                    )
                    Toast.makeText(context, "变量数据已导入，请绑定字段", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (error: Throwable) {
            Toast.makeText(context, "无法导入分享文件：${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
        } finally {
            onIncomingShareConsumed()
        }
    }
    BackHandler(editorDocument != null) {
        editorDocument = null
        editorVariableWorkbook = null
        editorBatchDocuments = emptyList()
    }
    val activeDocument = editorDocument
    LaunchedEffect(activeDocument?.id, tabIndex) {
        application.crashReports.setStage(
            activeDocument?.let { "editor:${it.id.take(24)}:${it.category.take(24)}" } ?: "main-tab:$tabIndex",
        )
    }

    // The editor owns full-size print bitmaps. Dispose the catalogue before allocating the first
    // editor raster instead of keeping both navigation trees alive during an exit animation.
    // This keeps peak memory predictable and prevents duplicate launcher registration.
    if (activeDocument == null) {
        Scaffold(
                bottomBar = {
                    NavigationBar {
                        tabs.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = tabIndex == index,
                                onClick = { tabIndex = index },
                                icon = { Icon(tab.icon, null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
        ) { padding ->
            AnimatedContent(
                    targetState = tabIndex,
                    transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.985f)) togetherWith fadeOut() },
                    label = "main-tabs",
            ) { index ->
                when (index) {
                        0 -> HomeScreen(
                            padding = padding,
                            printer = application.printerManager,
                            paper = application.localStore.paper,
                            onOpenTemplates = { category -> templateCategory = category; tabIndex = 1 },
                            onOpenEditor = ::openEditor,
                            onOpenSettings = { tabIndex = 3 },
                        )
                        1 -> TemplateScreen(
                            padding = padding,
                            catalog = TemplateCatalog,
                            store = application.localStore,
                            initialCategory = templateCategory,
                            onOpenEditor = ::openEditor,
                        )
                        2 -> HistoryScreen(
                            padding = padding,
                            store = application.localStore,
                            onOpenEditor = { openEditor(it.copy(builtIn = false)) },
                        )
                        else -> SettingsScreen(
                            padding = padding,
                            store = application.localStore,
                            printer = application.printerManager,
                            logs = application.runtimeLogs,
                        )
                }
            }
        }
    } else {
        EditorScreen(
            initialDocument = activeDocument,
            initialVariableWorkbook = editorVariableWorkbook,
            initialBatchDocuments = editorBatchDocuments,
            store = application.localStore,
            printer = application.printerManager,
            logs = application.runtimeLogs,
            onClose = {
                editorDocument = null
                editorVariableWorkbook = null
                editorBatchDocuments = emptyList()
            },
        )
    }

    crashReport?.let { report ->
        AlertDialog(
            onDismissRequest = {
                application.crashReports.clear()
                crashReport = null
            },
            title = { Text("检测到上次异常") },
            text = { Text("位置：${report.stage}\n${report.summary}\n\n可复制、保存为 .md 或通过系统分享；报告不包含标签内容或图片。") },
            confirmButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { crashReportSaver.launch(report.markdownFileName()) }) { Text("保存 .md") }
                    TextButton(onClick = {
                        CrashReportExporter.shareMarkdown(context, report).onFailure {
                            Toast.makeText(context, "分享失败：${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
                        }
                    }) { Text("分享") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report.asMarkdown()))
                        Toast.makeText(context, "诊断信息已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    application.crashReports.clear()
                    crashReport = null
                }) { Text("关闭") }
            },
        )
    }
}

private fun InputStream.readTextBounded(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "文件超过 ${maxBytes / 1024 / 1024} MB 限制" }
        output.write(buffer, 0, read)
    }
    return output.toString(Charsets.UTF_8.name())
}
