package com.qrint.studio.ui.editor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ShapeLine
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ViewWeek
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.qrint.studio.data.LocalStore
import com.qrint.studio.data.RuntimeLogCategory
import com.qrint.studio.data.RuntimeLogStore
import com.qrint.studio.ProductIdentity
import com.qrint.studio.data.IndustryCatalog
import com.qrint.studio.data.CapturedMediaStore
import com.qrint.studio.data.CodeTemplateMatch
import com.qrint.studio.data.CodeTemplateMatcher
import com.qrint.studio.data.LocalDocumentImporter
import com.qrint.studio.data.TemplateCatalog
import com.qrint.studio.data.applyTo
import com.qrint.studio.data.toEditableElements
import com.qrint.studio.data.VariableDataParser
import com.qrint.studio.data.VariableDataTable
import com.qrint.studio.data.VariableDataWorkbook
import com.qrint.studio.data.resolveVariables
import com.qrint.studio.data.variableFields
import com.qrint.studio.data.normalizeVariableRange
import com.qrint.studio.data.queryVariableRows
import com.qrint.studio.data.variableRowsIn
import com.qrint.studio.model.BarcodeType
import com.qrint.studio.model.EditorFactories
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.ImageFit
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.model.LabelElement
import com.qrint.studio.model.MAX_DOCUMENT_HEIGHT_DOTS
import com.qrint.studio.model.PrintFontCatalog
import com.qrint.studio.model.PrintFontOption
import com.qrint.studio.model.ShapeKind
import com.qrint.studio.model.TextAlignment
import com.qrint.studio.model.withDeviceProfile
import com.qrint.studio.printer.BluetoothPrinterManager
import com.qrint.studio.render.BarcodeDecoder
import com.qrint.studio.render.DecodedBarcode
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.render.ImageLoader
import com.qrint.studio.render.OfflineTextRecognizer
import com.qrint.studio.render.OfflineTextScan
import com.qrint.studio.render.RenderedLabel
import com.qrint.studio.ui.components.DeviceSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    initialDocument: LabelDocument,
    initialVariableWorkbook: VariableDataWorkbook? = null,
    initialBatchDocuments: List<LabelDocument> = emptyList(),
    store: LocalStore,
    printer: BluetoothPrinterManager,
    logs: RuntimeLogStore? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val printerState by printer.state.collectAsState()
    val userFonts by store.userFonts.fonts.collectAsState()
    val fontOptions = remember(userFonts) {
        PrintFontCatalog.options + userFonts.map { font ->
            PrintFontOption(
                key = font.key,
                title = font.displayName,
                description = "本地字体 · ${font.sizeBytes / 1024L} KB · 预览与打印共用",
                bundled = true,
            )
        }
    }
    val session = remember(initialDocument.id) {
        EditorSession(initialDocument.withDeviceProfile(store.paper.value), logs)
    }
    val workScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var rendered by remember { mutableStateOf<RenderedLabel?>(null) }
    var renderError by remember { mutableStateOf<String?>(null) }
    var rendering by remember { mutableStateOf(true) }
    var showPreview by remember { mutableStateOf(false) }
    var showSave by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var showDrawing by remember { mutableStateOf(false) }
    var showVariableData by remember { mutableStateOf(initialVariableWorkbook != null) }
    var showProductLibrary by remember { mutableStateOf(false) }
    var variableWorkbook by remember(initialDocument.id) { mutableStateOf(initialVariableWorkbook) }
    var variableData by remember(initialDocument.id) { mutableStateOf(initialVariableWorkbook?.defaultSheet) }
    var documentBatch by remember(initialDocument.id) {
        mutableStateOf(initialBatchDocuments.map { it.withDeviceProfile(store.paper.value) })
    }
    var activeDocumentPageIndex by remember(initialDocument.id) { mutableIntStateOf(0) }
    var selectedDocumentPageIndices by remember(initialDocument.id) {
        mutableStateOf(initialBatchDocuments.indices.toSet())
    }
    var showDocumentPages by remember(initialDocument.id) { mutableStateOf(initialBatchDocuments.size > 1) }
    var variableRow by remember(initialDocument.id) { mutableIntStateOf(0) }
    var variableBatchRange by remember(initialDocument.id) {
        mutableStateOf(0..(initialVariableWorkbook?.defaultSheet?.rows?.lastIndex ?: 0))
    }
    var variableFilter by remember(initialDocument.id) { mutableStateOf("") }
    var variableSortField by remember(initialDocument.id) { mutableStateOf<String?>(null) }
    var variableSortAscending by remember(initialDocument.id) { mutableStateOf(true) }
    var ocrScan by remember(initialDocument.id) { mutableStateOf<OfflineTextScan?>(null) }
    var liveCameraMode by remember { mutableStateOf<LiveCameraMode?>(null) }
    var scanningText by remember { mutableStateOf(false) }
    // The camera is an external activity. Keep the target URI across activity/process recreation
    // so its result can still be resolved when Android restores this editor.
    var pendingPhotoUriText by rememberSaveable(initialDocument.id) { mutableStateOf<String?>(null) }
    var launchPhotoAfterPermission by rememberSaveable(initialDocument.id) { mutableStateOf(false) }
    var pendingPhotoCropUriText by rememberSaveable(initialDocument.id) { mutableStateOf<String?>(null) }
    var processingPhotoCrop by remember { mutableStateOf(false) }
    var pendingDecodedCode by remember { mutableStateOf<DecodedBarcode?>(null) }
    var pendingCodeMatches by remember { mutableStateOf<List<CodeTemplateMatch>>(emptyList()) }

    fun currentDocumentPages(): List<LabelDocument> = mergeActiveDocumentPage(
        pages = documentBatch,
        activeIndex = activeDocumentPageIndex,
        activeDocument = session.document,
    )

    fun openDocumentPage(requestedIndex: Int) {
        if (documentBatch.isEmpty()) return
        val savedPages = currentDocumentPages()
        val nextIndex = requestedIndex.coerceIn(savedPages.indices)
        documentBatch = savedPages
        activeDocumentPageIndex = nextIndex
        session.openDocument(savedPages[nextIndex])
    }

    fun clearDocumentPages() {
        documentBatch = emptyList()
        activeDocumentPageIndex = 0
        selectedDocumentPageIndices = emptySet()
        showDocumentPages = false
    }

    LaunchedEffect(initialVariableWorkbook?.defaultSheet?.sheetName, initialVariableWorkbook?.defaultSheet?.rows?.size) {
        initialVariableWorkbook?.defaultSheet?.let { sheet ->
            logs?.info(
                "载入变量数据",
                "${sheet.rows.size} 行 · ${sheet.headers.size} 列 · 字段 ${sheet.headers.take(12).joinToString("、")}",
                RuntimeLogCategory.CONTENT,
            )
        }
    }

    fun insertDecodedCode(code: DecodedBarcode) {
        session.add(
            EditorFactories.barcodeElement(code.type, y = nextY(session.document)).copy(
                barcodeContent = code.content,
                barcodeCaption = !code.type.twoDimensional,
            ),
        )
    }

    fun routeDecodedCode(code: DecodedBarcode) {
        val product = store.products.findByBarcode(code.content)
        val templates = TemplateCatalog.all.map { it.document } + store.templates.value
        val matches = CodeTemplateMatcher.match(code, templates, product)
        if (product == null && matches.isEmpty()) insertDecodedCode(code)
        else {
            pendingDecodedCode = code
            pendingCodeMatches = matches
        }
    }

    fun recognizeCode(selectedUri: Uri) {
        workScope.launch {
            val decoded = withContext(Dispatchers.IO) { BarcodeDecoder.decode(context, selectedUri) }
            decoded.onSuccess { code ->
                routeDecodedCode(code)
                snackbar.showSnackbar("已识别 ${code.type.label}")
            }.onFailure { error -> snackbar.showSnackbar(error.message ?: "没有识别到可用编码") }
        }
    }

    fun recognizeText(selectedUri: Uri) {
        workScope.launch {
            scanningText = true
            val result = withContext(Dispatchers.IO) { OfflineTextRecognizer.recognize(context, selectedUri) }
            result.onSuccess { scan ->
                ocrScan = store.ocrQuality.applyValidatedCorrections(scan)
                snackbar.showSnackbar("离线识别完成：${scan.lines.size} 行可编辑文字")
            }.onFailure { error -> snackbar.showSnackbar(error.message ?: "文字识别失败") }
            scanningText = false
        }
    }

    fun addCapturedPhoto(uri: Uri, message: String) {
        workScope.launch {
            val base = EditorFactories.imageElement(uri.toString(), y = nextY(session.document))
            val dimensions = withContext(Dispatchers.IO) { ImageLoader.dimensions(context, uri.toString()) }
            session.add(dimensions?.let { source -> fitImageToSource(base, source, session.document) } ?: base)
            snackbar.showSnackbar(message)
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val selected = session.selected?.takeIf { element -> element.kind == ElementKind.IMAGE }
            val base = selected?.copy(imageUri = it.toString(), imageFit = ImageFit.FIT)
                ?: EditorFactories.imageElement(it.toString(), y = nextY(session.document))
            workScope.launch {
                val dimensions = withContext(Dispatchers.IO) { ImageLoader.dimensions(context, it.toString()) }
                val fitted = dimensions?.let { source -> fitImageToSource(base, source, session.document) } ?: base
                if (selected == null) session.add(fitted) else session.update(fitted)
                if (dimensions == null) snackbar.showSnackbar("图片尺寸读取失败，已保留默认大小，可在下方手动缩放")
            }
        }
    }
    val codeScanner = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::recognizeCode)
    }
    val textScanner = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::recognizeText)
    }
    val photoCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captured = pendingPhotoUriText?.let(Uri::parse)
        pendingPhotoUriText = null
        launchPhotoAfterPermission = false
        captured?.let { uri ->
            if (!success) {
                CapturedMediaStore.delete(context, uri)
            } else workScope.launch {
                val validation = withContext(Dispatchers.IO) { CapturedMediaStore.validateImage(context, uri) }
                validation.onSuccess {
                    pendingPhotoCropUriText = uri.toString()
                    snackbar.showSnackbar("拍照完成，请选择整张照片或圈选打印区域")
                }.onFailure { error ->
                    CapturedMediaStore.delete(context, uri)
                    snackbar.showSnackbar("拍照结果无效：${error.message ?: "无法读取照片"}")
                }
            }
        } ?: if (success) {
            workScope.launch { snackbar.showSnackbar("拍照状态已丢失，请重新拍摄") }
        } else Unit
    }
    val photoCameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val captured = pendingPhotoUriText?.let(Uri::parse)
        val shouldLaunch = launchPhotoAfterPermission
        launchPhotoAfterPermission = false
        if (granted && shouldLaunch && captured != null) {
            runCatching { photoCapture.launch(captured) }.onFailure { error ->
                CapturedMediaStore.deleteIfEmpty(context, captured)
                pendingPhotoUriText = null
                workScope.launch { snackbar.showSnackbar("无法启动相机：${error.message ?: "相机不可用"}") }
            }
        } else {
            captured?.let { CapturedMediaStore.deleteIfEmpty(context, it) }
            pendingPhotoUriText = null
            if (shouldLaunch) {
                workScope.launch { snackbar.showSnackbar("需要相机权限才能拍照；你仍可使用相册图片") }
            }
        }
    }
    val documentImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            workScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(selectedUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("无法读取文件")
                    }.fold(onSuccess = store::importDocument, onFailure = { Result.failure(it) })
                }
                imported.onSuccess { document ->
                    clearDocumentPages()
                    session.setDocument(document.withDeviceProfile(store.paper.value))
                    snackbar.showSnackbar("模板已导入")
                }.onFailure { error -> snackbar.showSnackbar("导入失败：${error.message}") }
            }
        }
    }
    val variableDataImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            workScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.contentResolver.query(
                            selectedUri,
                            arrayOf(OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) cursor.getString(0) else null
                        } ?: selectedUri.lastPathSegment ?: "变量数据"
                        context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            VariableDataParser.parseWorkbook(name, input)
                        } ?: error("无法读取数据文件")
                    }
                }
                imported.onSuccess { workbook ->
                    val table = workbook.defaultSheet
                    logs?.info(
                        "载入变量数据",
                        "${workbook.sourceName} · ${table.rows.size} 行 · ${table.headers.size} 列 · ${workbook.sheets.size} 个工作表",
                        RuntimeLogCategory.CONTENT,
                    )
                    clearDocumentPages()
                    variableWorkbook = workbook
                    variableData = table
                    variableRow = 0
                    variableBatchRange = 0..table.rows.lastIndex
                    variableFilter = ""
                    variableSortField = null
                    variableSortAscending = true
                    showVariableData = true
                    snackbar.showSnackbar(
                        if (workbook.sheets.size == 1) "已载入 ${table.rows.size} 条变量数据"
                        else "已载入 ${workbook.sheets.size} 个工作表，可手动选择",
                    )
                }.onFailure { error -> snackbar.showSnackbar("数据导入失败：${error.message}") }
            }
        }
    }
    val productDataImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            workScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.sharedDisplayName(selectedUri, "商品资料")
                        val workbook = context.contentResolver.openInputStream(selectedUri)?.use { input ->
                            VariableDataParser.parseWorkbook(name, input)
                        } ?: error("无法读取商品表格")
                        var imported = 0
                        workbook.sheets.forEach { table -> imported += store.products.import(table) }
                        imported
                    }
                }
                result.onSuccess { count ->
                    logs?.info("导入商品变量资料", "已导入或更新 $count 条记录", RuntimeLogCategory.CONTENT)
                    snackbar.showSnackbar("已导入或更新 $count 条商品资料")
                }
                    .onFailure { error -> snackbar.showSnackbar("商品资料导入失败：${error.message.orEmpty()}") }
            }
        }
    }
    val printableDocumentImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            workScope.launch {
                val imported = withContext(Dispatchers.IO) {
                    runCatching {
                        val name = context.sharedDisplayName(selectedUri, "导入文档")
                        LocalDocumentImporter.importDocument(
                            context = context,
                            uri = selectedUri,
                            sourceName = name,
                            mimeType = context.contentResolver.getType(selectedUri),
                            paper = session.document.paper,
                        ).getOrThrow()
                    }
                }
                imported.onSuccess { batch ->
                    val calibrated = batch.documents.map { it.withDeviceProfile(store.paper.value) }
                    documentBatch = calibrated
                    activeDocumentPageIndex = 0
                    selectedDocumentPageIndices = calibrated.indices.toSet()
                    showDocumentPages = calibrated.size > 1
                    variableWorkbook = null
                    variableData = null
                    variableRow = 0
                    variableBatchRange = 0..0
                    session.openDocument(calibrated.first())
                    snackbar.showSnackbar("已导入 ${batch.documents.size} 页；可逐页编辑并选择全部或指定页打印")
                }.onFailure { error -> snackbar.showSnackbar("文档导入失败：${error.message}") }
            }
        }
    }
    val fontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            workScope.launch {
                val result = store.userFonts.import(
                    selectedUri,
                    context.sharedDisplayName(selectedUri, "本地字体"),
                )
                result.onSuccess { font ->
                    session.selected
                        ?.takeIf { it.kind == ElementKind.TEXT || it.kind == ElementKind.DATE_TIME || it.kind == ElementKind.SEQUENCE }
                        ?.let { selected -> session.update(selected.copy(fontFamily = font.key)) }
                    snackbar.showSnackbar("已导入并应用字体：${font.displayName}")
                }.onFailure { error ->
                    snackbar.showSnackbar("字体导入失败：${error.message ?: "文件无效"}")
                }
            }
        }
    }

    val variableViewRows = remember(variableData, variableFilter, variableSortField, variableSortAscending) {
        variableData?.let { table ->
            queryVariableRows(table, variableFilter, variableSortField, variableSortAscending)
        }.orEmpty()
    }

    LaunchedEffect(session.document, activeDocumentPageIndex, documentBatch.size) {
        if (documentBatch.isNotEmpty() && activeDocumentPageIndex in documentBatch.indices) {
            documentBatch = mergeActiveDocumentPage(documentBatch, activeDocumentPageIndex, session.document)
        }
    }

    // Continuous paper grows from the exact Android text layout used by LabelRenderer. Debouncing
    // keeps typing responsive; render() applies the same preparation defensively, so an immediate
    // print can never use a shorter box than the editor has had time to commit.
    LaunchedEffect(session.document.elements, session.document.paper, variableRow, variableViewRows) {
        delay(70)
        val source = session.document
        val variables = variableViewRows.getOrNull(variableRow).orEmpty()
        val prepared = withContext(Dispatchers.Default) {
            LabelRenderer.prepareDocument(context, source.resolveVariables(variables))
        }
        val measuredHeights = prepared.elements.associate { it.id to it.height }
        val fittedElements = source.elements.map { element ->
            val measured = measuredHeights[element.id] ?: element.height
            if (measured > element.height) element.copy(height = measured) else element
        }
        if (session.document == source && fittedElements != source.elements) {
            session.setDocument(source.copy(elements = fittedElements), recordUndo = false)
        }
    }

    // A render already in progress is allowed to finish. Rapid gesture updates are conflated to
    // the newest document instead of cancelling and restarting the bitmap job on every pointer event.
    LaunchedEffect(session, variableData, variableFilter, variableSortField, variableSortAscending) {
        snapshotFlow {
            val variables = variableViewRows.getOrNull(variableRow).orEmpty()
            session.document.resolveVariables(variables)
        }
            .buffer(Channel.CONFLATED)
            .collect { snapshot ->
                rendering = true
                renderError = null
                var created: RenderedLabel? = null
                try {
                    created = withContext(Dispatchers.Default) { LabelRenderer.render(context, snapshot) }
                    coroutineContext.ensureActive()
                    rendered = created
                    created = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    renderError = error.message ?: "预览渲染失败"
                } finally {
                    created?.bitmap?.takeIf { !it.isRecycled }?.recycle()
                    rendering = false
                }
            }
    }
    // Compose owns exactly the bitmap currently displayed. Recycling from a timed coroutine could
    // race a delayed draw pass and crash Canvas with "trying to use a recycled bitmap".
    DisposableEffect(rendered) {
        val owned = rendered
        onDispose { owned?.bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    val currentVariables = variableViewRows.getOrNull(variableRow).orEmpty()
    val previewDocument = session.document.resolveVariables(currentVariables)

    fun fitSelectedContent() {
        val selected = session.selected?.takeIf { !it.locked } ?: return
        workScope.launch {
            val fitted = when (selected.kind) {
                ElementKind.IMAGE -> {
                    val dimensions = withContext(Dispatchers.IO) {
                        ImageLoader.dimensions(context, selected.imageUri)
                    } ?: run {
                        snackbar.showSnackbar("无法读取原图尺寸，请重新选择图片")
                        return@launch
                    }
                    fitImageToSource(selected.copy(imageFit = ImageFit.FIT), dimensions, session.document)
                }
                ElementKind.TEXT, ElementKind.DATE_TIME, ElementKind.SEQUENCE -> {
                    val content = withContext(Dispatchers.Default) {
                        LabelRenderer.measureTextContent(context, selected, selected.runtimeText())
                    }
                    fitTextToContent(selected, content, session.document)
                }
                else -> return@launch
            }
            if (session.document.elements.any { it.id == selected.id }) {
                session.update(fitted)
                snackbar.showSnackbar("蓝色选框已贴合实际内容")
            }
        }
    }

    fun addFittedTypography(element: LabelElement) {
        workScope.launch {
            val content = withContext(Dispatchers.Default) {
                LabelRenderer.measureTextContent(context, element, element.runtimeText())
            }
            session.add(fitTextToContent(element, content, session.document))
        }
    }

    Scaffold(
        // Apply IME insets to the whole layout so the dock sits immediately above the keyboard and
        // the content receives the remaining height instead of leaving a second keyboard-sized gap.
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(session.document.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${pretty(session.document.paper.contentWidthMm)} mm · ${session.document.paper.dpi} dpi",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = session::undo, enabled = session.canUndo) { Icon(Icons.AutoMirrored.Rounded.Undo, "撤销") }
                    IconButton(onClick = session::redo, enabled = session.canRedo) { Icon(Icons.AutoMirrored.Rounded.Redo, "重做") }
                    IconButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, session.document.title)
                            putExtra(Intent.EXTRA_TEXT, store.exportDocument(session.document))
                        }
                context.startActivity(Intent.createChooser(share, "导出${ProductIdentity.NAME}模板"))
                    }) { Icon(Icons.Rounded.Share, "导出") }
                    IconButton(onClick = { showSave = true }) { Icon(Icons.Rounded.Save, "保存") }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            EditorDock(
                            onAddText = { addFittedTypography(EditorFactories.textElement(y = nextY(session.document))) },
                onAddImage = { imagePicker.launch(arrayOf("image/*")) },
                onAddQr = { session.add(EditorFactories.barcodeElement(BarcodeType.QR_CODE, y = nextY(session.document))) },
                onAddBarcode = { session.add(EditorFactories.barcodeElement(BarcodeType.CODE_128, y = nextY(session.document))) },
                onAddShape = { session.add(EditorFactories.shapeElement(ShapeKind.ROUNDED_RECTANGLE, y = nextY(session.document))) },
                onAddTable = { session.add(EditorFactories.tableElement(y = nextY(session.document))) },
                            onAddDate = { addFittedTypography(EditorFactories.dateElement(y = nextY(session.document))) },
                            onAddSequence = { addFittedTypography(EditorFactories.sequenceElement(y = nextY(session.document))) },
                onScanCode = { codeScanner.launch(arrayOf("image/*")) },
                onCapturePhoto = {
                    runCatching {
                        CapturedMediaStore.createImageUri(context).also { uri ->
                            pendingPhotoUriText = uri.toString()
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                photoCapture.launch(uri)
                            } else {
                                launchPhotoAfterPermission = true
                                photoCameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }.onFailure { error ->
                        pendingPhotoUriText?.let(Uri::parse)?.let { CapturedMediaStore.deleteIfEmpty(context, it) }
                        pendingPhotoUriText = null
                        launchPhotoAfterPermission = false
                        workScope.launch { snackbar.showSnackbar("无法启动相机：${error.message ?: "相机不可用"}") }
                    }
                },
                onLiveText = { liveCameraMode = LiveCameraMode.OCR },
                onScanTemplate = { liveCameraMode = LiveCameraMode.TEMPLATE },
                onScanText = { textScanner.launch(arrayOf("image/*")) },
                onLiveCode = { liveCameraMode = LiveCameraMode.CODE },
                onImport = { documentImporter.launch(arrayOf("application/json", "text/plain")) },
                onImportDocument = {
                    printableDocumentImporter.launch(
                        arrayOf(
                            "application/pdf",
                            "text/plain",
                            "text/markdown",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/msword",
                            "application/vnd.ms-powerpoint",
                            "application/x-wps",
                            "application/x-dps",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                        ),
                    )
                },
                onVariableData = {
                    if (variableData == null) variableDataImporter.launch(
                        arrayOf(
                            "text/csv",
                            "text/tab-separated-values",
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "application/octet-stream",
                            "text/plain",
                        ),
                    ) else showVariableData = true
                },
                onProducts = { showProductLibrary = true },
                onDraw = { showDrawing = true },
                onSelectAll = session::selectAll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (rendered == null) return@ExtendedFloatingActionButton
                    if (documentBatch.isNotEmpty()) {
                        val firstSelected = selectedDocumentPageIndices.minOrNull()
                        if (firstSelected == null) {
                            workScope.launch { snackbar.showSnackbar("请至少选择一页再进入最终预览") }
                            return@ExtendedFloatingActionButton
                        }
                        if (activeDocumentPageIndex != firstSelected) openDocumentPage(firstSelected)
                    }
                    showPreview = true
                },
                icon = { Icon(Icons.Rounded.Print, null) },
                text = { Text("最终预览") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(innerPadding).background(MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            val previewInitialHeight = (maxHeight * 0.38f).coerceIn(170.dp, 320.dp)
            val previewMaxHeight = (maxHeight * 0.64f).coerceIn(240.dp, 520.dp)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (printerState.connected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(9.dp),
                    ) {}
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (printerState.connected) "已连接 ${printerState.deviceName}" else "未连接打印机 · 可继续离线编辑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${"%.1f".format(session.document.paper.dotsToMm(session.document.outputHeightDots()))} mm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    variableData?.let { table ->
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            onClick = { showVariableData = true },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                "数据 ${if (variableViewRows.isEmpty()) 0 else variableRow + 1}/${variableViewRows.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (documentBatch.size > 1) {
                        Spacer(Modifier.width(7.dp))
                        Surface(
                            onClick = { showDocumentPages = !showDocumentPages },
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                "文档 ${activeDocumentPageIndex + 1}/${documentBatch.size}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    if (scanningText) {
                        Spacer(Modifier.width(7.dp))
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(5.dp))
                        Text("本地识字中", style = MaterialTheme.typography.labelSmall)
                    }
                }

                AnimatedVisibility(
                    visible = documentBatch.size > 1 && showDocumentPages,
                    enter = fadeIn(tween(180)) + expandVertically(tween(260, easing = FastOutSlowInEasing)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(220, easing = FastOutSlowInEasing)),
                ) {
                    DocumentPagePanel(
                        pageCount = documentBatch.size,
                        activeIndex = activeDocumentPageIndex,
                        selectedIndices = selectedDocumentPageIndices,
                        onOpenPage = ::openDocumentPage,
                        onTogglePage = { index ->
                            selectedDocumentPageIndices = if (index in selectedDocumentPageIndices) {
                                selectedDocumentPageIndices - index
                            } else selectedDocumentPageIndices + index
                        },
                        onSelectAll = { selectedDocumentPageIndices = documentBatch.indices.toSet() },
                        onClearSelection = { selectedDocumentPageIndices = emptySet() },
                    )
                }

                StickyEditorPreview(
                    session = session,
                    rendered = rendered,
                    renderError = renderError,
                    rendering = rendering,
                    initialHeight = previewInitialHeight,
                    maxHeight = previewMaxHeight,
                )

                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (session.selectedElements.size > 1) {
                        val groupIds = session.selectedElements.map { it.groupId }.filter { it.isNotBlank() }.distinct()
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.extraLarge,
                            shadowElevation = 1.dp,
                        ) {
                            MultiSelectionPanel(
                                count = session.selectedElements.size,
                                persistentlyGrouped = groupIds.size == 1 && session.selectedElements.all { it.groupId == groupIds.single() },
                                onAlign = session::alignSelected,
                                onDistributeHorizontal = { session.distributeSelected(horizontal = true) },
                                onDistributeVertical = { session.distributeSelected(horizontal = false) },
                                onRotateStart = { session.beginTransform() },
                                onRotateBy = session::rotateSelectedBy,
                                onRotateEnd = session::endTransform,
                                onGroup = session::groupSelected,
                                onUngroup = session::ungroupSelected,
                                onDuplicate = session::duplicateSelected,
                                onDelete = session::deleteSelected,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else session.selected?.let { element ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.extraLarge,
                            shadowElevation = 1.dp,
                        ) {
                            ElementPropertiesPanel(
                                element = element,
                                paper = session.document.paper,
                                onUpdate = session::update,
                                onScaleStart = { session.beginTransform(element.id) },
                                onScaleToWidthDots = session::scaleSelectedToWidth,
                                onScaleTextToFontSize = session::scaleSelectedTypographyToFontSize,
                                onScaleEnd = session::endTransform,
                                onFitContent = ::fitSelectedContent,
                                onAlignOnCanvas = session::alignSelected,
                                onReplaceImage = { imagePicker.launch(arrayOf("image/*")) },
                                fontOptions = fontOptions,
                                onImportFont = {
                                    fontImporter.launch(
                                        arrayOf(
                                            "font/ttf",
                                            "font/otf",
                                            "application/x-font-ttf",
                                            "application/x-font-opentype",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                                onDelete = session::deleteSelected,
                                onDuplicate = session::duplicateSelected,
                                onBringForward = session::bringForward,
                                onSendBackward = session::sendBackward,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } ?: Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Text(
                            "点击元素编辑属性；长按元素可加入或移出多选。拖动时会吸附纸边、中心线和其他元素。",
                            modifier = Modifier.padding(18.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(92.dp))
                }
            }
        }
    }

    if (showPreview) rendered?.let { preview ->
        val selectedBatchRows = variableRowsIn(variableViewRows, variableBatchRange)
        val pageDocuments = selectedDocumentPages(currentDocumentPages(), selectedDocumentPageIndices)
        val printDocument = pageDocuments.firstOrNull() ?: previewDocument
        PrintPreviewSheet(
            document = printDocument,
            rendered = preview,
            batchSourceDocument = session.document.takeIf { variableData != null && documentBatch.isEmpty() },
            batchRows = selectedBatchRows,
            batchDocuments = pageDocuments,
            printer = printer,
            store = store,
            onConnect = { showPreview = false; showDevices = true },
            onAlignmentChange = session::setHorizontalAnchor,
            onDismiss = { showPreview = false },
        )
    }
    if (showSave) SaveTemplateDialog(
        document = session.document,
        onDismiss = { showSave = false },
        onSave = { title, category ->
            val saved = session.document.copy(
                id = if (session.document.builtIn) UUID.randomUUID().toString() else session.document.id,
                title = title,
                category = category,
                builtIn = false,
            )
            session.setDocument(saved)
            store.saveTemplate(saved, title)
            showSave = false
        },
    )
    if (showDevices) DeviceSheet(printer) { showDevices = false }
    if (showDrawing) DrawingSheet(
        onSave = { points ->
            session.add(EditorFactories.drawingElement(points, y = nextY(session.document)))
            showDrawing = false
        },
        onDismiss = { showDrawing = false },
    )
    if (showVariableData) variableData?.let { table ->
        VariableDataSheet(
            table = table,
            viewRows = variableViewRows,
            sheets = variableWorkbook?.sheets.orEmpty(),
            currentRow = variableRow,
            batchRange = normalizeVariableRange(variableViewRows.size, variableBatchRange),
            selected = session.selected,
            boundFields = session.document.variableFields(),
            filterQuery = variableFilter,
            sortField = variableSortField,
            sortAscending = variableSortAscending,
            onFilterQueryChange = { query ->
                variableFilter = query
                val nextRows = queryVariableRows(table, query, variableSortField, variableSortAscending)
                variableRow = 0
                variableBatchRange = normalizeVariableRange(nextRows.size, 0..nextRows.lastIndex)
            },
            onSortChange = { field, ascending ->
                variableSortField = field
                variableSortAscending = ascending
                val nextRows = queryVariableRows(table, variableFilter, field, ascending)
                variableRow = 0
                variableBatchRange = normalizeVariableRange(nextRows.size, 0..nextRows.lastIndex)
            },
            onRowChange = { variableRow = if (variableViewRows.isEmpty()) 0 else it.coerceIn(0, variableViewRows.lastIndex) },
            onBatchRangeChange = { requested -> variableBatchRange = normalizeVariableRange(variableViewRows.size, requested) },
            onSheetChange = { selectedSheet ->
                variableData = selectedSheet
                variableRow = 0
                variableBatchRange = 0..selectedSheet.rows.lastIndex
                variableFilter = ""
                variableSortField = null
                variableSortAscending = true
            },
            onInsertField = { header ->
                val token = "{{$header}}"
                when (val selected = session.selected) {
                    null -> addFittedTypography(EditorFactories.textElement(y = nextY(session.document)).copy(text = token))
                    else -> when (selected.kind) {
                        ElementKind.TEXT -> session.update(selected.copy(text = appendVariable(selected.text, token)))
                        ElementKind.BARCODE -> session.update(selected.copy(barcodeContent = token))
                        ElementKind.TABLE -> session.update(selected.copy(tableData = appendVariable(selected.tableData, token)))
                        else -> addFittedTypography(EditorFactories.textElement(y = nextY(session.document)).copy(text = token))
                    }
                }
            },
            onReplaceFile = {
                showVariableData = false
                variableDataImporter.launch(
                    arrayOf(
                        "text/csv",
                        "text/tab-separated-values",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "application/octet-stream",
                        "text/plain",
                    ),
                )
            },
            onClear = {
                variableWorkbook = null
                variableData = null
                variableRow = 0
                variableBatchRange = 0..0
                variableFilter = ""
                variableSortField = null
                variableSortAscending = true
                showVariableData = false
            },
            onDismiss = { showVariableData = false },
        )
    }
    if (showProductLibrary) {
        ProductLibrarySheet(
            store = store.products,
            onInsert = { product ->
                val boundFields = session.document.variableFields()
                if (boundFields.any { it in product.variables().keys }) {
                    session.setDocument(product.applyTo(session.document))
                } else {
                    session.addAll(product.toEditableElements(session.document.paper, nextY(session.document)))
                }
                showProductLibrary = false
            },
            onImport = {
                productDataImporter.launch(
                    arrayOf(
                        "text/csv",
                        "text/tab-separated-values",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "application/vnd.ms-excel",
                        "application/octet-stream",
                        "text/plain",
                    ),
                )
            },
            onMessage = snackbar::showSnackbar,
            onDismiss = { showProductLibrary = false },
        )
    }
    pendingPhotoCropUriText?.let { uriText ->
        val capturedUri = Uri.parse(uriText)
        PhotoCropSheet(
            uri = capturedUri,
            processing = processingPhotoCrop,
            onUseAll = {
                if (!processingPhotoCrop) {
                    pendingPhotoCropUriText = null
                    addCapturedPhoto(capturedUri, "整张照片已加入画布")
                }
            },
            onUseCrop = { selection ->
                if (!processingPhotoCrop) {
                    processingPhotoCrop = true
                    workScope.launch {
                        val cropped = withContext(Dispatchers.IO) {
                            CapturedPhotoCropper.cropFreehand(context, capturedUri, selection)
                        }
                        cropped.onSuccess { croppedUri ->
                            CapturedMediaStore.delete(context, capturedUri)
                            pendingPhotoCropUriText = null
                            addCapturedPhoto(croppedUri, "手绘圈选区域已加入画布")
                        }.onFailure { error ->
                            snackbar.showSnackbar("照片裁切失败：${error.message ?: "无法读取照片"}")
                        }
                        processingPhotoCrop = false
                    }
                }
            },
            onDismiss = {
                if (!processingPhotoCrop) {
                    CapturedMediaStore.delete(context, capturedUri)
                    pendingPhotoCropUriText = null
                }
            },
        )
    }
    liveCameraMode?.let { mode ->
        LiveCameraStudio(
            mode = mode,
            onDismiss = { liveCameraMode = null },
            onTextAccepted = { text ->
                val lines = text.lineSequence().count().coerceAtLeast(1)
                val paper = session.document.paper
                session.add(
                    EditorFactories.textElement(y = nextY(session.document)).copy(
                        text = text,
                        width = paper.contentWidthDots(),
                        height = (lines * 34 + 12).coerceAtLeast(40),
                        fontSizeDots = 26f,
                    ),
                )
                liveCameraMode = null
            },
            onTemplateAccepted = { scan ->
                liveCameraMode = null
                ocrScan = store.ocrQuality.applyValidatedCorrections(scan)
            },
            onCodeAccepted = { code ->
                routeDecodedCode(code)
                liveCameraMode = null
            },
        )
    }
    pendingDecodedCode?.let { decoded ->
        val product = store.products.findByBarcode(decoded.content)
        CodeMatchSheet(
            decoded = decoded,
            product = product,
            matches = pendingCodeMatches,
            onUseTemplate = { match ->
                clearDocumentPages()
                variableWorkbook = null
                variableData = null
                session.setDocument(
                    CodeTemplateMatcher.apply(match.document, decoded, product)
                        .withDeviceProfile(store.paper.value),
                )
                store.templateUsage.recordOpened(match.document.id)
                pendingDecodedCode = null
                pendingCodeMatches = emptyList()
            },
            onInsertProduct = product?.let {
                { selected ->
                    session.addAll(selected.toEditableElements(session.document.paper, nextY(session.document)))
                    pendingDecodedCode = null
                    pendingCodeMatches = emptyList()
                }
            },
            onInsertCode = {
                insertDecodedCode(decoded)
                pendingDecodedCode = null
                pendingCodeMatches = emptyList()
            },
            onDismiss = {
                pendingDecodedCode = null
                pendingCodeMatches = emptyList()
            },
        )
    }
    ocrScan?.let { scan ->
        OcrImportSheet(
            scan = scan,
            paperMode = session.document.paper.mode,
            qualityStore = store.ocrQuality,
            onValidated = { original, corrected ->
                workScope.launch { store.ocrQuality.recordValidated(original, corrected) }
            },
            onReplace = { selectedScan ->
                session.replaceElements(
                    selectedScan.toEditableElements(
                        paper = session.document.paper,
                        startYDots = 0,
                        fitInsideFixedLabel = session.document.paper.mode == com.qrint.studio.model.PaperMode.LABEL,
                    ),
                )
                ocrScan = null
            },
            onAppend = { selectedScan ->
                session.addAll(
                    selectedScan.toEditableElements(
                        paper = session.document.paper,
                        startYDots = nextY(session.document),
                        fitInsideFixedLabel = session.document.paper.mode == com.qrint.studio.model.PaperMode.LABEL,
                    ),
                )
                ocrScan = null
            },
            onDismiss = { ocrScan = null },
        )
    }
}

@Composable
private fun StickyEditorPreview(
    session: EditorSession,
    rendered: RenderedLabel?,
    renderError: String?,
    rendering: Boolean,
    initialHeight: Dp,
    maxHeight: Dp,
) {
    val previewScroll = rememberScrollState()
    val density = LocalDensity.current
    var collapsed by rememberSaveable(session.document.id) { mutableStateOf(false) }
    var viewportHeightDp by rememberSaveable(session.document.id) { mutableFloatStateOf(initialHeight.value) }
    val safeMaxHeight = maxHeight.value.coerceAtLeast(112f)
    val viewportHeight = viewportHeightDp.coerceIn(112f, safeMaxHeight).dp
    Surface(
        modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 3.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (collapsed) "实时画布 · 已收起" else "实时画布", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                if (!collapsed && previewScroll.maxValue > 0) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Text("画布内上下滑动", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { collapsed = !collapsed }) {
                    Text(if (collapsed) "展开画布" else "收起")
                }
            }
            AnimatedVisibility(
                visible = !collapsed,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Top,
                ) + fadeIn(animationSpec = tween(durationMillis = 240, delayMillis = 70)),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Top,
                ) + fadeOut(animationSpec = tween(durationMillis = 180)),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().height(viewportHeight)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            Box(
                                Modifier.fillMaxSize()
                                    .clip(MaterialTheme.shapes.large).verticalScroll(previewScroll).padding(12.dp),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                when {
                                    rendered != null -> InteractiveLabelCanvas(
                                        document = session.document,
                                        rendered = rendered,
                                        selectedId = session.selectedId,
                                        selectedIds = session.selectedIds,
                                        snapGuides = session.snapGuides,
                                        onSelect = session::select,
                                        onToggleSelection = session::toggleSelection,
                                        onDoubleTap = session::select,
                                        onTransformStart = session::beginTransform,
                                        onTransform = session::transformSelected,
                                        onResize = session::resizeSelected,
                                        onTransformEnd = session::endTransform,
                                    )
                                    renderError != null -> Text(renderError, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(40.dp))
                                    else -> CircularProgressIndicator(Modifier.padding(40.dp))
                                }
                            }
                            if (rendering && rendered != null) {
                                CircularProgressIndicator(Modifier.size(21.dp).align(Alignment.TopEnd), strokeWidth = 2.dp)
                            }
                        }
                        CanvasSideScrollStrip(previewScroll, Modifier.width(24.dp).fillMaxHeight())
                    }
                    Column(
                        Modifier.fillMaxWidth().height(30.dp)
                            .pointerInput(safeMaxHeight, density.density) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    viewportHeightDp = (viewportHeightDp + dragAmount / density.density)
                                        .coerceIn(112f, safeMaxHeight)
                                }
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        HorizontalDivider(Modifier.width(54.dp), thickness = 4.dp, color = MaterialTheme.colorScheme.outline)
                        Text("上下拖动调整", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** External scrollbar: it never covers printable content and follows preview collapse/expansion. */
@Composable
private fun CanvasSideScrollStrip(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.pointerInput(scroll.maxValue) {
            detectVerticalDragGestures { change, dragAmount ->
                change.consume()
                val trackPixels = size.height.coerceAtLeast(1)
                val contentRatio = (scroll.maxValue + trackPixels).toFloat() / trackPixels
                scroll.dispatchRawDelta(dragAmount * contentRatio)
            }
        },
        contentAlignment = Alignment.TopCenter,
    ) {
        val trackHeight = maxHeight
        val thumbHeight = if (scroll.maxValue <= 0) trackHeight * 0.56f else (trackHeight * 0.22f).coerceAtLeast(36.dp)
        val travel = (trackHeight - thumbHeight).coerceAtLeast(0.dp)
        val fraction = if (scroll.maxValue <= 0) 0f else scroll.value.toFloat() / scroll.maxValue
        Surface(
            modifier = Modifier.width(7.dp).fillMaxHeight(),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
        Surface(
            modifier = Modifier.width(7.dp).height(thumbHeight).offset(y = travel * fraction),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (scroll.maxValue > 0) 0.86f else 0.32f),
        ) {}
    }
}

@Composable
private fun EditorDock(
    onAddText: () -> Unit,
    onAddImage: () -> Unit,
    onAddQr: () -> Unit,
    onAddBarcode: () -> Unit,
    onAddShape: () -> Unit,
    onAddTable: () -> Unit,
    onAddDate: () -> Unit,
    onAddSequence: () -> Unit,
    onScanCode: () -> Unit,
    onCapturePhoto: () -> Unit,
    onLiveText: () -> Unit,
    onScanTemplate: () -> Unit,
    onScanText: () -> Unit,
    onLiveCode: () -> Unit,
    onImport: () -> Unit,
    onImportDocument: () -> Unit,
    onVariableData: () -> Unit,
    onProducts: () -> Unit,
    onDraw: () -> Unit,
    onSelectAll: () -> Unit,
) {
    val actions = listOf(
        DockAction("文字", Icons.Rounded.TextFields, onAddText),
        DockAction("图片", Icons.Rounded.AddPhotoAlternate, onAddImage),
        DockAction("二维码", Icons.Rounded.QrCode2, onAddQr),
        DockAction("一维码", Icons.Rounded.ViewWeek, onAddBarcode),
        DockAction("形状", Icons.Rounded.ShapeLine, onAddShape),
        DockAction("表格", Icons.Rounded.TableRows, onAddTable),
        DockAction("日期", Icons.Rounded.CalendarMonth, onAddDate),
        DockAction("流水号", Icons.Rounded.DataObject, onAddSequence),
        DockAction("相册识码", Icons.Rounded.QrCodeScanner, onScanCode),
        DockAction("拍照打印", Icons.Rounded.CameraAlt, onCapturePhoto),
        DockAction("拍照扫描", Icons.Rounded.DocumentScanner, onLiveText),
        DockAction("拍照建模板", Icons.Rounded.DocumentScanner, onScanTemplate),
        DockAction("相册识字", Icons.Rounded.DocumentScanner, onScanText),
        DockAction("实时识码", Icons.Rounded.QrCodeScanner, onLiveCode),
        DockAction("数据批打", Icons.Rounded.DataObject, onVariableData),
        DockAction("商品资料", Icons.Rounded.Inventory2, onProducts),
        DockAction("文档直印", Icons.Rounded.FileOpen, onImportDocument),
        DockAction("导入模板", Icons.Rounded.FileOpen, onImport),
        DockAction("手绘", Icons.Rounded.Draw, onDraw),
        DockAction("全选", Icons.Rounded.SelectAll, onSelectAll),
    )
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 10.dp) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(actions, key = { it.label }) { action ->
                DockButton(action.label, action.icon, action.onClick)
            }
            item { Spacer(Modifier.width(112.dp)) }
        }
    }
}

private data class DockAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
private fun DockButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.width(88.dp).heightIn(min = 78.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(lineHeight = 16.sp),
                maxLines = 2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveTemplateDialog(document: LabelDocument, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by remember { mutableStateOf(document.title) }
    var category by remember { mutableStateOf(document.category) }
    val categories = IndustryCatalog.categories.map { it.name }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存模板") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("模板名称") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { item ->
                        FilterChip(selected = category == item, onClick = { category = item }, label = { Text(item) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(title, category) }) { Text("保存") } },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun nextY(document: LabelDocument): Int {
    val bottom = document.elements.maxOfOrNull { it.visualBottom() } ?: document.paper.mmToDots(document.paper.topPaddingMm)
    val proposed = bottom + 10
    return if (document.paper.mode == com.qrint.studio.model.PaperMode.LABEL) {
        proposed.coerceAtMost(document.paper.fixedHeightDots() - 40)
    } else proposed
}

private fun fitImageToSource(
    element: LabelElement,
    source: ImageLoader.SourceDimensions,
    document: LabelDocument,
): LabelElement {
    val paper = document.paper
    val maximumWidth = paper.printableEndX() - paper.printableStartX()
    val maximumHeight = editorHeightLimit(document)
    var width = element.width.coerceAtMost(maximumWidth)
    var height = (width / source.aspectRatio).roundToInt().coerceAtLeast(1)
    if (height > maximumHeight) {
        height = maximumHeight
        width = (height * source.aspectRatio).roundToInt().coerceAtMost(maximumWidth)
    }
    return ElementSizingPolicy.fitToContent(
        element = element.copy(imageFit = ImageFit.FIT),
        contentWidthDots = width,
        contentHeightDots = height,
        contentStart = paper.printableStartX(),
        contentEnd = paper.printableEndX(),
        heightLimit = maximumHeight,
        horizontalBias = 0.5f,
        verticalBias = 0.5f,
    )
}

private fun fitTextToContent(
    element: LabelElement,
    content: LabelRenderer.ContentSize,
    document: LabelDocument,
): LabelElement {
    val horizontalBias = when (element.textAlignment) {
        TextAlignment.LEFT -> 0f
        TextAlignment.CENTER -> 0.5f
        TextAlignment.RIGHT -> 1f
    }
    return ElementSizingPolicy.fitToContent(
        element = element,
        contentWidthDots = content.width,
        contentHeightDots = content.height,
        contentStart = document.paper.printableStartX(),
        contentEnd = document.paper.printableEndX(),
        heightLimit = editorHeightLimit(document),
        horizontalBias = horizontalBias,
        verticalBias = 0f,
    )
}

private fun editorHeightLimit(document: LabelDocument): Int =
    if (document.paper.mode == com.qrint.studio.model.PaperMode.LABEL) document.paper.fixedHeightDots()
    else MAX_DOCUMENT_HEIGHT_DOTS

private fun pretty(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

private fun appendVariable(value: String, token: String): String = when {
    value.isBlank() || value == "双击编辑文字" -> token
    value.endsWith("\n") -> value + token
    else -> "$value $token"
}

private fun android.content.Context.sharedDisplayName(uri: Uri, fallback: String): String = runCatching {
    contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: fallback
