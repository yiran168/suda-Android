package com.qrint.studio.data

data class IndustryCategory(
    val name: String,
    val description: String,
)

/** Single source of truth shared by the home screen, filters and category routing. */
object IndustryCatalog {
    const val ALL = "全部"

    val categories = listOf(
        IndustryCategory("通用", "通用标签"),
        IndustryCategory("商业零售", "各行业的商业零售标签"),
        IndustryCategory("餐饮服务", "奶茶餐饮类型标签"),
        IndustryCategory("医药行业", "医院药店类型标签"),
        IndustryCategory("办公管理", "固资文档标识类型标签"),
        IndustryCategory("通讯电力", "线缆通讯系列标签"),
        IndustryCategory("居家生活", "居家生活标签"),
        IndustryCategory("生产制造", "质检标识标签"),
        IndustryCategory("仓储物流", "仓储快递类型标签"),
        IndustryCategory("收款码", "收款码类型标签"),
        IndustryCategory("直播带货", "适用于直播行业标签模板"),
        IndustryCategory("其他场景", "场景类型标签"),
    )

    fun normalize(value: String): String = when {
        value == ALL -> ALL
        categories.any { it.name == value } -> value
        else -> ALL
    }
}
