plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.livetranslate"
    compileSdk = 34
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.example.livetranslate"
        minSdk = 29
        targetSdk = 34
        versionCode = 11
        versionName = "0.12.1"

        // 真机 arm64-v8a + 模拟器 x86_64（sherpa-onnx 原生库占体积，过滤冗余 ABI）
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 内嵌 llama.cpp 推理引擎（jni_llm.cpp + NDK 预编译静态库）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    ndkVersion = "26.1.10909125"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // sherpa-onnx 自带 onnxruntime（含 ai.onnxruntime 类，SileroVadEngine 复用）
    implementation("com.github.k2-fsa:sherpa-onnx:1.13.4")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
