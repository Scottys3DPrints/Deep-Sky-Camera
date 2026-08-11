# The update manifest is parsed with kotlinx.serialization into @Serializable
# models. R8 must keep the generated serializers or the updater fails at runtime
# on exactly the builds that are meant to be updatable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.deepsky.camera.** {
    *** Companion;
}
-keepclasseswithmembers class com.deepsky.camera.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.deepsky.camera.**$$serializer { *; }
