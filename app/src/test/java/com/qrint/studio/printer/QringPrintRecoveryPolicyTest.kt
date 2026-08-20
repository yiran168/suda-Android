package com.qrint.studio.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QringPrintRecoveryPolicyTest {
    @Test fun ambiguousOverheatTimingReprintsFromTheSameRowInsteadOfSkippingContent() {
        assertEquals(
            200,
            QringPrintRecoveryPolicy.nextRowOffset(
                currentRow = 200,
                totalRows = 2_000,
                remainingBytes = 40_000,
                elapsedMs = 200,
                measuredRowsPerSecond = null,
            ),
        )
    }

    @Test fun overheatResumeAlwaysRewindsTheConfiguredOverlap() {
        val next = QringPrintRecoveryPolicy.nextRowOffset(
            currentRow = 0,
            totalRows = 2_000,
            remainingBytes = 0,
            elapsedMs = 2_000,
            measuredRowsPerSecond = 250.0,
        )
        assertEquals(500 - QringPrintRecoveryPolicy.HEAT_OVERLAP_ROWS, next)
    }

    @Test fun resumeRowNeverMovesPastTheLastPrintableRow() {
        assertEquals(
            999,
            QringPrintRecoveryPolicy.nextRowOffset(
                currentRow = 900,
                totalRows = 1_000,
                remainingBytes = 0,
                elapsedMs = 60_000,
                measuredRowsPerSecond = 400.0,
            ),
        )
    }

    @Test fun speedCalibrationRejectsShortOrImplausibleSamples() {
        assertNull(QringPrintRecoveryPolicy.measuredRowsPerSecond(500, 300))
        assertNull(QringPrintRecoveryPolicy.measuredRowsPerSecond(5_000, 500))
        assertTrue(QringPrintRecoveryPolicy.measuredRowsPerSecond(500, 2_000) == 250.0)
    }

    @Test fun partialTransferCheckpointUsesOnlyCompleteRowsAndRewindsOverlap() {
        val widthBytes = 48
        val transmittedRows = 500
        assertEquals(
            200 + transmittedRows - QringPrintRecoveryPolicy.HEAT_OVERLAP_ROWS,
            QringPrintRecoveryPolicy.rowOffsetFromTransferredBytes(
                currentRow = 200,
                totalRows = 2_000,
                widthBytes = widthBytes,
                transferredBytes = transmittedRows * widthBytes + widthBytes / 2,
            ),
        )
    }

    @Test fun partialTransferWithoutOneOverlapNeverAdvancesTheCursor() {
        assertEquals(
            320,
            QringPrintRecoveryPolicy.rowOffsetFromTransferredBytes(
                currentRow = 320,
                totalRows = 2_000,
                widthBytes = 48,
                transferredBytes = (QringPrintRecoveryPolicy.HEAT_OVERLAP_ROWS - 1) * 48,
            ),
        )
    }

    @Test fun everyCopyGetsAHeightBasedMechanicalCompletionWindow() {
        assertEquals(
            QringPrintRecoveryPolicy.MIN_POST_SEND_COMPLETION_MS,
            QringPrintRecoveryPolicy.minimumPostSendCompletionMillis(1),
        )
        assertEquals(
            8_000L,
            QringPrintRecoveryPolicy.minimumPostSendCompletionMillis(1_000),
        )
        assertEquals(
            QringPrintRecoveryPolicy.MAX_POST_SEND_COMPLETION_MS,
            QringPrintRecoveryPolicy.minimumPostSendCompletionMillis(100_000),
        )
    }

    @Test fun shortRasterStillGetsTheFullPostAckFirmwareRearmWindow() {
        assertEquals(
            20_000L + QringPrintRecoveryPolicy.POST_ACK_REARM_MS,
            QringPrintRecoveryPolicy.physicalCompletionNotBeforeMillis(
                totalRows = 256,
                rasterSentAtMs = 18_000L,
                ackReceivedAtMs = 20_000L,
            ),
        )
    }

    @Test fun veryTallRasterCannotBypassItsHeightBasedDeadline() {
        assertEquals(
            80_000L,
            QringPrintRecoveryPolicy.physicalCompletionNotBeforeMillis(
                totalRows = 10_000,
                rasterSentAtMs = 0L,
                ackReceivedAtMs = 1_000L,
            ),
        )
    }
}
