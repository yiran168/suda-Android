# PP-OCR Mobile module

This Android 7+ library owns the complete local OCR pipeline used by both gallery imports and the
CameraX capture workflow:

- official, unmodified PP-OCRv6 Small detection (IR v10) and recognition ONNX models;
- one process-wide pair of reusable ONNX Runtime sessions;
- one shared BGR preprocessing contract, plus pure Kotlin/Android DB post-processing and perspective crop;
- bounded one-line-at-a-time recognition (`1920 px` maximum recognition width by default);
- startup validation for model hashes, tensor shapes and the 18,710-class CTC contract;
- explicit native-session release on low-memory callbacks.

There is intentionally no OpenCV, RapidOCR, ML Kit or network model download path in this module.
