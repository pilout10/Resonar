// Plugins de todo el proyecto. Las versiones se declaran acá una sola vez;
// el módulo :app las aplica sin repetir versiones.
// AGP 9.5 trae Kotlin integrado y exige Gradle 9.x (acá, 9.6.0, ya instalado).
plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
