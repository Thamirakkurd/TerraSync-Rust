# TerraSync R8 / ProGuard keep rules
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# App data models
-keep class com.terrasync.app.LayerItem { *; }
-keep class com.terrasync.app.PinItem { *; }
-keep class com.terrasync.app.TrackItem { *; }
-keep class com.terrasync.app.OfflineRoadRegion { *; }
-keep class com.terrasync.app.TerraSyncCarAppService { *; }
-keep class com.terrasync.app.TerraSyncCarSession { *; }

# MapLibre GL & GeoJSON
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Firebase / Firestore / Auth
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# AndroidX Car App
-keep class androidx.car.app.** { *; }
-dontwarn androidx.car.app.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Rust JNI bridge methods
-keep class com.terrasync.app.RustCoreBridge { *; }
