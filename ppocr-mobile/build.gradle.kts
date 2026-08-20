plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qrint.ppocr"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    androidResources {
        // ONNX Runtime memory-maps the copied model files; do not spend build time recompressing
        // the already-compressed protobuf payloads inside the APK.
        noCompress += "onnx"
    }

    testOptions.unitTests.isReturnDefaultValues = true
    lint {
        disable += "GradleDependency"
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    // PP-OCRv6 Small detection uses ONNX IR v10. ORT 1.21.1 supports that contract and declares
    // API 24, so the application intentionally targets Android 7+ instead of rewriting the model.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    testImplementation("junit:junit:4.13.2")
}
