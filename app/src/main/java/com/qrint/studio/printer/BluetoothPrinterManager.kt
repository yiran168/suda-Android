package com.qrint.studio.printer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.qrint.studio.model.LabelDocument
import com.qrint.studio.data.RuntimeLogStore
import com.qrint.studio.model.ElementKind
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.model.PrintProtocol
import com.qrint.studio.render.LabelRenderer
import com.qrint.studio.render.RasterData
import com.qrint.studio.render.RasterEncoder
import com.qrint.studio.render.RenderedLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class BluetoothPrinterManager(
    private val context: Context,
    private val logs: RuntimeLogStore,
) {
    companion object {
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val PREFS = "printer_connection"
        private const val LAST_ADDRESS = "last_address"
        private const val AUTO_RECONNECT = "auto_reconnect"
        private const val QUERY_TIMEOUT_MS = 1_500L
        private const val ACK_TIMEOUT_MS = 120_000L
        private const val DISCOVERY_TIMEOUT_MS = 20_000L
        private const val FAULT_CONFIRM_DELAY_MS = 250L
        private const val INTER_SESSION_SETTLE_MS = 300L
        private const val READY_POLL_DELAY_MS = 150L
        private const val READY_TIMEOUT_MS = 30_000L
        private const val COOLDOWN_POLL_DELAY_MS = 2_000L
        private const val BLIND_COOLDOWN_DELAY_MS = 20_000L
        /** Drain late unsolicited frames before opening the next raster transaction. */
        private const val INPUT_QUIET_SETTLE_MS = 300L
    }

    private enum class QringAckKind {
        COMPLETED,
        PAUSED,
        OVERHEAT,
        DISCONNECTED,
        FAULT,
        TIMEOUT,
    }

    private data class QringAckResult(
        val kind: QringAckKind,
        val message: String,
    )

    private data class QringAttemptResult(
        val ack: QringAckResult,
        val rasterStartedAtMs: Long,
        val rasterSentAtMs: Long,
        val finishedAtMs: Long,
        val rasterBytesSent: Int,
    )

    private class QringImmediatePauseException : Exception("用户请求立即暂停")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var pollingJob: Job? = null
    private var reconnectJob: Job? = null
    private var discoveryTimeoutJob: Job? = null
    private var reconnectAttempt = 0
    private var manualDisconnect = false
    private var receiverRegistered = false
    private var privateStatusSupported = false
    private var lastQringCompletionAt = 0L
    @Volatile private var pauseAfterCurrentCopy = false
    @Volatile private var pauseImmediately = false
    @Volatile private var linkDropObserved = false
    @Volatile private var transportGeneration = 0L
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PrinterUiState> = _state.asStateFlow()
    private val _devices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val devices: StateFlow<List<BluetoothDeviceInfo>> = _devices.asStateFlow()
    private val _autoReconnectEnabled = MutableStateFlow(preferences.getBoolean(AUTO_RECONNECT, true))
    val autoReconnectEnabled: StateFlow<Boolean> = _autoReconnectEnabled.asStateFlow()

    private val discoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let(::addDevice)
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> markDiscoveryStarted()
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    if (_state.value.phase == ConnectionPhase.SCANNING) {
                        finishDiscovery("扫描完成，共发现 ${_devices.value.size} 台设备")
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> refreshDevices()
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device?.address == _state.value.deviceAddress) {
                        val disconnectedGeneration = transportGeneration
                        linkDropObserved = true
                        // Close immediately so an in-flight write fails promptly. The generation
                        // check below prevents this delayed broadcast from closing a replacement
                        // socket that the active print job has already re-established.
                        runCatching { socket?.close() }
                        scope.launch {
                            mutex.withLock {
                                if (transportGeneration != disconnectedGeneration) return@withLock
                                closeSocket("连接已中断，等待自动重连")
                                scheduleReconnect()
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                ) {
                    BluetoothAdapter.STATE_ON -> {
                            refreshDevices()
                            scheduleReconnect(350L)
                        }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        stopDiscovery(resumeAutoReconnect = false)
                        scope.launch { mutex.withLock { closeSocket("蓝牙已关闭") } }
                    }
                }
            }
        }
    }

    init {
        registerDiscoveryReceiver()
        refreshDevices()
        if (BluetoothPermissions.has(context, false)) scheduleReconnect(650L)
    }

    private fun initialState(): PrinterUiState = when {
        adapter == null -> PrinterUiState(ConnectionPhase.UNSUPPORTED, lastError = "此设备没有经典蓝牙")
        !BluetoothPermissions.has(context, false) -> PrinterUiState(ConnectionPhase.PERMISSION_REQUIRED)
        else -> PrinterUiState()
    }

    fun onPermissionsChanged() {
        if (!BluetoothPermissions.has(context, false)) {
            _state.value = _state.value.copy(phase = ConnectionPhase.PERMISSION_REQUIRED)
            return
        }
        if (_state.value.phase == ConnectionPhase.PERMISSION_REQUIRED) _state.value = PrinterUiState()
        refreshDevices()
        autoReconnect()
    }

    fun refreshDevices() {
        val bt = adapter ?: return
        if (!BluetoothPermissions.has(context, false)) return
        val bonded = runCatching { bt.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        _devices.value = bonded.map(::deviceInfo).sortedWith(BluetoothConnectionPolicy.deviceComparator)
    }

    fun startDiscovery(): Boolean {
        logs.info("蓝牙扫描", "请求开始")
        val bt = adapter ?: run {
            _state.value = PrinterUiState(ConnectionPhase.UNSUPPORTED, lastError = "此设备没有经典蓝牙")
            return false
        }
        if (!BluetoothPermissions.has(context, true)) {
            _state.value = _state.value.copy(
                phase = ConnectionPhase.PERMISSION_REQUIRED,
                progressText = "需要附近设备扫描权限",
                lastError = "未获得扫描权限，请授权后重试",
            )
            return false
        }
        if (!bt.isEnabled) {
            _state.value = _state.value.copy(
                phase = ConnectionPhase.ERROR,
                progressText = "蓝牙未开启",
                lastError = "请先打开系统蓝牙",
            )
            return false
        }
        refreshDevices()
        reconnectJob?.cancel()
        reconnectJob = null
        if (runCatching { bt.isDiscovering }.getOrDefault(false)) {
            markDiscoveryStarted()
            return true
        }
        val startResult = runCatching { bt.startDiscovery() }
        val started = startResult.getOrDefault(false)
        if (started) {
            markDiscoveryStarted()
        } else {
            val detail = startResult.exceptionOrNull()?.message.orEmpty()
            _state.value = _state.value.copy(
                phase = ConnectionPhase.ERROR,
                progressText = "扫描未能启动",
                lastError = buildString {
                    append("系统未能启动蓝牙扫描，请确认蓝牙")
                    if (Build.VERSION.SDK_INT in 23..30) append("和定位服务")
                    append("已开启")
                    if (detail.isNotBlank()) append("：$detail")
                },
            )
            logs.error("蓝牙扫描未启动", _state.value.lastError)
        }
        return started
    }

    fun stopDiscovery(resumeAutoReconnect: Boolean = true) {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        adapter?.let { runCatching { if (it.isDiscovering) it.cancelDiscovery() } }
        if (_state.value.phase == ConnectionPhase.SCANNING) {
            _state.value = _state.value.copy(
                phase = ConnectionPhase.DISCONNECTED,
                progressText = "已停止扫描",
                lastError = "",
            )
        }
        if (resumeAutoReconnect) scheduleReconnect(1_500L)
    }

    fun connect(address: String) {
        if (!BluetoothPermissions.has(context, false)) {
            _state.value = _state.value.copy(phase = ConnectionPhase.PERMISSION_REQUIRED)
            return
        }
        manualDisconnect = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        logs.info("连接打印机", "手动连接 ${address.takeLast(5)}")
        scope.launch { connectBlocking(address, automatic = false) }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        reconnectJob = null
        scope.launch { mutex.withLock { closeSocket("已断开") } }
    }

    fun autoReconnect() {
        manualDisconnect = false
        scheduleReconnect(0L)
    }

    fun setAutoReconnectEnabled(enabled: Boolean) {
        _autoReconnectEnabled.value = enabled
        preferences.edit().putBoolean(AUTO_RECONNECT, enabled).apply()
        if (enabled) {
            manualDisconnect = false
            scheduleReconnect(0L)
        } else {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
        }
    }

    suspend fun print(
        rendered: RenderedLabel,
        document: LabelDocument,
        copies: Int,
        sequenceStartIndex: Long = 0L,
        initialRowOffset: Int = 0,
    ): PrintResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            pauseAfterCurrentCopy = false
            pauseImmediately = false
            val activeSocket = socket
            val activeOutput = output
            if (activeSocket == null || !activeSocket.isConnected || activeOutput == null || linkDropObserved) {
                logs.warning("打印未开始", "打印机未连接")
                return@withLock PrintResult(false, "请先连接打印机")
            }
            pollingJob?.cancel()
            _state.value = _state.value.copy(phase = ConnectionPhase.PRINTING, progress = 0f, progressText = "打印前检查")
            val safeCopies = copies.coerceIn(MIN_PRINT_COPIES, MAX_PRINT_COPIES)
            try {
                privateStatusSupported = document.paper.protocol == PrintProtocol.QRING_SPP
                logs.info(
                    "打印任务开始",
                    "${document.paper.mode.name} · ${document.paper.protocol.name} · ${document.paper.contentWidthMm} mm · $safeCopies 份",
                )
                val hasSequence = document.elements.any { it.kind == ElementKind.SEQUENCE }
                if (document.paper.protocol == PrintProtocol.QRING_SPP) {
                    val result = printQringCopies(
                        rendered,
                        document,
                        safeCopies,
                        sequenceStartIndex,
                        hasSequence,
                        initialRowOffset,
                    )
                    if (!result.success) {
                        return@withLock if (result.cancelled) {
                            finishPaused(result.completedCopies, safeCopies, result.resumeRowOffset)
                        } else {
                            finishFailure(result.message, result.completedCopies, result.resumeRowOffset)
                        }
                    }
                } else {
                    for (copyIndex in 0 until safeCopies) {
                        if (pauseAfterCurrentCopy) {
                            return@withLock finishPaused(copyIndex, safeCopies)
                        }
                        _state.value = _state.value.copy(
                            progress = copyIndex.toFloat() / safeCopies,
                            progressText = "正在打印 ${copyIndex + 1}/$safeCopies",
                        )
                        val perCopy = if (copyIndex == 0 || !hasSequence) {
                            rendered
                        } else LabelRenderer.render(context, document, sequenceStartIndex + copyIndex)
                        val raster = RasterEncoder.encode(perCopy.bitmap)
                        if (!RasterEncoder.hasInk(raster)) {
                            if (perCopy !== rendered && !perCopy.bitmap.isRecycled) perCopy.bitmap.recycle()
                            return@withLock finishFailure("当前画布渲染后为空白，请先添加可打印内容或调整图片阈值", copyIndex)
                        }
                        val updateProgress: (Float) -> Unit = { fraction ->
                            _state.value = _state.value.copy(
                                progress = (copyIndex + fraction.coerceIn(0f, 1f)) / safeCopies,
                            )
                        }
                        val result = try {
                            printGeneric(raster, document, updateProgress)
                        } finally {
                            if (perCopy !== rendered && !perCopy.bitmap.isRecycled) perCopy.bitmap.recycle()
                        }
                        if (!result.success) return@withLock finishFailure(result.message, copyIndex)
                    }
                }
                _state.value = _state.value.copy(phase = ConnectionPhase.CONNECTED, progress = 1f, progressText = "打印完成")
                logs.info("打印任务完成", "$safeCopies/$safeCopies 份")
                startPolling()
                PrintResult(true, "打印完成", safeCopies)
            } catch (cancelled: CancellationException) {
                if (socket?.isConnected == true) {
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.CONNECTED,
                        progressText = "打印已取消",
                    )
                    startPolling()
                }
                throw cancelled
            } catch (paused: QringImmediatePauseException) {
                finishPaused(0, safeCopies)
            } catch (error: Exception) {
                logs.error("打印连接中断", error.message ?: "I/O 错误")
                closeSocket("连接已中断：${error.message ?: "I/O 错误"}")
                scheduleReconnect()
                PrintResult(false, _state.value.lastError)
            } finally {
                if (state.value.connected && state.value.phase != ConnectionPhase.PRINTING) startPolling()
            }
        }
    }

    /** Pauses before the next copy without interrupting bytes already accepted by the printer. */
    fun requestPauseAfterCurrentCopy() {
        pauseAfterCurrentCopy = true
        logs.warning("请求暂停打印", "将在当前份完整结束后暂停，可选择继续或结束任务")
    }

    /**
     * Stops the active raster at the next safe protocol boundary and returns a conservative row
     * checkpoint. The checkpoint includes a thermal overlap, so resuming may darken a small seam
     * but cannot silently omit half a glyph or image.
     */
    fun requestImmediatePause() {
        pauseImmediately = true
        pauseAfterCurrentCopy = true
        logs.warning("请求立即暂停", "将在当前点阵边界停止并保存行断点，可选择继续或结束任务")
    }

    /** Kept for callers compiled against earlier releases; cancellation is now a resumable pause. */
    fun requestCancelAfterCurrentCopy() {
        requestPauseAfterCurrentCopy()
    }

    suspend fun feedPaper(distanceMm: Float, paper: PaperSettings): PrintResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val activeSocket = socket
                val activeOutput = output
                if (activeSocket == null || !activeSocket.isConnected || activeOutput == null) {
                    return@withLock PrintResult(false, "请先连接打印机")
                }
                val plan = runCatching { PrintJobPolicy.manualFeedPlan(paper, distanceMm) }
                    .getOrElse { return@withLock PrintResult(false, it.message ?: "走纸距离无效") }
                pollingJob?.cancel()
                _state.value = _state.value.copy(
                    phase = ConnectionPhase.PRINTING,
                    progress = 0f,
                    progressText = "正在走纸 ${formatMillimetres(distanceMm)} mm",
                    lastError = "",
                )
                try {
                    privateStatusSupported = paper.protocol == PrintProtocol.QRING_SPP
                    if (paper.protocol == PrintProtocol.QRING_SPP) {
                        awaitQringReadyOrFault(QringReadyMode.PREFLIGHT)?.let { return@withLock finishFailure(it) }
                        awaitQringInputQuiet()
                        beginQringSession()
                        plan.commands.forEach { send(it) }
                        // Some Qring firmware cancels a standalone ESC J when STOP follows in the
                        // same Bluetooth burst. Give the motor time to execute before STOP.
                        delay((plan.dots * 2L).coerceIn(100L, 2_500L))
                        send(QringProtocol.STOP)
                    } else {
                        send(byteArrayOf(0x1B, 0x40))
                        plan.commands.forEach { send(it) }
                        delay((plan.dots * 2L).coerceIn(100L, 2_500L))
                    }
                    if (paper.protocol == PrintProtocol.QRING_SPP) {
                        awaitQringReadyOrFault(QringReadyMode.BETWEEN_COPIES)?.let { return@withLock finishFailure(it) }
                    }
                    _state.value = _state.value.copy(
                        phase = ConnectionPhase.CONNECTED,
                        progress = 1f,
                        progressText = "已走纸 ${formatMillimetres(distanceMm)} mm",
                    )
                    startPolling()
                    PrintResult(true, "已走纸 ${formatMillimetres(distanceMm)} mm")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    closeSocket("走纸失败：${error.message ?: "I/O 错误"}")
                    scheduleReconnect()
                    PrintResult(false, _state.value.lastError)
                } finally {
                    if (state.value.connected && state.value.phase != ConnectionPhase.PRINTING) startPolling()
                }
            }
        }

    private suspend fun connectBlocking(address: String, automatic: Boolean) = mutex.withLock {
        if (automatic && (
                !_autoReconnectEnabled.value ||
                    manualDisconnect ||
                    _state.value.connected ||
                    _state.value.phase == ConnectionPhase.SCANNING
                )
        ) return@withLock
        val bt = adapter ?: return@withLock
        closeSocket("")
        if (!bt.isEnabled) {
            _state.value = PrinterUiState(ConnectionPhase.ERROR, lastError = "请先打开蓝牙")
            return@withLock
        }
        stopDiscovery(resumeAutoReconnect = false)
        _state.value = PrinterUiState(
            ConnectionPhase.CONNECTING,
            deviceAddress = address,
            progressText = if (automatic) "正在自动重连上次设备" else "正在建立 SPP 连接",
        )
        val device = runCatching { bt.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            _state.value = PrinterUiState(ConnectionPhase.ERROR, lastError = "蓝牙地址无效")
            return@withLock
        }
        val connected = runCatching { openSocket(device) }.getOrElse { error ->
            _state.value = PrinterUiState(ConnectionPhase.ERROR, lastError = "连接失败：${error.message ?: "请先在系统蓝牙中配对"}")
            logs.error(if (automatic) "自动重连失败" else "连接失败", _state.value.lastError)
            scheduleReconnect()
            return@withLock
        }
        val info = installTransport(connected, device)
        _state.value = PrinterUiState(ConnectionPhase.CONNECTED, info.name, info.address)
        logs.info(if (automatic) "自动重连成功" else "连接成功", info.name)
        preferences.edit().putString(LAST_ADDRESS, address).apply()
        reconnectAttempt = 0
        reconnectJob = null
        if (privateStatusSupported) queryAll()
        startPolling()
    }

    private suspend fun printQringCopies(
        rendered: RenderedLabel,
        document: LabelDocument,
        copies: Int,
        sequenceStartIndex: Long,
        hasSequence: Boolean,
        initialRowOffset: Int = 0,
    ): PrintResult {
        val firstRaster = RasterEncoder.encode(rendered.bitmap)
        if (!RasterEncoder.hasInk(firstRaster)) {
            return PrintResult(false, "当前画布渲染后为空白，请先添加可打印内容或调整图片阈值")
        }
        var completed = 0
        var measuredRowsPerSecond: Double? = null
        // Do not query the one-byte status channel while a label batch is active. On Qring_50EE
        // the label gap and the motor transition transiently raise paper/heat bits even though the
        // job is healthy. The print transaction's explicit FF fault frame is authoritative here.
        logs.info("逐份打印协议", "批量任务使用打印回包与固定机械复位时间确认，不轮询不稳定的份间状态位")
        for (copyIndex in 0 until copies) {
            if (pauseAfterCurrentCopy) {
                return qringPausedResult(completed, copies)
            }
            _state.value = _state.value.copy(
                progress = copyIndex.toFloat() / copies,
                progressText = "正在打印 ${copyIndex + 1}/$copies",
            )
            val sourceRaster = if (copyIndex == 0 || !hasSequence) {
                firstRaster
            } else {
                val perCopy = LabelRenderer.render(context, document, sequenceStartIndex + copyIndex)
                try {
                    RasterEncoder.encode(perCopy.bitmap)
                } finally {
                    if (!perCopy.bitmap.isRecycled) perCopy.bitmap.recycle()
                }
            }
            if (!RasterEncoder.hasInk(sourceRaster)) {
                return PrintResult(false, "第 ${copyIndex + 1} 份渲染后为空白，请检查流水号内容", completed)
            }
            val prepared = PrintJobPolicy.qringRasterWithTrailingFeed(sourceRaster, document.paper)
            var rowOffset = if (copyIndex == 0) {
                initialRowOffset.coerceIn(0, prepared.heightDots - 1)
            } else {
                0
            }
            var heatPauses = 0
            var reconnectEpisodes = 0
            var completedAttempt: QringAttemptResult? = null
            var completedRemaining: RasterData? = null
            copyAttempt@ while (true) {
                if (pauseImmediately) return qringPausedResult(completed, copies, rowOffset)
                val remaining = RasterEncoder.sliceRows(prepared, rowOffset)
                val result = printQringAttempt(
                    raster = remaining,
                    feedBeforeRaster = rowOffset == 0,
                ) { fraction ->
                    val rowProgress = (rowOffset + fraction.coerceIn(0f, 1f) * remaining.heightDots) /
                        prepared.heightDots.coerceAtLeast(1).toFloat()
                    _state.value = _state.value.copy(
                        progress = (copyIndex + rowProgress.coerceIn(0f, 1f)) / copies,
                    )
                }
                when (result.ack.kind) {
                    QringAckKind.COMPLETED -> {
                        completedAttempt = result
                        completedRemaining = remaining
                        if (rowOffset == 0) {
                            QringPrintRecoveryPolicy.measuredRowsPerSecond(
                                totalRows = prepared.heightDots,
                                mechanicalMs = result.finishedAtMs - result.rasterSentAtMs,
                            )?.let { measuredRowsPerSecond = it }
                        }
                        break@copyAttempt
                    }
                    QringAckKind.DISCONNECTED -> {
                        val checkpoint = estimateResumeRow(
                            rowOffset = rowOffset,
                            totalRows = prepared.heightDots,
                            remainingBytes = remaining.bytes.size,
                            widthBytes = remaining.widthBytes,
                            result = result,
                            measuredRowsPerSecond = measuredRowsPerSecond,
                        )
                        reconnectEpisodes += 1
                        if (reconnectEpisodes > QringPrintRecoveryPolicy.MAX_RECONNECTS_PER_COPY) {
                            return PrintResult(
                                false,
                                "第 ${copyIndex + 1} 份打印中断线次数过多，请检查蓝牙距离后从断点继续",
                                completed,
                                resumeRowOffset = checkpoint,
                            )
                        }
                        try {
                            recoverQringConnection(copyIndex, copies, reconnectEpisodes)
                                ?.let { return PrintResult(false, it, completed, resumeRowOffset = checkpoint) }
                        } catch (_: QringImmediatePauseException) {
                            return qringPausedResult(completed, copies, checkpoint)
                        }
                        // The exact firmware row is not reported. A partial transmission resumes
                        // from complete sent rows with overlap; a fully sent raster uses the timing
                        // estimate. Both paths prefer a darker seam over dropping half a glyph.
                        rowOffset = checkpoint
                        heatPauses = 0
                    }
                    QringAckKind.PAUSED -> {
                        val checkpoint = estimateResumeRow(
                            rowOffset = rowOffset,
                            totalRows = prepared.heightDots,
                            remainingBytes = remaining.bytes.size,
                            widthBytes = remaining.widthBytes,
                            result = result,
                            measuredRowsPerSecond = measuredRowsPerSecond,
                        )
                        return qringPausedResult(completed, copies, checkpoint)
                    }
                    QringAckKind.OVERHEAT -> {
                        heatPauses += 1
                        if (heatPauses > QringPrintRecoveryPolicy.MAX_HEAT_PAUSES) {
                            return PrintResult(
                                false,
                                "第 ${copyIndex + 1} 份反复触发过热保护，请关机散热后从断点继续",
                                completed,
                                resumeRowOffset = rowOffset,
                            )
                        }
                        val previousOffset = rowOffset
                        rowOffset = QringPrintRecoveryPolicy.nextRowOffset(
                            currentRow = rowOffset,
                            totalRows = prepared.heightDots,
                            remainingBytes = remaining.bytes.size,
                            elapsedMs = result.finishedAtMs - result.rasterStartedAtMs,
                            measuredRowsPerSecond = measuredRowsPerSecond,
                        )
                        logs.warning(
                            "打印头过热断点续打",
                            "第 ${copyIndex + 1}/$copies 份：估计从第 ${rowOffset + 1}/${prepared.heightDots} 行继续" +
                                "（由 $previousOffset 行推进，回退重叠 ${QringPrintRecoveryPolicy.HEAT_OVERLAP_ROWS} 行防缺行，" +
                                "第 $heatPauses/${QringPrintRecoveryPolicy.MAX_HEAT_PAUSES} 次）",
                        )
                        _state.value = _state.value.copy(
                            progressText = "第 ${copyIndex + 1}/$copies 份过热，散热后从行断点继续",
                        )
                        val cooldown = awaitQringCoolDown()
                        when (cooldown.kind) {
                            QringAckKind.COMPLETED -> Unit
                            QringAckKind.PAUSED -> return qringPausedResult(completed, copies, rowOffset)
                            QringAckKind.DISCONNECTED -> {
                                reconnectEpisodes += 1
                                if (reconnectEpisodes > QringPrintRecoveryPolicy.MAX_RECONNECTS_PER_COPY) {
                                        return PrintResult(false, "散热等待中蓝牙反复断开，请检查连接后从断点继续", completed, resumeRowOffset = rowOffset)
                                }
                                try {
                                    recoverQringConnection(copyIndex, copies, reconnectEpisodes)
                                        ?.let { return PrintResult(false, it, completed, resumeRowOffset = rowOffset) }
                                } catch (_: QringImmediatePauseException) {
                                    return qringPausedResult(completed, copies, rowOffset)
                                }
                                // The thermal checkpoint already contains a protected overlap.
                                // Keep it after reconnect instead of restarting the whole copy.
                                heatPauses = 0
                            }
                            else -> return PrintResult(false, cooldown.message, completed)
                        }
                    }
                    QringAckKind.FAULT,
                    QringAckKind.TIMEOUT,
                    -> return PrintResult(false, "第 ${copyIndex + 1} 份失败：${result.ack.message}", completed, resumeRowOffset = rowOffset)
                }
            }
            val confirmedAttempt = completedAttempt
                ?: return PrintResult(false, "第 ${copyIndex + 1} 份未取得完成确认", completed, resumeRowOffset = rowOffset)
            val confirmedRemaining = completedRemaining
                ?: return PrintResult(false, "第 ${copyIndex + 1} 份缺少点阵完成记录", completed, resumeRowOffset = rowOffset)
            try {
                awaitQringPhysicalCompletion(
                    rasterHeight = confirmedRemaining.heightDots,
                    rasterSentAtMs = confirmedAttempt.rasterSentAtMs,
                    ackReceivedAtMs = confirmedAttempt.finishedAtMs,
                    copyIndex = copyIndex,
                    copies = copies,
                )?.let { message ->
                    val checkpoint = estimateResumeRow(
                        rowOffset = rowOffset,
                        totalRows = prepared.heightDots,
                        remainingBytes = confirmedRemaining.bytes.size,
                        widthBytes = confirmedRemaining.widthBytes,
                        result = confirmedAttempt.copy(finishedAtMs = SystemClock.elapsedRealtime()),
                        measuredRowsPerSecond = measuredRowsPerSecond,
                    )
                    return PrintResult(false, message, completed, resumeRowOffset = checkpoint)
                }
            } catch (_: QringImmediatePauseException) {
                val checkpoint = estimateResumeRow(
                    rowOffset = rowOffset,
                    totalRows = prepared.heightDots,
                    remainingBytes = confirmedRemaining.bytes.size,
                    widthBytes = confirmedRemaining.widthBytes,
                    result = confirmedAttempt.copy(finishedAtMs = SystemClock.elapsedRealtime()),
                    measuredRowsPerSecond = measuredRowsPerSecond,
                )
                return qringPausedResult(completed, copies, checkpoint)
            }
            completed += 1
            logs.info(
                "打印份数物理确认",
                "$completed/$copies 份已完成点阵发送、ACK 与独立机械复位等待",
            )
        }
        return PrintResult(true, "打印完成", completed)
    }

    private suspend fun printQringAttempt(
        raster: RasterData,
        feedBeforeRaster: Boolean,
        progress: (Float) -> Unit,
    ): QringAttemptResult {
        var rasterStartedAt = 0L
        var rasterSentAt = 0L
        var rasterBytesSent = 0
        return try {
            // A previous ACK can arrive a little after the motor stops. Keep it out of this
            // transaction before sending the next header; otherwise a stale 0xAA can make a
            // printer that is still busy look complete and the following copy gets swallowed.
            awaitQringInputQuiet()
            beginQringSession()
            if (feedBeforeRaster) RasterEncoder.feedCommands(10).forEach { send(it) }
            rasterStartedAt = SystemClock.elapsedRealtime()
            send(RasterEncoder.rasterHeader(raster))
            send(
                data = raster.bytes,
                progress = progress,
                abortOnImmediatePause = true,
                onBytesWritten = { rasterBytesSent = it },
            )
            rasterSentAt = SystemClock.elapsedRealtime()
            send(QringProtocol.STOP, abortOnImmediatePause = false)
            // Only an explicit FF 03 print-response frame enters thermal recovery. A standalone
            // status byte after timeout is not proof of heat on Qring_50EE and caused false alarms.
            val ack = waitForAck()
            QringAttemptResult(
                ack = ack,
                rasterStartedAtMs = rasterStartedAt,
                rasterSentAtMs = rasterSentAt,
                finishedAtMs = SystemClock.elapsedRealtime(),
                rasterBytesSent = rasterBytesSent,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (paused: QringImmediatePauseException) {
            // Best effort STOP: the row checkpoint is calculated by the caller from the elapsed
            // raster time and deliberately overlaps rows on resume.
            runCatching { send(QringProtocol.STOP, abortOnImmediatePause = false) }
            QringAttemptResult(
                ack = QringAckResult(QringAckKind.PAUSED, "用户请求立即暂停"),
                rasterStartedAtMs = rasterStartedAt,
                rasterSentAtMs = rasterSentAt,
                finishedAtMs = SystemClock.elapsedRealtime(),
                rasterBytesSent = rasterBytesSent,
            )
        } catch (error: Exception) {
            if (!isLinkDrop(error)) throw error
            QringAttemptResult(
                ack = QringAckResult(QringAckKind.DISCONNECTED, "连接已断开"),
                rasterStartedAtMs = rasterStartedAt,
                rasterSentAtMs = rasterSentAt,
                finishedAtMs = SystemClock.elapsedRealtime(),
                rasterBytesSent = rasterBytesSent,
            )
        }
    }

    private fun estimateResumeRow(
        rowOffset: Int,
        totalRows: Int,
        remainingBytes: Int,
        widthBytes: Int,
        result: QringAttemptResult,
        measuredRowsPerSecond: Double?,
    ): Int {
        if (result.rasterBytesSent <= 0 || result.rasterStartedAtMs <= 0L) return rowOffset
        if (result.rasterBytesSent < remainingBytes) {
            return QringPrintRecoveryPolicy.rowOffsetFromTransferredBytes(
                currentRow = rowOffset,
                totalRows = totalRows,
                widthBytes = widthBytes,
                transferredBytes = result.rasterBytesSent,
            )
        }
        return QringPrintRecoveryPolicy.nextRowOffset(
            currentRow = rowOffset,
            totalRows = totalRows,
            remainingBytes = remainingBytes,
            elapsedMs = result.finishedAtMs - result.rasterStartedAtMs,
            measuredRowsPerSecond = measuredRowsPerSecond,
        )
    }

    private suspend fun recoverQringConnection(
        copyIndex: Int,
        copies: Int,
        reconnectEpisode: Int,
    ): String? {
        logs.warning(
            "打印中断线恢复",
            "第 ${copyIndex + 1}/$copies 份连接中断，正在重连并重打当前未确认份（第 $reconnectEpisode 次）",
        )
        _state.value = _state.value.copy(
            progressText = "连接中断，正在重连并恢复第 ${copyIndex + 1}/$copies 份",
            lastError = "",
        )
        return reconnectActivePrint()
    }

    /**
     * Confirms one physical copy before the next protocol session is allowed to start.
     *
     * Qring ACK confirms that the raster command was accepted, but Qring_50EE can ACK another
     * raster while its engine is still rearming and then silently discard it. Every copy therefore
     * gets both a height deadline and a fixed post-ACK rearm deadline. Status polling is deliberately
     * absent: the supplied device log proves that its busy, paper and heat bits are transient here.
     */
    private suspend fun awaitQringPhysicalCompletion(
        rasterHeight: Int,
        rasterSentAtMs: Long,
        ackReceivedAtMs: Long,
        copyIndex: Int,
        copies: Int,
    ): String? {
        val sentAt = rasterSentAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val ackAt = ackReceivedAtMs.takeIf { it > 0L } ?: SystemClock.elapsedRealtime()
        val heightWaitMs = QringPrintRecoveryPolicy.minimumPostSendCompletionMillis(rasterHeight)
        val readyAt = QringPrintRecoveryPolicy.physicalCompletionNotBeforeMillis(
            totalRows = rasterHeight,
            rasterSentAtMs = sentAt,
            ackReceivedAtMs = ackAt,
        )
        val remainingMs = readyAt - SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(
            progressText = "正在确认第 ${copyIndex + 1}/$copies 份已完整出纸",
        )
        logs.info(
            "逐份物理完成闸",
            "第 ${copyIndex + 1}/$copies 份 ACK 已收到；高度等待 ${heightWaitMs} ms，" +
                "且每份 ACK 后固定复位 ${QringPrintRecoveryPolicy.POST_ACK_REARM_MS} ms，取较晚时刻",
        )
        if (remainingMs > 0L && !delayUnlessImmediatelyPaused(remainingMs)) {
            throw QringImmediatePauseException()
        }
        var reconnectEpisodes = 0
        while (true) {
            try {
                if (!isTransportConnected()) throw IOException("连接已断开")
                awaitQringInputQuiet()
                return null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!isLinkDrop(error)) throw error
                reconnectEpisodes += 1
                if (reconnectEpisodes > QringPrintRecoveryPolicy.MAX_RECONNECTS_PER_COPY) {
                    return "确认第 ${copyIndex + 1}/$copies 份后蓝牙反复断开，请检查距离后继续剩余份数"
                }
                logs.warning(
                    "份间蓝牙恢复",
                    "第 ${copyIndex + 1}/$copies 份已过机械复位时间，正在自动重连（第 $reconnectEpisodes 次）",
                )
                reconnectActivePrint()?.let { return it }
            }
        }
    }

    /**
     * Reconnects while [mutex] is already owned by the active print job.
     *
     * Calling the public connect path here would deadlock because that path also locks [mutex].
     * This private path owns only transport replacement and invalidates stale disconnect broadcasts
     * through [transportGeneration].
     */
    private suspend fun reconnectActivePrint(): String? {
        val address = _state.value.deviceAddress.ifBlank {
            preferences.getString(LAST_ADDRESS, "").orEmpty()
        }
        if (!BluetoothAdapter.checkBluetoothAddress(address)) return "连接中断且没有可用的设备地址"
        if (!BluetoothPermissions.has(context, false)) return "蓝牙权限已失效，无法自动重连"
        val bt = adapter ?: return "此设备不支持经典蓝牙"
        if (!bt.isEnabled) return "蓝牙已关闭，无法继续打印"
        val device = runCatching { bt.getRemoteDevice(address) }.getOrNull()
            ?: return "自动重连设备地址无效"

        val previousState = _state.value
        reconnectJob?.cancel()
        reconnectJob = null
        stopDiscovery(resumeAutoReconnect = false)
        closeTransport()
        _state.value = previousState.copy(
            phase = ConnectionPhase.PRINTING,
            progressText = "连接中断，正在自动重连",
            lastError = "",
        )

        val deadline = SystemClock.elapsedRealtime() + QringPrintRecoveryPolicy.RECONNECT_TIMEOUT_MS
        var attempt = 0
        var lastMessage = "自动重连超时"
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pauseImmediately) throw QringImmediatePauseException()
            val connected = try {
                openSocket(device)
            } catch (error: Exception) {
                lastMessage = error.message ?: "SPP 连接失败"
                null
            }
            if (connected != null) {
                val info = try {
                    installTransport(connected, device)
                } catch (error: Exception) {
                    runCatching { connected.close() }
                    lastMessage = error.message ?: "无法取得蓝牙输入输出流"
                    null
                }
                if (info != null) {
                    if (pauseImmediately) throw QringImmediatePauseException()
                    privateStatusSupported = true
                    _state.value = previousState.copy(
                        phase = ConnectionPhase.PRINTING,
                        deviceName = info.name,
                        deviceAddress = info.address,
                        progressText = "已自动重连，正在稳定蓝牙通道",
                        lastError = "",
                    )
                    preferences.edit().putString(LAST_ADDRESS, info.address).apply()
                    reconnectAttempt = 0
                    try {
                        delay(INTER_SESSION_SETTLE_MS)
                        awaitQringInputQuiet()
                    } catch (error: Exception) {
                        if (isLinkDrop(error)) {
                            lastMessage = "重连后链路再次中断"
                            closeTransport()
                        } else {
                            throw error
                        }
                    }
                    if (isTransportConnected()) {
                        logs.info("打印中断线恢复成功", "已重新连接 ${info.name}，将恢复未确认份")
                        return null
                    }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            delay(QringPrintRecoveryPolicy.reconnectDelayMillis(attempt))
            attempt += 1
            _state.value = _state.value.copy(progressText = "自动重连中（第 ${attempt + 1} 次）")
        }

        closeTransport()
        val message = "连接中断后自动重连失败：$lastMessage"
        _state.value = previousState.copy(
            phase = ConnectionPhase.ERROR,
            lastError = message,
            progressText = "可稍后从断点继续",
        )
        logs.error("打印中断线恢复失败", message)
        scheduleReconnect()
        return message
    }

    /** Mirrors the reference 2-second thermal poll with a safe blind-cooldown fallback. */
    private suspend fun awaitQringCoolDown(): QringAckResult {
        val deadline = SystemClock.elapsedRealtime() + QringPrintRecoveryPolicy.COOLDOWN_TIMEOUT_MS
        var silentPolls = 0
        var sawHeat = false
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pauseImmediately) {
                return QringAckResult(QringAckKind.PAUSED, "用户请求立即暂停")
            }
            if (!isTransportConnected()) {
                return QringAckResult(QringAckKind.DISCONNECTED, "散热等待中连接已断开")
            }
            val status = try {
                queryHardware()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (isLinkDrop(error)) {
                    return QringAckResult(QringAckKind.DISCONNECTED, "散热等待中连接已断开")
                }
                return QringAckResult(QringAckKind.FAULT, error.message ?: "读取打印机温度状态失败")
            }
            if (status == null) {
                silentPolls += 1
                if (silentPolls >= 2) {
                    logs.info("打印头盲等散热", "状态通道无响应，等待 20 秒后保守续打")
                    if (!delayUnlessImmediatelyPaused(BLIND_COOLDOWN_DELAY_MS)) {
                        return QringAckResult(QringAckKind.PAUSED, "用户请求立即暂停")
                    }
                    return if (isTransportConnected()) {
                        QringAckResult(QringAckKind.COMPLETED, "盲等散热完成")
                    } else {
                        QringAckResult(QringAckKind.DISCONNECTED, "散热等待中连接已断开")
                    }
                }
            } else {
                silentPolls = 0
                if (!status.overheat) {
                    QringProtocol.blockingFault(status)?.let {
                        return QringAckResult(QringAckKind.FAULT, it)
                    }
                    if (sawHeat) logs.info("打印头散热完成", "继续当前份的剩余点阵")
                    return QringAckResult(QringAckKind.COMPLETED, "散热完成")
                }
                sawHeat = true
            }
            delay(COOLDOWN_POLL_DELAY_MS)
        }
        // Match the reference implementation: retrying is safe because firmware will raise the
        // heat bit again, while the per-copy pause limit prevents an infinite loop.
        logs.warning("等待散热超时", "尝试继续；若仍过热会再次进入保护并保留行断点")
        return QringAckResult(QringAckKind.COMPLETED, "等待散热超时，保守重试")
    }

    private suspend fun printGeneric(
        raster: com.qrint.studio.render.RasterData,
        document: LabelDocument,
        progress: (Float) -> Unit,
    ): PrintResult {
        send(byteArrayOf(0x1B, 0x40))
        send(RasterEncoder.rasterHeader(raster))
        send(raster.bytes, progress)
        PrintJobPolicy.trailingFeedCommands(document.paper).forEach { send(it) }
        return PrintResult(true, "数据已发送")
    }

    /**
     * Keep the upstream Qring SDK transaction order in one helper so feed, label and continuous
     * jobs cannot drift apart: enable both engines first, then wake the head for this transaction.
     */
    private suspend fun beginQringSession() {
        send(QringProtocol.ENABLE)
        send(QringProtocol.ENABLE_SECONDARY)
        send(QringProtocol.WAKE_UP)
    }

    /**
     * Clear delayed unsolicited ACK/fault bytes before opening a new raster transaction. A short
     * quiet window is intentional: draining once is not enough when Bluetooth delivers the
     * previous response in two packets.
     */
    private suspend fun awaitQringInputQuiet(
        quietWindowMs: Long = INPUT_QUIET_SETTLE_MS,
    ) {
        val stream = input ?: return
        val deadline = SystemClock.elapsedRealtime() + quietWindowMs.coerceAtLeast(1L) + 1_000L
        var quietSince = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            if (linkDropObserved) throw IOException("连接已断开")
            if (stream.available() > 0) {
                drain(stream)
                quietSince = SystemClock.elapsedRealtime()
            } else if (SystemClock.elapsedRealtime() - quietSince >= quietWindowMs) {
                return
            }
            delay(20L)
        }
    }

    private suspend fun send(
        data: ByteArray,
        progress: ((Float) -> Unit)? = null,
        abortOnImmediatePause: Boolean = false,
        onBytesWritten: ((Int) -> Unit)? = null,
    ) {
        if (linkDropObserved) throw IOException("连接已断开")
        val stream = output ?: throw IOException("打印机未连接")
        var offset = 0
        while (offset < data.size) {
            if (linkDropObserved) throw IOException("连接已断开")
            if (abortOnImmediatePause && pauseImmediately) throw QringImmediatePauseException()
            val count = minOf(QringProtocol.CHUNK_SIZE, data.size - offset)
            stream.write(data, offset, count)
            stream.flush()
            offset += count
            onBytesWritten?.invoke(offset)
            progress?.invoke(offset.toFloat() / data.size.coerceAtLeast(1))
            // Match the upstream SDK timing even after a one-chunk command. In particular this
            // prevents STOP from being coalesced into the final raster bytes on sensitive firmware.
            delay(QringProtocol.CHUNK_DELAY_MS)
        }
    }

    private suspend fun query(command: ByteArray, expected: Int): ByteArray {
        if (linkDropObserved) throw IOException("连接已断开")
        val stream = input ?: throw IOException("打印机未连接")
        drain(stream)
        send(command)
        delay(150)
        val result = ArrayList<Byte>()
        val deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MS
        var lastDataAt = 0L
        while (System.currentTimeMillis() < deadline && result.size < expected) {
            if (linkDropObserved) throw IOException("连接已断开")
            var received = false
            while (stream.available() > 0 && result.size < expected) {
                result += stream.read().toByte()
                received = true
            }
            if (received) lastDataAt = System.currentTimeMillis()
            // Model/firmware strings are variable length and do not always reach [expected].
            if (result.isNotEmpty() && lastDataAt > 0 && System.currentTimeMillis() - lastDataAt >= 120) break
            if (result.size < expected) delay(20)
        }
        return result.toByteArray()
    }

    private suspend fun queryHardware(): HardwareStatus? {
        val response = query(QringProtocol.QUERY_STATUS, 1)
        if (response.isEmpty()) return null
        val status = QringProtocol.parseStatus(response[0].toInt() and 0xFF)
        _state.value = _state.value.copy(hardware = status)
        return status
    }

    /** Wait until the previous motor job is really idle; ACK can precede mechanical completion. */
    private suspend fun awaitQringReadyOrFault(
        mode: QringReadyMode,
        timeoutMs: Long = READY_TIMEOUT_MS,
    ): String? {
        if (!isTransportConnected()) throw IOException("连接已断开")
        val settleRemaining = INTER_SESSION_SETTLE_MS - (SystemClock.elapsedRealtime() - lastQringCompletionAt)
        if (settleRemaining > 0) delay(settleRemaining)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val gate = QringReadyGate(mode = mode)
        var sawOverheat = false
        var overheatSamples = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!isTransportConnected()) throw IOException("连接已断开")
            val status = queryHardware()
            if (status?.overheat == true) {
                overheatSamples += 1
                // A single 0x10 snapshot is a normal Qring_50EE motor-transition artefact. Only a
                // persistent manual-feed status is surfaced; active print heat uses explicit FF 03.
                if (overheatSamples >= 3 && !sawOverheat) {
                    sawOverheat = true
                    logs.warning("打印头持续过热", "连续三次状态确认，等待散热完成")
                    _state.value = _state.value.copy(progressText = "打印头过热，等待散热")
                }
            } else {
                overheatSamples = 0
            }
            val observation = gate.observe(status)
            when (observation.decision) {
                QringReadyDecision.READY -> return null
                QringReadyDecision.FAULT -> return observation.message
                QringReadyDecision.WAIT -> Unit
            }
            delay(READY_POLL_DELAY_MS)
        }
        return if (sawOverheat) {
            "打印头仍在过热保护中，可稍后从断点继续"
        } else {
            "打印机仍在处理上一份任务，可稍后从断点继续"
        }
    }

    private suspend fun delayUnlessImmediatelyPaused(durationMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pauseImmediately) return false
            val remaining = deadline - SystemClock.elapsedRealtime()
            delay(remaining.coerceIn(1L, 250L))
        }
        return !pauseImmediately
    }

    private fun qringPausedResult(completed: Int, requested: Int, rowOffset: Int = 0): PrintResult {
        val rowDetail = if (rowOffset > 0) "，可从第 ${rowOffset + 1} 行继续" else "，可从当前份继续"
        return PrintResult(
            success = false,
            message = "已暂停，已完成 $completed/$requested 份$rowDetail",
            completedCopies = completed,
            cancelled = true,
            resumeRowOffset = rowOffset.coerceAtLeast(0),
        )
    }

    private suspend fun queryAll() {
        runCatching {
            val hardware = queryHardware()
            val battery = QringProtocol.parseBatteryPercent(query(QringProtocol.QUERY_BATTERY, 2))
            val modelName = queryText(QringProtocol.QUERY_MODEL)
            val firmware = queryText(QringProtocol.QUERY_FIRMWARE)
            _state.value = _state.value.copy(hardware = hardware, batteryPercent = battery, model = modelName, firmware = firmware)
        }
    }

    private suspend fun queryText(command: ByteArray): String {
        val bytes = query(command, 32)
        return bytes.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charset.forName("UTF-8")).trim()
    }

    private suspend fun waitForAck(): QringAckResult {
        if (!isTransportConnected()) return QringAckResult(QringAckKind.DISCONNECTED, "连接已断开")
        val stream = input ?: return QringAckResult(QringAckKind.DISCONNECTED, "连接已断开")
        val deadline = SystemClock.elapsedRealtime() + ACK_TIMEOUT_MS
        val response = ArrayList<Byte>(32)
        var pendingFaultCode: Int? = null
        var pendingFaultSince = 0L
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (!isTransportConnected()) return QringAckResult(QringAckKind.DISCONNECTED, "连接已断开")
                if (pauseImmediately) throw QringImmediatePauseException()
                while (stream.available() > 0) {
                    val value = stream.read()
                    if (value < 0) return QringAckResult(QringAckKind.DISCONNECTED, "连接已断开")
                    response += value.toByte()
                }
                val parsed = QringProtocol.parsePrintResponse(response.toByteArray())
                when (parsed.kind) {
                    QringProtocol.PrintResponseKind.COMPLETED -> {
                        lastQringCompletionAt = SystemClock.elapsedRealtime()
                        return QringAckResult(QringAckKind.COMPLETED, "打印完成")
                    }
                    QringProtocol.PrintResponseKind.FAULT -> {
                        val now = SystemClock.elapsedRealtime()
                        if (pendingFaultCode != parsed.faultCode) {
                            pendingFaultCode = parsed.faultCode
                            pendingFaultSince = now
                        } else if (now - pendingFaultSince >= FAULT_CONFIRM_DELAY_MS) {
                            val code = parsed.faultCode ?: 0
                            return if (code == 0x03) {
                                QringAckResult(QringAckKind.OVERHEAT, QringProtocol.faultFrameMessage(code))
                            } else {
                                QringAckResult(QringAckKind.FAULT, QringProtocol.faultFrameMessage(code))
                            }
                        }
                    }
                    QringProtocol.PrintResponseKind.PENDING -> Unit
                }
                if (response.size > 64 && pendingFaultCode == null) {
                    val trailingHead = (response.last().toInt() and 0xFF) == QringProtocol.FAULT_FRAME_HEAD
                    response.clear()
                    if (trailingHead) response += QringProtocol.FAULT_FRAME_HEAD.toByte()
                }
                delay(25)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (isLinkDrop(error)) return QringAckResult(QringAckKind.DISCONNECTED, "连接已断开")
            throw error
        }
        return QringAckResult(QringAckKind.TIMEOUT, "等待打印完成超时，请检查纸张和机器状态")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        if (!state.value.connected || !privateStatusSupported) return
        pollingJob = scope.launch {
            while (true) {
                delay(10_000)
                mutex.withLock {
                    if (_state.value.phase == ConnectionPhase.CONNECTED) {
                        runCatching { queryAll() }.onFailure {
                            closeSocket("状态读取失败，等待自动重连")
                            scheduleReconnect()
                        }
                    }
                }
            }
        }
    }

    private fun finishFailure(
        message: String,
        completed: Int = 0,
        resumeRowOffset: Int = 0,
    ): PrintResult {
        val connected = isTransportConnected()
        _state.value = _state.value.copy(
            phase = if (connected) ConnectionPhase.CONNECTED else ConnectionPhase.ERROR,
            lastError = message,
            progressText = message,
        )
        if (connected) startPolling()
        logs.error("打印失败", "$message · 已完成 $completed 份")
        return PrintResult(false, message, completed, resumeRowOffset = resumeRowOffset)
    }

    private fun finishPaused(completed: Int, requested: Int, resumeRowOffset: Int = 0): PrintResult {
        val message = "已暂停，已完成 $completed/$requested 份，可选择继续或结束任务"
        _state.value = _state.value.copy(
            phase = if (isTransportConnected()) ConnectionPhase.CONNECTED else ConnectionPhase.ERROR,
            progressText = message,
            lastError = "",
        )
        if (isTransportConnected()) startPolling() else scheduleReconnect()
        logs.warning("打印已暂停", message)
        return PrintResult(false, message, completed, cancelled = true, resumeRowOffset = resumeRowOffset)
    }

    private fun closeSocket(message: String) {
        closeTransport()
        _state.value = PrinterUiState(
            phase = if (message.isBlank() || message == "已断开") ConnectionPhase.DISCONNECTED else ConnectionPhase.ERROR,
            lastError = message,
        )
    }

    private fun closeTransport() {
        pollingJob?.cancel(); pollingJob = null
        runCatching { input?.close() }; runCatching { output?.close() }; runCatching { socket?.close() }
        input = null; output = null; socket = null
        privateStatusSupported = false
        lastQringCompletionAt = 0L
        linkDropObserved = true
        transportGeneration += 1
    }

    private fun installTransport(
        connected: BluetoothSocket,
        device: BluetoothDevice,
    ): BluetoothDeviceInfo {
        socket = connected
        input = connected.inputStream
        output = connected.outputStream
        linkDropObserved = false
        transportGeneration += 1
        val info = deviceInfo(device)
        privateStatusSupported = info.likelyQring
        return info
    }

    private fun isTransportConnected(): Boolean =
        socket != null && socket?.isConnected == true && output != null && !linkDropObserved

    private fun isLinkDrop(error: Throwable): Boolean {
        if (linkDropObserved) return true
        if (error is IOException) return true
        return Regex("断开|disconnected|not connected|broken pipe|socket closed", RegexOption.IGNORE_CASE)
            .containsMatchIn(error.message.orEmpty())
    }

    private fun scheduleReconnect(delayOverride: Long? = null) {
        val address = preferences.getString(LAST_ADDRESS, "").orEmpty()
        val validAddress = BluetoothAdapter.checkBluetoothAddress(address)
        if (!BluetoothPermissions.has(context, false) || !BluetoothConnectionPolicy.shouldAutoReconnect(
                enabled = _autoReconnectEnabled.value,
                manualDisconnect = manualDisconnect,
                phase = _state.value.phase,
                hasValidAddress = validAddress,
            )
        ) return
        reconnectJob?.cancel()
        val attemptNumber = reconnectAttempt + 1
        val waitMs = delayOverride ?: BluetoothConnectionPolicy.reconnectDelayMillis(reconnectAttempt)
        reconnectAttempt = BluetoothConnectionPolicy.nextReconnectAttempt(reconnectAttempt)
        _state.value = _state.value.copy(
            progressText = if (waitMs < 1_000L) "正在准备自动重连" else "将在 ${waitMs / 1_000L} 秒后自动重连（第 $attemptNumber 次）",
        )
        val job = scope.launch(start = CoroutineStart.LAZY) {
            delay(waitMs)
            if (BluetoothConnectionPolicy.shouldAutoReconnect(
                    enabled = _autoReconnectEnabled.value,
                    manualDisconnect = manualDisconnect,
                    phase = _state.value.phase,
                    hasValidAddress = true,
                )
            ) {
                reconnectJob = null
                connectBlocking(address, automatic = true)
            }
        }
        reconnectJob = job
        job.start()
    }

    private fun drain(stream: InputStream) {
        runCatching { while (stream.available() > 0) stream.read() }
    }

    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        var candidate = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            candidate.connect()
            return candidate
        } catch (secureError: Exception) {
            runCatching { candidate.close() }
            candidate = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            try {
                candidate.connect()
                return candidate
            } catch (insecureError: Exception) {
                runCatching { candidate.close() }
                insecureError.addSuppressed(secureError)
                throw insecureError
            }
        }
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Bluetooth broadcasts may originate from the privileged Bluetooth process rather than
        // the framework system UID. Android's receiver guidance requires EXPORTED for this case.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(discoveryReceiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(discoveryReceiver, filter)
        receiverRegistered = true
    }

    private fun addDevice(device: BluetoothDevice) {
        val info = deviceInfo(device)
        val map = _devices.value.associateBy { it.address }.toMutableMap()
        map[info.address] = info
        _devices.value = map.values.sortedWith(BluetoothConnectionPolicy.deviceComparator)
    }

    private fun deviceInfo(device: BluetoothDevice) = BluetoothDeviceInfo(
        name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "未命名设备" },
        address = device.address,
        bonded = device.bondState == BluetoothDevice.BOND_BONDED,
    )

    private fun markDiscoveryStarted() {
        _state.value = _state.value.copy(
            phase = ConnectionPhase.SCANNING,
            progressText = "正在扫描附近的经典蓝牙设备",
            lastError = "",
        )
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (_state.value.phase == ConnectionPhase.SCANNING) {
                finishDiscovery("扫描超时，共发现 ${_devices.value.size} 台设备", cancelAdapter = true)
            }
        }
    }

    private fun formatMillimetres(value: Float): String =
        if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

    private fun finishDiscovery(message: String, cancelAdapter: Boolean = false) {
        discoveryTimeoutJob?.cancel()
        discoveryTimeoutJob = null
        if (cancelAdapter) adapter?.let { runCatching { if (it.isDiscovering) it.cancelDiscovery() } }
        if (_state.value.phase != ConnectionPhase.SCANNING) return
        _state.value = _state.value.copy(
            phase = ConnectionPhase.DISCONNECTED,
            progressText = message,
            lastError = "",
        )
        scheduleReconnect(1_500L)
    }
}
