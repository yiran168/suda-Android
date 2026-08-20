package com.qrint.studio.printer

object QringProtocol {
    const val DEFAULT_WIDTH_DOTS = 384
    const val CHUNK_SIZE = 1024
    const val CHUNK_DELAY_MS = 1L
    const val ACK_PRINT_DONE = 0xAA
    const val FAULT_FRAME_HEAD = 0xFF

    val ENABLE = bytes(0x10, 0xFF, 0xF1, 0x02)
    val ENABLE_SECONDARY = bytes(0x1F, 0xB2, 0x10)
    val STOP = bytes(0x10, 0xFF, 0xF1, 0x45)
    val WAKE_UP = ByteArray(12)
    val QUERY_STATUS = bytes(0x10, 0xFF, 0x40)
    val QUERY_BATTERY = bytes(0x10, 0xFF, 0x50, 0xF1)
    val QUERY_MODEL = bytes(0x10, 0xFF, 0x20, 0xF0)
    val QUERY_FIRMWARE = bytes(0x10, 0xFF, 0x20, 0xF1)
    val QUERY_SERIAL = bytes(0x10, 0xFF, 0x20, 0xF2)

    const val STATUS_PRINTING = 0x01
    const val STATUS_COVER_OPEN = 0x02
    const val STATUS_NO_PAPER = 0x04
    const val STATUS_LOW_BATTERY = 0x08
    const val STATUS_OVERHEAT = 0x10

    enum class PrintResponseKind { PENDING, COMPLETED, FAULT }

    data class PrintResponse(
        val kind: PrintResponseKind,
        val faultCode: Int? = null,
    )

    fun parseStatus(raw: Int): HardwareStatus = HardwareStatus(
        raw = raw and 0xFF,
        printing = raw and STATUS_PRINTING != 0,
        coverOpen = raw and STATUS_COVER_OPEN != 0,
        noPaper = raw and STATUS_NO_PAPER != 0,
        lowBattery = raw and STATUS_LOW_BATTERY != 0,
        overheat = raw and STATUS_OVERHEAT != 0,
    )

    /**
     * The private Qring command returns two bytes and the second byte is the percentage.
     * Some firmware returns 0xFF when the value is unavailable. Never clamp that sentinel to
     * 100%, otherwise the UI reports a full battery even though no percentage was supplied.
     */
    fun parseBatteryPercent(response: ByteArray): Int? {
        if (response.size < 2) return null
        return (response[1].toInt() and 0xFF).takeIf { it in 0..100 }
    }

    /**
     * Parse all bytes received in one print response window. The upstream protocol gives ACK
     * precedence over unsolicited fault frames, and only FF 01..04 are defined as faults.
     * Consuming/classifying the whole window also prevents bytes after ACK leaking into the next
     * copy of a batch job.
     */
    fun parsePrintResponse(response: ByteArray): PrintResponse {
        if (response.any { (it.toInt() and 0xFF) == ACK_PRINT_DONE }) {
            return PrintResponse(PrintResponseKind.COMPLETED)
        }
        for (index in 0 until response.lastIndex) {
            if ((response[index].toInt() and 0xFF) != FAULT_FRAME_HEAD) continue
            val code = response[index + 1].toInt() and 0xFF
            if (code in 0x01..0x04) return PrintResponse(PrintResponseKind.FAULT, code)
        }
        return PrintResponse(PrintResponseKind.PENDING)
    }

    /** Cover is checked first because an open cover often also raises the paper bit. */
    fun blockingFault(status: HardwareStatus): String? = when {
        status.coverOpen -> "上盖未合好，请合盖后重试"
        status.noPaper -> "检测不到纸张，请检查装纸"
        status.overheat -> "打印头过热，请稍候冷却"
        else -> null
    }

    fun faultFrameMessage(code: Int): String = when (code and 0xFF) {
        0x01 -> "打印中断：缺纸"
        0x02 -> "打印中断：上盖打开"
        0x03 -> "打印中断：打印头过热"
        0x04 -> "打印中断：电量过低"
        else -> "打印机报告未知故障 0x${(code and 0xFF).toString(16)}"
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

data class HardwareStatus(
    val raw: Int = 0,
    val printing: Boolean = false,
    val coverOpen: Boolean = false,
    val noPaper: Boolean = false,
    val lowBattery: Boolean = false,
    val overheat: Boolean = false,
)
