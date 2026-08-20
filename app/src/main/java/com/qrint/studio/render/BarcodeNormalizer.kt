package com.qrint.studio.render

import com.qrint.studio.model.BarcodeType

data class NormalizedBarcode(
    val value: String,
    val changed: Boolean,
    val notice: String,
)

/**
 * Converts imperfect user input into a legal payload. The preview therefore never disappears
 * just because an EAN/UPC length or check digit is wrong.
 */
object BarcodeNormalizer {
    fun normalize(type: BarcodeType, raw: String): NormalizedBarcode {
        val original = raw.trim()
        return when (type) {
            BarcodeType.EAN_13 -> numericWithCheck(original, 12, "EAN-13")
            BarcodeType.JAN_13 -> numericWithCheck(original, 12, "JAN-13")
            BarcodeType.ISBN_13 -> {
                val digits = asciiDigits(original)
                val payload = when {
                    digits.startsWith("978") || digits.startsWith("979") -> digits.take(12).padEnd(12, '0')
                    else -> ("978" + digits).take(12).padEnd(12, '0')
                }
                val value = payload + mod10(payload)
                changed(original, value, "ISBN-13 已补齐 978/979 前缀并重算校验位")
            }
            BarcodeType.ISSN_13 -> {
                val serial = asciiDigits(original).removePrefix("977").take(7).padStart(7, '0')
                val payload = ("977" + serial + "00").take(12).padEnd(12, '0')
                val value = payload + mod10(payload)
                changed(original, value, "ISSN-13 已生成 977 前缀与期次位并重算校验位")
            }
            BarcodeType.EAN_8 -> numericWithCheck(original, 7, "EAN-8")
            BarcodeType.UPC_A -> numericWithCheck(original, 11, "UPC-A")
            BarcodeType.UPC_E -> {
                val digits = asciiDigits(original)
                val payload = digits.takeLast(6).padStart(6, '0')
                changed(original, "0$payload", "已整理为 UPC-E 的 0 + 6 位数据，校验位由编码器生成")
            }
            BarcodeType.ITF -> {
                var digits = asciiDigits(original).ifEmpty { "00" }.take(80)
                if (digits.length % 2 != 0) digits = "0$digits"
                changed(original, digits, "ITF 仅保留数字；奇数位时已在前面补 0")
            }
            BarcodeType.CODE_39, BarcodeType.CODE_93 -> {
                val allowed = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-. $/+%"
                val value = original.uppercase().map { if (it in allowed) it else '-' }.joinToString("")
            .ifEmpty { "LINGYIN-001" }.take(80)
                changed(original, value, "已转换为该码制支持的大写字符")
            }
            BarcodeType.CODABAR -> {
                val body = original.uppercase().filter { it in "0123456789-$:/.+" }.ifEmpty { "12345" }.take(60)
                val value = "A${body.trim('A', 'B', 'C', 'D')}A"
                changed(original, value, "已自动补齐 Codabar 起止字符 A")
            }
            BarcodeType.CODE_128, BarcodeType.GS1_128 -> {
                val value = buildString {
        val fallback = if (type == BarcodeType.GS1_128) "(01)06901234567890(10)BATCH01" else "LINGYIN-2026"
                    original.ifEmpty { fallback }.forEach { char ->
                        if (char.code in 32..126 || char == '\u001D') append(char)
                        else append("U+").append(char.code.toString(16).uppercase())
                    }
                }.take(120)
                changed(original, value, if (type == BarcodeType.GS1_128) "GS1-128 内容已清理为安全可编码文本" else "Code 128 不支持的字符已转换为 U+ 十六进制文本")
            }
            BarcodeType.QR_CODE, BarcodeType.DATA_MATRIX, BarcodeType.PDF_417, BarcodeType.AZTEC,
            BarcodeType.GS1_QR, BarcodeType.GS1_DATA_MATRIX -> {
                val safe = when {
                    original.isNotEmpty() -> original
                    type == BarcodeType.GS1_QR || type == BarcodeType.GS1_DATA_MATRIX -> "(01)06901234567890(10)BATCH01"
                    else -> "https://example.com"
                }
                changed(original, safe.take(2400), "空内容已替换为示例；超长内容会安全截断")
            }
        }
    }

    private fun numericWithCheck(raw: String, payloadLength: Int, label: String): NormalizedBarcode {
        val digits = asciiDigits(raw)
        val payload = digits.take(payloadLength).padStart(payloadLength, '0')
        val value = payload + mod10(payload)
        return changed(raw, value, "$label 已自动清理、补齐并重算校验位")
    }

    private fun asciiDigits(value: String): String = buildString {
        value.forEach { char -> Character.digit(char, 10).takeIf { it >= 0 }?.let(::append) }
    }

    /** GS1/EAN modulo-10 check digit for the data portion. */
    fun mod10(payload: String): Int {
        var sum = 0
        var weightThree = true
        for (index in payload.indices.reversed()) {
            val digit = payload[index].digitToIntOrNull() ?: 0
            sum += digit * if (weightThree) 3 else 1
            weightThree = !weightThree
        }
        return (10 - sum % 10) % 10
    }

    private fun changed(original: String, value: String, reason: String) = NormalizedBarcode(
        value = value,
        changed = original != value,
        notice = if (original == value) "内容有效" else reason,
    )
}
