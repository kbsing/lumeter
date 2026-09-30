# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.lumeter.** {
    *** Companion;
}
-keepclasseswithmembers class com.lumeter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
