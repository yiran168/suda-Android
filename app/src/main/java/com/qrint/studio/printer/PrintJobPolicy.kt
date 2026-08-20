package com.qrint.studio.printer

import com.qrint.studio.model.PaperMode
import com.qrint.studio.model.PaperSettings
import com.qrint.studio.render.RasterData
import com.qrint.studio.render.RasterEncoder

/**
 * Paper-motion rules shared by every editor and both Bluetooth protocols.
 * Keeping this independent from the transport prevents one creation mode from accidentally
 * omitting the final feed that makes the printed content reachable at the tear bar.
 */
internal object PrintJobPolicy {
    const val MIN_MANUAL_FEED_MM = 0.1f
    const val MAX_MANUAL_FEED_MM = 100f

    data class ManualFeedPlan(
        val dots: Int,
        val commands: List<ByteArray>,
    )

    fun trailingFeedDots(paper: PaperSettings): Int {
        val millimetres = when (paper.mode) {
            PaperMode.LABEL -> paper.labelGapMm
            PaperMode.CONTINUOUS -> paper.tailFeedMm
        }
        return paper.mmToDots(millimetres).coerceAtLeast(0)
    }

    fun trailingFeedCommands(paper: PaperSettings): List<ByteArray> =
        RasterEncoder.feedCommands(trailingFeedDots(paper))

    fun isManualFeedDistanceValid(distanceMm: Float?): Boolean =
        distanceMm != null && distanceMm.isFinite() &&
            distanceMm in MIN_MANUAL_FEED_MM..MAX_MANUAL_FEED_MM

    fun manualFeedPlan(paper: PaperSettings, distanceMm: Float): ManualFeedPlan {
        require(isManualFeedDistanceValid(distanceMm)) {
            "走纸距离必须在 0.1–100 mm 之间"
        }
        val dots = paper.mmToDots(distanceMm).coerceAtLeast(1)
        return ManualFeedPlan(dots, RasterEncoder.feedCommands(dots))
    }

    /** Qring-safe strategy: motor travel is encoded in each copy's printable raster height. */
    fun qringRasterWithTrailingFeed(raster: RasterData, paper: PaperSettings): RasterData =
        RasterEncoder.appendBlankRows(raster, trailingFeedDots(paper))
}
