# kotlinx.serialization keeps its serializers on the companion; R8 cannot see the
# reflective link, so the generated serializers must be kept for every @Serializable
# model or release builds fail to decode Postgrest responses.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.openlibrarykashmir.olk.core.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.openlibrarykashmir.olk.core.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor picks its engine reflectively.
-keep class io.ktor.client.engine.okhttp.** { *; }
-dontwarn org.slf4j.**
