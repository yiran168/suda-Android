package com.qrint.studio.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QringProtocolTest {
    @Test fun statusBitsAreParsedIndependently() {
        val status = QringProtocol.parseStatus(0x02 or 0x04 or 0x10)
        assertTrue(status.coverOpen)
        assertTrue(status.noPaper)
        assertTrue(status.overheat)
        assertFalse(status.printing)
    }

    @Test fun coverMessageWinsOverPaperMessage() {
        val status = QringProtocol.parseStatus(0x02 or 0x04)
        assertEquals("上盖未合好，请合盖后重试", QringProtocol.blockingFault(status))
    }

    @Test fun batteryUsesSecondProtocolByte() {
        assertEquals(73, QringProtocol.parseBatteryPercent(byteArrayOf(0x00, 73)))
        assertEquals(100, QringProtocol.parseBatteryPercent(byteArrayOf(0x00, 100)))
    }

    @Test fun unavailableBatteryIsNotMisreportedAsFull() {
        assertEquals(null, QringProtocol.parseBatteryPercent(byteArrayOf(0x00, 0xFF.toByte())))
        assertEquals(null, QringProtocol.parseBatteryPercent(byteArrayOf(0x00, 101)))
        assertEquals(null, QringProtocol.parseBatteryPercent(byteArrayOf(80)))
    }

    @Test fun printAckWinsOverFaultBytesInTheSameResponseWindow() {
        assertEquals(
            QringProtocol.PrintResponseKind.COMPLETED,
            QringProtocol.parsePrintResponse(byteArrayOf(0xFF.toByte(), 0x02, 0xAA.toByte())).kind,
        )
        assertEquals(
            QringProtocol.PrintResponseKind.COMPLETED,
            QringProtocol.parsePrintResponse(byteArrayOf(0xAA.toByte(), 0xFF.toByte(), 0x02)).kind,
        )
    }

    @Test fun onlyDefinedFaultFramesInterruptPrinting() {
        val cover = QringProtocol.parsePrintResponse(byteArrayOf(0x00, 0xFF.toByte(), 0x02))
        assertEquals(QringProtocol.PrintResponseKind.FAULT, cover.kind)
        assertEquals(0x02, cover.faultCode)
        assertEquals(
            QringProtocol.PrintResponseKind.PENDING,
            QringProtocol.parsePrintResponse(byteArrayOf(0xFF.toByte(), 0x7F)).kind,
        )
        assertEquals(
            QringProtocol.PrintResponseKind.PENDING,
            QringProtocol.parsePrintResponse(byteArrayOf(0xFF.toByte())).kind,
        )
    }
}
