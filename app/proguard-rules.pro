# TerraSync R8 / ProGuard keep rules
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# App data models & Android Auto entry points
-keep class com.terrasync.app.LayerItem { *; }
-keep class com.terrasync.app.PinItem { *; }
-keep class com.terrasync.app.TrackItem { *; }
-keep class com.terrasync.app.OfflineRoadRegion { *; }
-keep class com.terrasync.app.TerraSyncCarAppService { *; }
-keep class com.terrasync.app.TerraSyncCarSession { *; }

# Keep Rust JNI bridge methods
-keep class com.terrasync.app.RustCoreBridge { *; }

# MapLibre GL, Firebase, AndroidX Car App & OkHttp bundle their own consumer rules;
# keep MapLibre native/HTTP hooks surgically instead of blanket SDK wildcards.
-keepclasseswithmembers class org.maplibre.** {
    native <methods>;
}
-keep class org.maplibre.android.module.http.** { *; }
-dontwarn org.maplibre.**
-dontwarn com.google.firebase.**
-dontwarn androidx.car.app.**
-dontwarn okhttp3.**
-dontwarn okio.**
