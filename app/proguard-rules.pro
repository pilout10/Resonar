# Reglas de ofuscación para el build release.
# Las libs incluyen sus propias reglas "consumer", así que agregamos solo lo nuestro:
# kotlinx.serialization necesita conservar los serializers generados de nuestras clases.

-keepattributes *Annotation*, InnerClasses

-keepclassmembers class com.resonar.app.** {
    *** Companion;
}

-keepclasseswithmembers class com.resonar.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# La excepción con mensaje del servicio de reanudación se loguea; no ofuscar nombres de nuestras clases clave.
-keepnames class com.resonar.app.player.MusicService

# NewPipeExtractor: el decifrador de firmas de YouTube usa Rhino (JS) por reflexión.
# Reglas oficiales del README del extractor.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
