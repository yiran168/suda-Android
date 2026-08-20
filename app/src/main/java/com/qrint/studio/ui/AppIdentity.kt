package com.qrint.studio.ui

import com.qrint.studio.ProductIdentity

/**
 * Single source of truth for product-facing identity and introduction copy.
 * Keep protocol attribution separate from product claims so the relationship
 * with the upstream project remains unambiguous wherever this copy is reused.
 */
internal object AppIdentity {
    const val NAME = ProductIdentity.NAME
    const val TAGLINE = "把每一毫米纸面，变成可编辑、可预览、可复现的打印作品"
    val SUMMARY =
        "$NAME 是一款本地优先的便携式热敏打印创作工具，面向连续纸与标签纸，把文字、图片、二维码、一维条码、形状、OCR、行业模板和变量数据统一放进可编辑画布。"
    const val PRINT_WORKFLOW =
        "从毫米级纸张设置、203 dpi 同源预览到蓝牙发送打印，编辑与输出共用同一套坐标和点阵流程；连续纸按内容自动算长，标签纸按设定宽高输出。"
    const val LOCAL_FIRST =
        "核心编辑、模板、OCR、历史与设置均在设备本地完成；应用不申请网络权限，未连接打印机时也可以先完成创作与预览。"
    const val UPSTREAM_PROJECT_URL = "https://github.com/Thisko/QrintPrint"
    const val ANDROID_PROJECT_URL = "https://github.com/yiran168/suda-Android"
    const val DESKTOP_PROJECT_URL = "https://github.com/yiran168/suda-win-web"
    const val WEB_APP_URL = "https://yiran168.github.io/suda-win-web/"
}
