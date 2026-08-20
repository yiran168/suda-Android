package com.qrint.studio.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.view.MotionEvent
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qrint.studio.render.BarcodeDecoder
import com.qrint.studio.render.DecodedBarcode
import com.qrint.studio.render.OfflineTextScan
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay

enum class LiveCameraMode(
    val title: String,
    val hint: String,
    val acceptLabel: String,
) {
    OCR("拍照扫描文字", "调整蓝框并点按对焦，拍照后用 PP-OCRv6 Small 扫描", "插入识别文字"),
    TEMPLATE("拍照扫描建模板", "框选平整纸面，拍照后生成可编辑文字层", "生成可编辑模板"),
    CODE("实时相机识码", "框选编码；旋转/反色解码并经连续 4 帧一致校验", "插入识别结果"),
}

@Composable
fun LiveCameraStudio(
    mode: LiveCameraMode,
    onDismiss: () -> Unit,
    onTextAccepted: (String) -> Unit,
    onTemplateAccepted: (OfflineTextScan) -> Unit,
    onCodeAccepted: (DecodedBarcode) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraSessionActive = remember(mode) { AtomicBoolean(false) }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionGranted = it
    }
    var latestScan by remember(mode) { mutableStateOf<OfflineTextScan?>(null) }
    var latestCode by remember(mode) { mutableStateOf<DecodedBarcode?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var captureExecutor by remember { mutableStateOf<java.util.concurrent.ExecutorService?>(null) }
    var captureInProgress by remember(mode) { mutableStateOf(false) }
    var torchEnabled by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraGuidance by remember(mode) {
        mutableStateOf(
            if (mode == LiveCameraMode.CODE) "拖动蓝框边缘或四角调整识别范围"
            else "让文字占满蓝框，点按文字对焦后点击拍照扫描",
        )
    }
    var scanRegion by remember(mode) { mutableStateOf(defaultCameraScanRegion(mode)) }
    val scanRegionRef = remember(mode) { AtomicReference(scanRegion) }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    val previewTapHandler = rememberUpdatedState<(Float, Float) -> Unit> { x, y ->
        camera?.let { activeCamera ->
            if (requestCameraFocusAtView(activeCamera, previewView, x, y)) {
                cameraGuidance = "正在对焦并重新校验…"
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    DisposableEffect(permissionGranted, mode, lifecycleOwner, previewView) {
        if (!permissionGranted) return@DisposableEffect onDispose { }
        val analysisExecutor = if (mode == LiveCameraMode.CODE) {
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "lingyin-live-code").apply { isDaemon = true }
            }
        } else null
        val stillExecutor = if (mode != LiveCameraMode.CODE) {
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "lingyin-photo-ocr").apply { isDaemon = true }
            }
        } else null
        cameraSessionActive.set(true)
        captureExecutor = stillExecutor
        val active = AtomicBoolean(true)
        val analyzer = if (mode == LiveCameraMode.CODE) {
            LiveCodeAnalyzer(
                regionProvider = scanRegionRef::get,
                onCode = { code ->
                    mainExecutor.execute {
                        if (active.get()) {
                            latestCode = code
                            cameraError = null
                            cameraGuidance = "已通过连续 4 帧码值完全一致校验"
                        }
                    }
                },
                onGuidance = { message ->
                    mainExecutor.execute { if (active.get()) cameraGuidance = message }
                },
                onError = { error ->
                    mainExecutor.execute { if (active.get() && cameraError == null) cameraError = error }
                },
            )
        } else null
        var provider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (!active.get()) return@addListener
                runCatching {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val targetResolution = Size(1280, 960)
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                targetResolution,
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build()
                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    cameraProvider.unbindAll()
                    val boundCamera = if (mode == LiveCameraMode.CODE) {
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(resolutionSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(requireNotNull(analysisExecutor), requireNotNull(analyzer))
                            }
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } else {
                        val captureResolutionSelector = ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(2560, 1920),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build()
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setJpegQuality(95)
                            .setTargetRotation(previewView.display?.rotation ?: AndroidSurface.ROTATION_0)
                            .setResolutionSelector(captureResolutionSelector)
                            .build()
                        imageCapture = capture
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            capture,
                        )
                    }
                    boundCamera
                }.onSuccess { boundCamera ->
                    camera = boundCamera
                    cameraError = null
                }.onFailure { error -> cameraError = error.message ?: "无法启动相机" }
            },
            mainExecutor,
        )
        onDispose {
            active.set(false)
            cameraSessionActive.set(false)
            analyzer?.close()
            provider?.unbindAll()
            analysisExecutor?.shutdownNow()
            stillExecutor?.shutdownNow()
            captureExecutor = null
            imageCapture = null
            camera = null
        }
    }

    // Focus the centre of the editable region after camera bind and after every region change.
    // The user can additionally tap anywhere inside the blue region for a precise AF/AE/AWB point.
    LaunchedEffect(camera, scanRegion) {
        delay(220)
        val activeCamera = camera ?: return@LaunchedEffect
        requestCameraFocusInOverlay(
            camera = activeCamera,
            previewView = previewView,
            normalizedX = (scanRegion.left + scanRegion.right) / 2f,
            normalizedY = (scanRegion.top + scanRegion.bottom) / 2f,
        )
    }

    val statusMessage = when {
        cameraError != null -> cameraError.orEmpty()
        mode == LiveCameraMode.CODE && latestCode != null -> "已稳定识别 ${latestCode?.type?.label}"
        mode != LiveCameraMode.CODE && captureInProgress -> "正在处理高分辨率照片，请稍候…"
        mode != LiveCameraMode.CODE && latestScan?.lines?.isNotEmpty() == true ->
            "拍照扫描完成：${latestScan?.lines?.size} 行"
        else -> cameraGuidance
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF07090D)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(mode.title, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(mode.hint, color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(
                        enabled = camera?.cameraInfo?.hasFlashUnit() == true,
                        onClick = {
                            torchEnabled = !torchEnabled
                            camera?.cameraControl?.enableTorch(torchEnabled)
                        },
                    ) {
                        Icon(if (torchEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "补光灯", tint = Color.White)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                    if (permissionGranted) {
                        val guidanceSpace = 96.dp
                        val previewFrame = fitCameraPreview(
                            containerWidth = maxWidth.value,
                            containerHeight = maxHeight.value,
                            reservedBottom = guidanceSpace.value,
                        )
                        val previewHeight = previewFrame.height.dp
                        val previewWidth = previewFrame.width.dp
                        val previewModifier = Modifier
                            .width(previewWidth)
                            .height(previewHeight)
                            .align(Alignment.TopCenter)
                        AndroidView(
                            factory = {
                                previewView.apply {
                                    setOnTouchListener { touchedView, event ->
                                        if (event.action == MotionEvent.ACTION_UP) {
                                            touchedView.performClick()
                                            previewTapHandler.value(event.x, event.y)
                                        }
                                        true
                                    }
                                }
                            },
                            modifier = previewModifier,
                        )
                        Box(
                            previewModifier,
                        ) {
                            LiveScanRegionOverlay(
                                region = scanRegion,
                                onRegionChange = { updated ->
                                    scanRegion = updated
                                    scanRegionRef.set(updated)
                                    latestScan = null
                                    latestCode = null
                                    cameraGuidance = if (mode == LiveCameraMode.CODE) {
                                        "选区已调整 · 正在重新识码"
                                    } else {
                                        "选区已调整 · 点按文字对焦后点击拍照扫描"
                                    }
                                },
                                onFocusRequested = { normalizedX, normalizedY ->
                                    camera?.let { activeCamera ->
                                        if (requestCameraFocusInOverlay(activeCamera, previewView, normalizedX, normalizedY)) {
                                            cameraGuidance = "正在对焦…对焦稳定后点击拍照扫描"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        CameraGuidanceCapsule(
                            message = statusMessage,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    } else {
                        Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("需要相机权限才能实时识别", color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
                        }
                    }
                }
                LiveResultPanel(
                    mode = mode,
                    scan = latestScan,
                    code = latestCode,
                    captureInProgress = captureInProgress,
                    captureAvailable = imageCapture != null && captureExecutor != null,
                    statusMessage = statusMessage,
                    onCapture = {
                        val capture = imageCapture
                        val worker = captureExecutor
                        if (capture == null || worker == null || captureInProgress) return@LiveResultPanel
                        capture.targetRotation = previewView.display?.rotation ?: AndroidSurface.ROTATION_0
                        captureInProgress = true
                        latestScan = null
                        cameraError = null
                        cameraGuidance = "正在拍照并扫描蓝框内文字…"
                        CapturedPhotoOcr.capture(
                            context = context.applicationContext,
                            imageCapture = capture,
                            region = scanRegion,
                            worker = worker,
                            sourceTag = if (mode == LiveCameraMode.TEMPLATE) "template" else "text",
                        ) { result ->
                            mainExecutor.execute {
                                if (!cameraSessionActive.get()) return@execute
                                captureInProgress = false
                                result.onSuccess { scan ->
                                    latestScan = scan
                                    cameraError = null
                                    cameraGuidance = "拍照扫描完成，可确认使用或重新拍照"
                                }.onFailure { error ->
                                    latestScan = null
                                    cameraError = error.message ?: "拍照扫描失败"
                                    cameraGuidance = "请重新对焦后拍照"
                                }
                            }
                        }
                    },
                    onAccept = {
                        when (mode) {
                            LiveCameraMode.OCR -> latestScan?.plainText?.takeIf { it.isNotBlank() }?.let(onTextAccepted)
                            LiveCameraMode.TEMPLATE -> latestScan?.takeIf { it.lines.isNotEmpty() }?.let(onTemplateAccepted)
                            LiveCameraMode.CODE -> latestCode?.let(onCodeAccepted)
                        }
                    },
                )
            }
        }
    }
}

private fun requestCameraFocusInOverlay(
    camera: Camera,
    previewView: PreviewView,
    normalizedX: Float,
    normalizedY: Float,
): Boolean {
    if (previewView.width <= 0 || previewView.height <= 0) return false
    return requestCameraFocusAtView(
        camera = camera,
        previewView = previewView,
        x = normalizedX.coerceIn(0f, 1f) * previewView.width,
        y = normalizedY.coerceIn(0f, 1f) * previewView.height,
    )
}

@Composable
private fun CameraGuidanceCapsule(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CenterFocusStrong,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                message,
                modifier = Modifier.weight(1f).padding(vertical = 1.dp),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(
                    lineHeight = 22.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = true),
                ),
            )
        }
    }
}

private fun requestCameraFocusAtView(
    camera: Camera,
    previewView: PreviewView,
    x: Float,
    y: Float,
): Boolean {
    if (previewView.width <= 0 || previewView.height <= 0) return false
    return runCatching {
        val point = previewView.meteringPointFactory.createPoint(
            x.coerceIn(0f, previewView.width.toFloat()),
            y.coerceIn(0f, previewView.height.toFloat()),
            0.18f,
        )
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        val allMeters = FocusMeteringAction.Builder(point, flags)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
        val autoFocus = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        val supported = when {
            camera.cameraInfo.isFocusMeteringSupported(allMeters) -> allMeters
            camera.cameraInfo.isFocusMeteringSupported(autoFocus) -> autoFocus
            else -> return@runCatching false
        }
        camera.cameraControl.startFocusAndMetering(supported)
        true
    }.getOrDefault(false)
}

@Composable
private fun LiveResultPanel(
    mode: LiveCameraMode,
    scan: OfflineTextScan?,
    code: DecodedBarcode?,
    captureInProgress: Boolean,
    captureAvailable: Boolean,
    statusMessage: String,
    onCapture: () -> Unit,
    onAccept: () -> Unit,
) {
    val ready = if (mode == LiveCameraMode.CODE) code != null else scan?.lines?.isNotEmpty() == true
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.TextSnippet,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (ready) "高可信结果" else "识别状态",
                    modifier = Modifier.padding(top = 1.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        lineHeight = 22.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when (mode) {
                        LiveCameraMode.CODE -> code?.let { "${it.type.label} · ${it.content}" } ?: statusMessage
                        LiveCameraMode.TEMPLATE -> scan?.let { "检测到 ${it.lines.size} 个可编辑文字层" }
                            ?: statusMessage
                        LiveCameraMode.OCR -> scan?.plainText
                            ?: statusMessage
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall.copy(
                        lineHeight = 26.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = true),
                    ),
                    color = if (ready) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode != LiveCameraMode.CODE) {
                    Button(
                        onClick = onCapture,
                        enabled = captureAvailable && !captureInProgress,
                        modifier = Modifier
                            .widthIn(min = if (ready) 88.dp else 136.dp, max = if (ready) 104.dp else 168.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(if (captureInProgress) "扫描中" else if (ready) "重拍" else "拍照扫描", maxLines = 1)
                    }
                }
                if (ready) {
                    Button(
                        onClick = onAccept,
                        enabled = !captureInProgress,
                        modifier = Modifier.widthIn(min = 128.dp, max = 154.dp).heightIn(min = 48.dp),
                    ) {
                        Text(
                            mode.acceptLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium.copy(
                                lineHeight = 24.sp,
                                platformStyle = PlatformTextStyle(includeFontPadding = true),
                            ),
                        )
                    }
                }
            }
        }
    }
}

private class LiveCodeAnalyzer(
    private val regionProvider: () -> CameraScanRegion,
    private val onCode: (DecodedBarcode) -> Unit,
    private val onGuidance: (String) -> Unit,
    private val onError: (String) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val codeGate = StableValueGate<DecodedBarcode>(requiredMatches = 4)
    private var lastRegion: CameraScanRegion? = null
    private var acceptedCode: DecodedBarcode? = null
    private var lastGuidance: String? = null
    private var lastError: String? = null

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        if (closed.get()) {
            image.close()
            return
        }
        val region = regionProvider()
        if (region != lastRegion) {
            lastRegion = region
            codeGate.reset()
            acceptedCode = null
            lastGuidance = null
            lastError = null
        }
        try {
            val frame = image.toOrientedLuminance().cropTo(region)
            frame.measureQuality().guidance?.let { guidance ->
                reportGuidance(guidance)
                return
            }
            BarcodeDecoder.decodeLuminance(frame.bytes, frame.width, frame.height).onSuccess { value ->
                if (value == acceptedCode) return@onSuccess
                val stable = codeGate.offer(value)
                if (stable != null) {
                    acceptedCode = stable
                    lastGuidance = null
                    lastError = null
                    onCode(stable)
                } else {
                    reportGuidance("码值校验中 · 请保持蓝框内编码稳定")
                }
            }
        } catch (error: Throwable) {
            if (error !is com.google.zxing.NotFoundException) {
                val message = error.message ?: "识码失败"
                if (message != lastError) {
                    lastError = message
                    onError(message)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun reportGuidance(message: String) {
        if (message == lastGuidance) return
        lastGuidance = message
        onGuidance(message)
    }

    override fun close() {
        closed.set(true)
    }
}

@Composable
private fun LiveScanRegionOverlay(
    region: CameraScanRegion,
    onRegionChange: (CameraScanRegion) -> Unit,
    onFocusRequested: (normalizedX: Float, normalizedY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val touchRadiusPx = with(density) { 28.dp.toPx() }
    val regionRef = remember { AtomicReference(region) }
    val currentOnRegionChange by rememberUpdatedState(onRegionChange)
    val currentOnFocusRequested by rememberUpdatedState(onFocusRequested)
    var activeHandle by remember { mutableStateOf<CameraScanHandle?>(null) }
    SideEffect { regionRef.set(region) }

    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { position ->
                currentOnFocusRequested(
                    position.x / size.width.coerceAtLeast(1),
                    position.y / size.height.coerceAtLeast(1),
                )
            }
        }.pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { position ->
                    val current = regionRef.get()
                    activeHandle = hitCameraScanRegion(
                        region = current,
                        x = position.x / size.width.coerceAtLeast(1),
                        y = position.y / size.height.coerceAtLeast(1),
                        toleranceX = touchRadiusPx / size.width.coerceAtLeast(1),
                        toleranceY = touchRadiusPx / size.height.coerceAtLeast(1),
                    )
                },
                onDragCancel = { activeHandle = null },
                onDragEnd = { activeHandle = null },
                onDrag = { change, dragAmount ->
                    val handle = activeHandle ?: return@detectDragGestures
                    change.consume()
                    val updated = transformCameraScanRegion(
                        region = regionRef.get(),
                        handle = handle,
                        deltaX = dragAmount.x / size.width.coerceAtLeast(1),
                        deltaY = dragAmount.y / size.height.coerceAtLeast(1),
                    )
                    regionRef.set(updated)
                    currentOnRegionChange(updated)
                },
            )
        },
    ) {
        val left = region.left * size.width
        val top = region.top * size.height
        val right = region.right * size.width
        val bottom = region.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.34f)
        drawRect(shade, size = ComposeSize(size.width, top))
        drawRect(shade, topLeft = Offset(0f, bottom), size = ComposeSize(size.width, size.height - bottom))
        drawRect(shade, topLeft = Offset(0f, top), size = ComposeSize(left, bottom - top))
        drawRect(shade, topLeft = Offset(right, top), size = ComposeSize(size.width - right, bottom - top))
        drawRoundRect(
            color = accent,
            topLeft = Offset(left, top),
            size = ComposeSize(right - left, bottom - top),
            cornerRadius = CornerRadius(22.dp.toPx()),
            style = Stroke(
                width = 2.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 7.dp.toPx())),
            ),
        )
        val handles = listOf(
            Offset(left, top),
            Offset((left + right) / 2f, top),
            Offset(right, top),
            Offset(left, (top + bottom) / 2f),
            Offset(right, (top + bottom) / 2f),
            Offset(left, bottom),
            Offset((left + right) / 2f, bottom),
            Offset(right, bottom),
        )
        handles.forEach { point ->
            drawCircle(Color.White, radius = 7.dp.toPx(), center = point)
            drawCircle(accent, radius = 5.dp.toPx(), center = point)
        }
    }
}

internal data class LuminanceFrame(val bytes: ByteArray, val width: Int, val height: Int)

private fun ImageProxy.toOrientedLuminance(): LuminanceFrame {
    val plane = planes.first()
    val buffer = plane.buffer.duplicate()
    val compact = ByteArray(width * height)
    val baseOffset = buffer.position()
    if (plane.pixelStride == 1) {
        for (y in 0 until height) {
            buffer.position(baseOffset + y * plane.rowStride)
            buffer.get(compact, y * width, width)
        }
    } else {
        for (y in 0 until height) {
            val row = baseOffset + y * plane.rowStride
            for (x in 0 until width) compact[y * width + x] = buffer.get(row + x * plane.pixelStride)
        }
    }
    return rotateLuminance(compact, width, height, imageInfo.rotationDegrees)
}

internal fun rotateLuminance(bytes: ByteArray, width: Int, height: Int, rotationDegrees: Int): LuminanceFrame {
    require(bytes.size >= width * height && width > 0 && height > 0)
    val rotation = ((rotationDegrees % 360) + 360) % 360
    if (rotation == 0) return LuminanceFrame(bytes, width, height)
    val outputWidth = if (rotation == 180) width else height
    val outputHeight = if (rotation == 180) height else width
    val output = ByteArray(width * height)
    when (rotation) {
        90 -> for (y in 0 until height) for (x in 0 until width) {
            output[x * outputWidth + (height - 1 - y)] = bytes[y * width + x]
        }
        180 -> for (y in 0 until height) for (x in 0 until width) {
            output[(height - 1 - y) * outputWidth + (width - 1 - x)] = bytes[y * width + x]
        }
        270 -> for (y in 0 until height) for (x in 0 until width) {
            output[(width - 1 - x) * outputWidth + y] = bytes[y * width + x]
        }
        else -> error("相机旋转角度必须为 0/90/180/270")
    }
    return LuminanceFrame(output, outputWidth, outputHeight)
}

internal class StableValueGate<T>(private val requiredMatches: Int) {
    private var last: T? = null
    private var matches = 0
    private var emitted: T? = null

    init { require(requiredMatches >= 1) }

    fun offer(value: T): T? {
        if (value == last) matches++ else {
            last = value
            matches = 1
            emitted = null
        }
        return value.takeIf { matches >= requiredMatches && emitted != value }?.also { emitted = it }
    }

    fun reset() {
        last = null
        matches = 0
        emitted = null
    }
}
