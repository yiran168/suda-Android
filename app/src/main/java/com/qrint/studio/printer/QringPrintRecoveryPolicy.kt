package com.qrint.studio.printer

import kotlin.math.floor

/** Pure recovery calculations used by the Qring multi-copy transport state machine. */
internal object QringPrintRecoveryPolicy {
    /** SPP 115200 bps is approximately 11.5 KiB/s after serial framing. */
    const val SPP_BYTES_PER_SECOND = 11_520.0

    /** Conservative fallback of roughly 31 mm/s at 203 dpi. */
    const val FALLBACK_ROWS_PER_SECOND = 250.0

    /** Reprint roughly 16 mm at an estimated thermal-stop boundary so content is never omitted. */
    const val HEAT_OVERLAP_ROWS = 128

    const val MAX_HEAT_PAUSES = 6
    const val MAX_RECONNECTS_PER_COPY = 3
    const val COOLDOWN_TIMEOUT_MS = 180_000L
    const val RECONNECT_TIMEOUT_MS = 25_000L
    /**
     * A deliberately slow mechanical-speed floor used after ACK.
     *
     * Several Qring firmware builds acknowledge buffered raster data before the label has cleared
     * the head. Waiting as if the motor only advances at 125 rows/s (about 15.6 mm/s at 203 dpi)
     * prevents the following copy from opening a new session too early.
     */
    const val COMPLETION_ROWS_PER_SECOND = 125.0
    const val MIN_POST_SEND_COMPLETION_MS = 800L
    const val MAX_POST_SEND_COMPLETION_MS = 120_000L

    /**
     * Mandatory quiet time after this firmware's ACK before another ENABLE transaction.
     *
     * The target Qring_50EE accepts a second raster and returns 0xAA while its print engine is
     * still rearming, but never starts the motor for that raster. The supplied device log shows
     * that sessions opened about 1.5 seconds after ACK were swallowed, whereas every session
     * preceded by the existing six-second status fallback printed. Keep a small margin over that
     * measured boundary and apply it to every copy instead of alternating by an unreliable bit.
     */
    const val POST_ACK_REARM_MS = 6_500L

    /**
     * Estimates a safe row from which to resume after an overheat stop.
     *
     * The estimate intentionally subtracts both serial-transfer time and an overlap. If timing is
     * ambiguous, it advances by zero rows and reprints more data instead of risking a missing line.
     */
    fun nextRowOffset(
        currentRow: Int,
        totalRows: Int,
        remainingBytes: Int,
        elapsedMs: Long,
        measuredRowsPerSecond: Double?,
    ): Int {
        if (totalRows <= 1) return 0
        val safeCurrent = currentRow.coerceIn(0, totalRows - 1)
        val transferMs = remainingBytes.coerceAtLeast(0) * 1_000.0 / SPP_BYTES_PER_SECOND
        val printableMs = (elapsedMs.coerceAtLeast(0).toDouble() - transferMs).coerceAtLeast(0.0)
        val speed = measuredRowsPerSecond
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: FALLBACK_ROWS_PER_SECOND
        val estimatedPrintedRows = floor(printableMs / 1_000.0 * speed).toInt()
        val safeAdvance = (estimatedPrintedRows - HEAT_OVERLAP_ROWS).coerceAtLeast(0)
        return (safeCurrent + safeAdvance).coerceAtMost(totalRows - 1)
    }

    /**
     * Creates a conservative checkpoint when transmission stopped before the whole raster was sent.
     *
     * Bluetooth only tells us how many bytes were accepted by the socket, not the exact row already
     * heated by the print head. Converting complete transmitted rows and then rewinding the same
     * overlap keeps the cursor near the interruption while protecting data still buffered in the
     * printer. A partial row is never skipped.
     */
    fun rowOffsetFromTransferredBytes(
        currentRow: Int,
        totalRows: Int,
        widthBytes: Int,
        transferredBytes: Int,
    ): Int {
        if (totalRows <= 1) return 0
        val safeCurrent = currentRow.coerceIn(0, totalRows - 1)
        if (widthBytes <= 0 || transferredBytes <= 0) return safeCurrent
        val completeTransferredRows = transferredBytes / widthBytes
        val safeAdvance = (completeTransferredRows - HEAT_OVERLAP_ROWS).coerceAtLeast(0)
        return (safeCurrent + safeAdvance).coerceAtMost(totalRows - 1)
    }

    fun minimumPostSendCompletionMillis(totalRows: Int): Long {
        val estimated = kotlin.math.ceil(
            totalRows.coerceAtLeast(0) * 1_000.0 / COMPLETION_ROWS_PER_SECOND,
        ).toLong()
        return estimated.coerceIn(MIN_POST_SEND_COMPLETION_MS, MAX_POST_SEND_COMPLETION_MS)
    }

    /** The next session may start only after both the raster and post-ACK deadlines. */
    fun physicalCompletionNotBeforeMillis(
        totalRows: Int,
        rasterSentAtMs: Long,
        ackReceivedAtMs: Long,
    ): Long = maxOf(
        rasterSentAtMs.coerceAtLeast(0L) + minimumPostSendCompletionMillis(totalRows),
        ackReceivedAtMs.coerceAtLeast(0L) + POST_ACK_REARM_MS,
    )

    /** A successful unsliced copy calibrates the printer for later thermal-stop estimates. */
    fun measuredRowsPerSecond(totalRows: Int, mechanicalMs: Long): Double? {
        if (totalRows <= 0 || mechanicalMs < 500L) return null
        val measured = totalRows * 1_000.0 / mechanicalMs
        return measured.takeIf { it in 40.0..1_200.0 }
    }

    fun reconnectDelayMillis(attempt: Int): Long = when (attempt.coerceAtLeast(0)) {
        0 -> 500L
        1 -> 1_000L
        else -> 2_000L
    }
}
