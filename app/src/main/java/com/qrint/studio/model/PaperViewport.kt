package com.qrint.studio.model

import kotlin.math.max
import kotlin.math.min

/**
 * Maps the physical paper shown by the editor to the fixed-width thermal head raster.
 *
 * A 384-dot / 203-dpi head is about 48 mm wide although the roll can be 57 mm. Wider paper
 * therefore shows real unprintable side margins. Narrower paper crops the head raster from the
 * left, centre, or right according to how the user physically loaded the roll.
 */
data class PaperViewport(
    val paperWidthDots: Int,
    val headWidthDots: Int,
    val sourceStartX: Int,
    val sourceWidthDots: Int,
    val destinationStartX: Int,
) {
    fun headToPaperX(headX: Float): Float = headX - sourceStartX + destinationStartX
    fun paperToHeadX(paperX: Float): Float = paperX + sourceStartX - destinationStartX

    companion object {
        /** Physical paper, including real side margins that cannot be heated by the print head. */
        fun from(paper: PaperSettings): PaperViewport {
            val head = paper.headDots.coerceAtLeast(8)
            val physical = paper.paperWidthDots().coerceAtLeast(8)
            return if (physical >= head) {
                val free = physical - head
                val destination = when (paper.horizontalAnchor) {
                    HorizontalAnchor.LEFT -> 0
                    HorizontalAnchor.CENTER -> free / 2
                    HorizontalAnchor.RIGHT -> free
                }
                PaperViewport(
                    paperWidthDots = physical,
                    headWidthDots = head,
                    sourceStartX = 0,
                    sourceWidthDots = head,
                    destinationStartX = destination,
                )
            } else {
                val free = max(0, head - physical)
                val start = when (paper.horizontalAnchor) {
                    HorizontalAnchor.LEFT -> 0
                    HorizontalAnchor.CENTER -> free / 2
                    HorizontalAnchor.RIGHT -> free
                }
                PaperViewport(
                    paperWidthDots = physical,
                    headWidthDots = head,
                    sourceStartX = start.coerceIn(0, max(0, head - 8)),
                    sourceWidthDots = min(physical, head - start).coerceAtLeast(8),
                    destinationStartX = 0,
                )
            }
        }

        /**
         * Editor viewport for the exact printable strip of [paper].
         *
         * A 57 mm roll is wider than a 384-dot head. Showing those unprintable margins in the
         * editor made the canvas unnecessarily narrow and made the right selection edge appear
         * unreachable. The editor instead fills its width with the exact source-dot window that
         * the printer can heat. [from] remains available to final physical-paper previews.
         */
        fun forEditor(paper: PaperSettings): PaperViewport {
            val physical = from(paper)
            return physical.copy(
                paperWidthDots = physical.sourceWidthDots,
                destinationStartX = 0,
            )
        }
    }
}
