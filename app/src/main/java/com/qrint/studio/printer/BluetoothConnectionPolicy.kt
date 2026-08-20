package com.qrint.studio.printer

/** Pure connection rules shared by discovery, UI ordering and reconnect scheduling. */
internal object BluetoothConnectionPolicy {
    private val reconnectDelaysMs = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L)

    fun preferredDevicePriority(name: String): Int = when {
        name.contains("Qring", ignoreCase = true) -> 3
        name.contains("BeePrt", ignoreCase = true) -> 2
        name.startsWith("BY", ignoreCase = true) -> 1
        else -> 0
    }

    val deviceComparator: Comparator<BluetoothDeviceInfo> =
        compareByDescending<BluetoothDeviceInfo> { preferredDevicePriority(it.name) }
            .thenByDescending { it.bonded }
            .thenBy { it.name.lowercase() }
            .thenBy { it.address }

    fun reconnectDelayMillis(attempt: Int): Long = reconnectDelaysMs[attempt.coerceIn(reconnectDelaysMs.indices)]

    fun nextReconnectAttempt(attempt: Int): Int = (attempt + 1).coerceAtMost(reconnectDelaysMs.size)

    fun shouldAutoReconnect(
        enabled: Boolean,
        manualDisconnect: Boolean,
        phase: ConnectionPhase,
        hasValidAddress: Boolean,
    ): Boolean = enabled &&
        !manualDisconnect &&
        hasValidAddress &&
        phase !in setOf(
            ConnectionPhase.SCANNING,
            ConnectionPhase.CONNECTING,
            ConnectionPhase.CONNECTED,
            ConnectionPhase.PRINTING,
        )
}
