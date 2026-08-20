package com.qrint.studio.ui.editor

import kotlin.math.abs

internal data class AxisSnapResult(val correction: Float, val guide: Float?)

/**
 * A light magnetic snap with hysteresis. Small motion can settle exactly on a guide, while several
 * small pointer events accumulate until the object releases; users never have to make one fast
 * gesture merely to escape a paper edge or centre line.
 */
internal class MagneticSnapController(
    private val captureDistanceDots: Float = 2f,
    private val releaseDistanceDots: Float = 6f,
) {
    private var lock: SnapLock? = null

    fun reset() {
        lock = null
    }

    fun apply(sources: List<Float>, targets: List<Float>, pointerMovement: Float): AxisSnapResult {
        if (sources.isEmpty() || targets.isEmpty()) {
            reset()
            return AxisSnapResult(0f, null)
        }
        lock?.let { current ->
            if (current.sourceIndex !in sources.indices) {
                reset()
            } else {
                current.escape += pointerMovement
                if (abs(current.escape) >= releaseDistanceDots) {
                    val catchUp = current.escape - pointerMovement
                    reset()
                    return AxisSnapResult(catchUp, null)
                }
                return AxisSnapResult(current.target - sources[current.sourceIndex], current.target)
            }
        }

        val match = bestSnap(sources, targets, captureDistanceDots) ?: return AxisSnapResult(0f, null)
        lock = SnapLock(match.sourceIndex, match.target)
        return AxisSnapResult(match.correction, match.target)
    }

    private data class SnapLock(val sourceIndex: Int, val target: Float, var escape: Float = 0f)
}

private data class SnapMatch(val sourceIndex: Int, val correction: Float, val target: Float)

private fun bestSnap(sources: List<Float>, targets: List<Float>, threshold: Float): SnapMatch? {
    var best: SnapMatch? = null
    sources.forEachIndexed { sourceIndex, source ->
        targets.forEach { target ->
            val correction = target - source
            if (abs(correction) <= threshold && (best == null || abs(correction) < abs(best!!.correction))) {
                best = SnapMatch(sourceIndex, correction, target)
            }
        }
    }
    return best
}
