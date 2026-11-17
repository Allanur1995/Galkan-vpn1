# Galkan VPN ProGuard Rules
-keepattributes *Annotation*
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.example.model.** { *; }
-keep class com.android.billingclient.api.** { *; }
-keep class com.google.android.gms.ads.** { *; }
