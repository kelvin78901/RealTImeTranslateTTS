# ===== Sherpa-ONNX =====
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# ===== Vosk =====
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# ===== JNA =====
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ===== MLKit =====
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ===== Kotlin/Coroutines =====
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ===== 防止资源引用丢失 =====
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*, InnerClasses
