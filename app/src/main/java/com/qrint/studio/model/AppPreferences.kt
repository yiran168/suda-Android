package com.qrint.studio.model

enum class AppThemeStyle(val title: String, val description: String) {
    AURORA("极光工作台", "流动渐变、柔和大圆角与悬浮层次"),
    PAPER("纸感手账", "暖白纸张、细描边与衬线标题"),
    INK("黑白编辑部", "高对比黑白、利落直角与红色批注"),
    MINT("薄荷实验室", "清爽薄荷、模块化网格与精密感"),
    SUNSET("落日气泡", "珊瑚橙粉、胶囊轮廓与轻快节奏"),
    NEON("深夜霓虹", "深色底、青紫高光与数字终端气质"),
    FROST_GLASS("冰晶玻璃", "冷白磨砂、冰蓝边缘与安静悬浮层"),
    LIQUID_GLASS("液态玻璃", "流体高光、海蓝紫渐变与柔软胶囊"),
    SMOKE_GLASS("烟熏玻璃", "深灰半透、金属描边与暗场聚焦"),
    PRISM_GLASS("棱镜玻璃", "虹彩折射、非对称切角与明亮光斑"),
}

enum class PrintSoundPreset(val title: String, val description: String) {
    SILENT("静音", "发送时不播放声音"),
    PAPER_TICK("纸张轻点", "短促、克制的纸张点击"),
    CLEAN_CHIME("清澈提示", "两音确认铃"),
    BUBBLE_POP("气泡弹出", "柔软上扬的气泡声"),
    LASER_PULSE("激光脉冲", "快速电子扫频"),
    WOOD_BLOCK("木鱼轻敲", "温和木质敲击"),
    RECEIPT_RUN("小票出纸", "模拟热敏机启动节奏"),
    SPARKLE("星光闪烁", "三音轻盈琶音"),
    WATER_DROP("水滴确认", "清亮下落音"),
    SUCCESS_FANFARE("完成短曲", "明快的成功提示"),
    RETRO_BEEP("复古终端", "8-bit 双音提示"),
    MECHANICAL("机械咔哒", "打印机构件的短促节拍"),
    BELL("桌面小铃", "圆润单铃确认"),
    RANDOM("随机内置", "每次从 12 种内置声音随机选择"),
    GENERATIVE("随机生成", "每次按本地算法生成不同旋律"),
}

data class AppPreferences(
    val theme: AppThemeStyle = AppThemeStyle.AURORA,
    val printSound: PrintSoundPreset = PrintSoundPreset.PAPER_TICK,
)
