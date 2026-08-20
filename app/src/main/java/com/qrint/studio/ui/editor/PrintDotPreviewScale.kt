package com.qrint.studio.ui.editor

/**
 * Fits the immutable printer-dot raster to the available screen width.
 *
 * The bitmap itself is never re-laid out and [InteractiveLabelCanvas] always draws it with
 * nearest-neighbour filtering. A fractional screen scale therefore only changes how large the
 * physical dots look on this particular display; it cannot change a glyph, line break, barcode,
 * dither pattern, or any byte later sent to the printer.
 */
internal fun printDotPreviewScale(availablePixels: Int, paperWidthDots: Int): Float {
    val safeAvailable = availablePixels.coerceAtLeast(1)
    val safePaper = paperWidthDots.coerceAtLeast(1)
    return safeAvailable.toFloat() / safePaper
}
