package com.qrint.studio.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothConnectionPolicyTest {
    @Test
    fun qringDevicesAlwaysSortAheadOfOtherSupportedAndGenericDevices() {
        val devices = listOf(
            BluetoothDeviceInfo("普通打印机", "00:00:00:00:00:01", bonded = true),
            BluetoothDeviceInfo("BeePrt-BY", "00:00:00:00:00:02", bonded = true),
            BluetoothDeviceInfo("Qring-01", "00:00:00:00:00:03", bonded = false),
            BluetoothDeviceInfo("BY-245", "00:00:00:00:00:04", bonded = true),
        )

        val sorted = devices.sortedWith(BluetoothConnectionPolicy.deviceComparator)

        assertEquals(listOf("Qring-01", "BeePrt-BY", "BY-245", "普通打印机"), sorted.map { it.name })
    }

    @Test
    fun reconnectBackoffIsBoundedAndMatchesUiContract() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 30_000L),
            (0..6).map(BluetoothConnectionPolicy::reconnectDelayMillis))
    }

    @Test
    fun reconnectRequiresOptInAddressAndIdleConnectionState() {
        assertTrue(BluetoothConnectionPolicy.shouldAutoReconnect(true, false, ConnectionPhase.ERROR, true))
        assertFalse(BluetoothConnectionPolicy.shouldAutoReconnect(false, false, ConnectionPhase.ERROR, true))
        assertFalse(BluetoothConnectionPolicy.shouldAutoReconnect(true, true, ConnectionPhase.ERROR, true))
        assertFalse(BluetoothConnectionPolicy.shouldAutoReconnect(true, false, ConnectionPhase.SCANNING, true))
        assertFalse(BluetoothConnectionPolicy.shouldAutoReconnect(true, false, ConnectionPhase.CONNECTED, true))
        assertFalse(BluetoothConnectionPolicy.shouldAutoReconnect(true, false, ConnectionPhase.DISCONNECTED, false))
    }
}
