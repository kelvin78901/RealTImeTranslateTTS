plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // 在 AGP 8+ 使用 androidResources 配置不压缩的资源后缀，避免大型模型被压缩或打包异常
    androidResources {
        noCompress += listOf("onnx", "txt")
    }
    namespace = "com.example.myapplication1"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.myapplication1"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 仅打包 arm64 架构，减小 APK 体积
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles("proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // 某些三方 AAR 使用非标准目录，如 x86-64
            excludes += setOf("**/x86-64/**")

            // 避免重复的 libonnxruntime.so 冲突（sherpa-onnx.aar 与 onnxruntime-android）
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/x86/libonnxruntime.so",
                "lib/x86_64/libonnxruntime.so",
                "lib/arm64-v8a/libonnxruntime4j_jni.so",
                "lib/armeabi-v7a/libonnxruntime4j_jni.so",
                "lib/x86/libonnxruntime4j_jni.so",
                "lib/x86_64/libonnxruntime4j_jni.so"
            )

            // 兼容传统打包方式（部分 Android 版本需要）
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions { jvmTarget = "11" }

    buildFeatures { compose = true }
}

dependencies {
    // ---- Jetpack Compose ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")

    // ---- 小组件 ----
    implementation("androidx.glance:glance-appwidget:1.1.1")

    // ---- Vosk 离线语音识别 ----
    implementation("com.alphacephei:vosk-android:0.3.47") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // ---- MLKit 翻译 ----
    implementation("com.google.mlkit:translate:17.0.2")

    // ---- Kotlin 协程 ----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // MLKit 自定义模型
    implementation("com.google.mlkit:linkfirebase:16.0.0")
    implementation("com.google.firebase:firebase-ml-model-interpreter:22.0.3")

    // 音频播放
    implementation("androidx.media:media:1.6.0")

    // ---- OkHttp (Edge TTS WebSocket) ----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- ONNX Runtime (离线翻译模型推理) ----
    // MUST match the version bundled in sherpa-onnx.aar (1.17.1) — version mismatch
    // causes native heap corruption (SIGABRT "pthread_mutex_lock on destroyed mutex").
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.1")

    implementation(files("libs/sherpa-onnx.aar"))
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.name == "sherpa-onnx") {
                useTarget(files("libs/sherpa-onnx.aar"))
            }
        }
    }




    // ---- 测试 ----
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
