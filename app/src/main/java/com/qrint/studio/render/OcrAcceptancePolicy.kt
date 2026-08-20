package com.qrint.studio.render

/**
 * Shared acceptance thresholds for still-image PP-OCR and lightweight live code-frame quality.
 *
 * The still-photo confidence gate is not a claim about ground-truth recognition accuracy. Real
 * accuracy remains measured only from text the user has checked and corrected in OcrQualityStore.
 */
object OcrAcceptancePolicy {
    const val MIN_MODEL_CONFIDENCE = 0.60f
    const val MIN_FRAME_LUMINANCE = 34f
    const val MAX_FRAME_LUMINANCE = 238f
    const val MIN_FRAME_CONTRAST = 16f
    const val MIN_FRAME_EDGE_STRENGTH = 5f
}
