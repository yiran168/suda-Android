package com.qrint.studio.printer

import org.junit.Assert.assertEquals
import org.junit.Test

class QringReadyPolicyTest {
    @Test fun twoStableIdleSamplesOpenTheNextCopyGate() {
        val gate = QringReadyGate(QringReadyMode.BETWEEN_COPIES)
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus()).decision)
        assertEquals(QringReadyDecision.READY, gate.observe(HardwareStatus()).decision)
    }

    @Test fun transientCoverBitBetweenCopiesDoesNotAbortTheBatch() {
        val gate = QringReadyGate(QringReadyMode.BETWEEN_COPIES)
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus(coverOpen = true)).decision)
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus()).decision)
        assertEquals(QringReadyDecision.READY, gate.observe(HardwareStatus()).decision)
    }

    @Test fun persistentMediaFaultStillStopsSafely() {
        val gate = QringReadyGate(QringReadyMode.BETWEEN_COPIES)
        val cover = HardwareStatus(coverOpen = true)
        repeat(3) { assertEquals(QringReadyDecision.WAIT, gate.observe(cover).decision) }
        val confirmed = gate.observe(cover)
        assertEquals(QringReadyDecision.FAULT, confirmed.decision)
        assertEquals("上盖未合好，请合盖后重试", confirmed.message)
    }

    @Test fun overheatMustClearBeforeTheNextCopy() {
        val gate = QringReadyGate(QringReadyMode.BETWEEN_COPIES)
        repeat(5) { assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus(overheat = true)).decision) }
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus()).decision)
        assertEquals(QringReadyDecision.READY, gate.observe(HardwareStatus()).decision)
    }

    @Test fun silentStatusChannelFallsBackAfterTwoPolls() {
        val gate = QringReadyGate(QringReadyMode.BETWEEN_COPIES)
        assertEquals(QringReadyDecision.WAIT, gate.observe(null).decision)
        assertEquals(QringReadyDecision.READY, gate.observe(null).decision)
    }

    @Test fun printingStatusMustClearBeforeStableIdleIsAccepted() {
        val gate = QringReadyGate(mode = QringReadyMode.BETWEEN_COPIES)
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus(printing = true)).decision)
        assertEquals(QringReadyDecision.WAIT, gate.observe(HardwareStatus()).decision)
        assertEquals(QringReadyDecision.READY, gate.observe(HardwareStatus()).decision)
    }
}
