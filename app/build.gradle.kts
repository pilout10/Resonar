import java.util.Properties

// Módulo principal de Resonar.
//
// Decisiones de versión:
//  - minSdk 24: cubre la gran mayoría de Android ( requisito pedido ).
//  - targetSdk 35: la versión estable más reciente (Android 15).
//  - Java 17: bytecode de la app. AGP 9 aplica Kotlin solo; no hace falta el plugin kotlin.android.
//
// CONFIG LOCAL (no se versiona): la key de Jamendo se lee de "local.properties".
// Es gratuita, se pide en https://devportal.jamendo.com/signup y se usa así:
//
//     jamendo.client_id=TU_CLIENT_ID
//
// Si la línea no está, la fuente Jamendo se saltea sola y el resto de la app
// funciona igual (no se rompe nada por falta de key).
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val jamendoClientId = (localProps.getProperty("jamendo.client_id") ?: "").trim()

// FIRMA DE RELEASE (auditoría): el keystore y su contraseña viven en
// local.properties (NO versionado). Sin esas líneas, assembleRelease produce
// un APK sin firmar como antes (no rompe builds de nadie más).
val releaseStoreFile = (localProps.getProperty("release.store_file") ?: "").trim()
val releaseStorePassword = localProps.getProperty("release.store_password") ?: ""
val releaseKeyAlias = (localProps.getProperty("release.key_alias") ?: "").trim()
val releaseKeyPassword = localProps.getProperty("release.key_password") ?: ""
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.resonar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.resonar.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "1.0"

        // Key de Jamendo (fuente en línea). Vacía = la fuente se saltea sola.
        buildConfigField("String", "JAMENDO_CLIENT_ID", "\"$jamendoClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Firma real si el keystore está configurado (está en local.properties,
            // fuera del repo). Antes salía sin firmar: no instalable ni distribuible.
            signingConfig = if (releaseStoreFile.isNotBlank()) {
                signingConfigs.create("release") {
                    storeFile = file(releaseStoreFile)
                    storePassword = releaseStorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            } else {
                null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor usa java.time y otras APIs modernas: en minSdk 24 hay
        // que desugarizar (api-level 33+ las trae nativas).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ── AndroidX base ──────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Jetpack Compose + Material 3 ───────────────────────────────────
    // El BOM fija versiones compatibles de todas las libs de Compose.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ── Media3: reproducción + sesión multimedia ───────────────────────
    // exoplayer: motor de audio. session: servicio en segundo plano, notificación
    // con controles, pantalla de bloqueo, reanudación. common: tipos base.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // ── Persistencia liviana de sesión ─────────────────────────────────
    // SharedPreferences + JSON. Room entra en la Etapa 2 (caché de búsquedas con TTL);
    // el estado del player acá alcanza y pesa nada.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── Carátulas con tamaño acotado ───────────────────────────────────
    // Coil: decodifica al tamaño pedido (96–512 px), no al original. Es CLAVE
    // para la RAM en el Motorola E15.
    implementation("io.coil-kt:coil-compose:2.6.0")

    // ── Tests de unidad (auditoría) ─────────────────────────────────────
    // Cubren justo los defectos que la auditoría encontró y corrigió.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")

    // ── YouTube vía NewPipeExtractor (ETAPA 2B) ────────────────────────
    // Extrae búsqueda y streams de YouTube SIN YouTube API key. Es GPLv3:
    // Resonar queda GPLv3 por usarla (nota en Ajustes). Llega por JitPack.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")

    // Desugaring exigido por NewPipeExtractor con minSdk < 33.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
