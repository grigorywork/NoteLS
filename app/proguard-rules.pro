# kotlinx.serialization: keep @Serializable DTO sync-протокола и их сериализаторы.
-keepclassmembers class com.notessync.app.sync.protocol.** {
    <init>(...);
    *** Companion;
}
-keepclasseswithmembers class com.notessync.app.sync.protocol.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.notessync.app.sync.protocol.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature

# Стандартные правила kotlinx.serialization.
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-dontwarn kotlinx.serialization.**

# Room.
-dontwarn androidx.room.**
