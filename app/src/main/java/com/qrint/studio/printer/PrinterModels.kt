package com.qrint.studio.printer

const val MIN_PRINT_COPIES = 1
const val MAX_PRINT_COPIES = 50

enum class ConnectionPhase { UNSUPPORTED, PERMISSION_REQUIRED, DISCONNECTED, SCANNING, CONNECTING, CONNECTED, PRINTING, ERROR }

data class BluetoothDeviceInfo(
    val name: String,
    val address: String,
    val bonded: Boolean,
) {
    val likelyQring: Boolean get() = BluetoothConnectionPolicy.preferredDevicePriority(name) > 0
}

data class PrinterUiState(
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val deviceName: String = "未连接打印机",
    val deviceAddress: String = "",
    val batteryPercent: Int? = null,
    val hardware: HardwareStatus? = null,
    val model: String = "",
    val firmware: String = "",
    val lastError: String = "",
    val progress: Float = 0f,
    val progressText: String = "",
) {
    val connected: Boolean get() = phase == ConnectionPhase.CONNECTED || phase == ConnectionPhase.PRINTING
    val healthy: Boolean get() = hardware?.let { !it.coverOpen && !it.noPaper && !it.overheat } ?: true
}

data class PrintResult(
    val success: Boolean,
    val message: String,
    val completedCopies: Int = 0,
    val cancelled: Boolean = false,
    /** Row checkpoint for an interrupted first unconfirmed copy; 0 means restart that copy. */
    val resumeRowOffset: Int = 0,
)
