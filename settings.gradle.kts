pluginManagement {
    repositories {
        // 插件仓库
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 允许模块（如 app）自己声明 flatDir 等仓库
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)

    repositories {
        // 主仓库：Android 和 Jetpack 依赖
        google()
        // Kotlin、Firebase、MLKit、Vosk 等依赖
        mavenCentral()

        // 仅用于解析本地 AAR（如 sherpa-onnx.aar）
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "MyApplication12"
include(":app")
