package com.qrint.ppocr

/** Single source of truth for the bundled PP-OCR model contract. */
internal object PpOcrModelSpec {
    const val DISPLAY_NAME = "PP-OCRv6 Small"
    const val MODEL_NAME = "PP-OCRv6_small"
    const val RECOGNITION_MODEL_NAME = "PP-OCRv6_small_rec"
    const val CACHE_DIRECTORY = "ppocr-v6-small"
    const val DICTIONARY_ASSET = "ppocr/v6_small_rec.yml"
    const val DICTIONARY_ENTRY_COUNT = 18_708
    const val RECOGNITION_CLASS_COUNT = 18_710 // CTC blank + dictionary + regular space.
    const val RECOGNITION_HEIGHT = 48

    val LEGACY_CACHE_DIRECTORIES = listOf("ppocr-v5-mobile")

    val DETECTION = ModelAsset(
        assetPath = "ppocr/v6_small_det.onnx",
        fileName = "v6_small_det.onnx",
        bytes = 9_880_512L,
        sha256 = "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",
    )

    val RECOGNITION = ModelAsset(
        assetPath = "ppocr/v6_small_rec.onnx",
        fileName = "v6_small_rec.onnx",
        bytes = 21_159_378L,
        sha256 = "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    )
}

internal data class ModelAsset(
    val assetPath: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String,
)
