package com.qrint.studio.model

data class PrintFontOption(
    val key: String,
    val title: String,
    val description: String,
    val bundled: Boolean = false,
)

data class PrintFontWeightPreset(val value: Int, val title: String)

/** One shared catalog feeds property UI and the Android Canvas renderer. */
object PrintFontCatalog {
    const val MIN_WEIGHT = 100
    const val MAX_WEIGHT = 900
    const val REGULAR_WEIGHT = 400
    const val BOLD_WEIGHT = 700
    const val MA_SHAN_ZHENG = "bundled:ma_shan_zheng"
    const val LONG_CANG = "bundled:long_cang"
    const val ZHI_MANG_XING = "bundled:zhi_mang_xing"
    const val LIU_JIAN_MAO_CAO = "bundled:liu_jian_mao_cao"

    val options = listOf(
        PrintFontOption(MA_SHAN_ZHENG, "马善政毛笔", "粗犷醒目的标题手写体", true),
        PrintFontOption(LONG_CANG, "龙藏书法", "舒展的行书风格", true),
        PrintFontOption(ZHI_MANG_XING, "志莽行书", "有速度感的签名字体", true),
        PrintFontOption(LIU_JIAN_MAO_CAO, "刘建毛草", "自由的毛笔草书", true),
        PrintFontOption("sans-serif", "系统黑体", "清晰通用"),
        PrintFontOption("sans-serif-light", "轻黑体", "轻盈说明文字"),
        PrintFontOption("sans-serif-thin", "纤细黑体", "细线大字号"),
        PrintFontOption("sans-serif-medium", "系统中黑", "小字号更清楚"),
        PrintFontOption("sans-serif-black", "特粗黑体", "促销标题"),
        PrintFontOption("sans-serif-condensed", "窄体", "有限宽度容纳更多字"),
        PrintFontOption("sans-serif-condensed-light", "窄体细字", "紧凑说明"),
        PrintFontOption("sans-serif-condensed-medium", "窄体中黑", "紧凑且醒目"),
        PrintFontOption("sans-serif-smallcaps", "小型大写", "英文标签"),
        PrintFontOption("serif", "系统宋体", "正文与传统风格"),
        PrintFontOption("serif-monospace", "等宽衬线", "票据编号"),
        PrintFontOption("monospace", "等宽字体", "序列号与代码"),
        PrintFontOption("cursive", "系统手写体", "随设备字库变化"),
        PrintFontOption("casual", "系统休闲体", "随设备字库变化"),
    )

    val weightPresets = listOf(
        PrintFontWeightPreset(REGULAR_WEIGHT, "常规"),
        PrintFontWeightPreset(BOLD_WEIGHT, "粗体"),
    )

    fun title(key: String): String = options.firstOrNull { it.key == key }?.title ?: key
    fun normalizeWeight(value: Int): Int = value.coerceIn(MIN_WEIGHT, MAX_WEIGHT)
}
