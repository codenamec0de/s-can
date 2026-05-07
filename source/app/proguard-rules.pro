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
