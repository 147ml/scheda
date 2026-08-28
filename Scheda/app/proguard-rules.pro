# ========== 应用自身 ==========
-keep class com.scheda.app.** { *; }

# ========== Compose (R8 全模式兼容) ==========
-dontwarn androidx.compose.**
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ========== Gson ==========
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ========== Kotlin 协程 ==========
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ========== 其他 ==========
-keepclassmembers class * extends android.app.Activity { *; }
