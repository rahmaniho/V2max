-keep class libv2ray.** { *; }
-keep class go.** { *; }

-keep class com.google.gson.** { *; }
-keepclassmembers class ir.v2max.app.data.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# ---------------------------------------------------------------------------
# V2max — قواعد نگه‌داری برای فعال‌سازی R8
# مدل‌ها با Gson (بازتابی) خوانده می‌شوند و نام فیلدها باید حفظ شود.
# ---------------------------------------------------------------------------
-keep class ir.v2max.app.data.** { *; }
-keepclassmembers class ir.v2max.app.data.** { <fields>; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Compose / Activity / ViewModel ها از طریق بازتاب ساخته می‌شوند
-keep class ir.v2max.app.MainActivity { *; }
-keep class ir.v2max.app.service.** { *; }

# OkHttp/Kotlin internals
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
