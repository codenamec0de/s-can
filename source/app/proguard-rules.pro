# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Retrofit models
-keepattributes Signature
-keepattributes *Annotation*

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Gson
-keep class com.uow.scan.model.** { *; }

# Keep all API service interfaces + their nested request/response data classes
# (Retrofit + Gson reflection on @SerializedName / property names — must survive R8).
-keep interface com.uow.scan.api.** { *; }
-keep class com.uow.scan.api.** { *; }
-keep class com.uow.scan.api.**$* { *; }

# Kotlin metadata — used by reflective callers (Gson, Retrofit's KotlinDefaultsConverter).
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$Companion { *; }
-keepclasseswithmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Coroutines — keep continuations + service loader entries.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Firebase
-keep class com.google.firebase.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# App data entities
-keep class com.uow.scan.data.** { *; }

# Keep API models (Gson serialization)
-keep class com.uow.scan.api.ScanAiApiService$* { *; }

# Keep BuildConfig
-keep class com.uow.scan.BuildConfig { *; }
