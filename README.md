# Resonar

Reproductor de música para Android (Kotlin + Jetpack Compose + Media3), liviano y pensado para gama de entrada (probado en un Motorola E15, 2 GB de RAM). Sin Room, sin OkHttp y con inyección de dependencias manual.

**Licencia:** [GPLv3](LICENSE) (requerida por NewPipeExtractor). Si algo externo se rompe (sobre todo YouTube), ver [MANTENIMIENTO.md](MANTENIMIENTO.md).

## Descargar

APK firmado en [**Releases**](https://github.com/pilout10/Resonar/releases): descargar el `.apk` e instalarlo (permitir "instalar de fuentes desconocidas").

## Funciones

- **Biblioteca local y en la nube**: música del teléfono y carpetas elegidas (incluye subcarpetas y FLAC), más carpetas de Google Drive vía el selector del sistema (SAF).
- **Búsqueda y descarga en línea**: busca en paralelo en YouTube, Audius, ccMixter, Openverse, Internet Archive y Jamendo (opcional, con key) y permite descargar temas completos.
- **Reproducción**: cola, aleatorio, repetir, reanudación, ecualizador del sistema y controles en notificación/pantalla de bloqueo.
- **Extras**: radio automática al terminar la cola, temporizador de sueño, playlists/favoritos y borrado limpio (en Drive va a la papelera).
- **Robustez**: validación de respuestas y archivos descargados; 21 tests de unidad (`gradlew testDebugUnitTest`).

> **Nota:** YouTube depende de un extractor de terceros y puede dejar de funcionar temporalmente; las demás fuentes no dependen de él.

## Compilar

Requisitos: JDK 17 y Android SDK 35 (Gradle Wrapper incluido).

```powershell
# en Resonar/
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat installDebug --console=plain
```

Para generar un release firmado hay que configurar el keystore en `local.properties` (no versionado). Jamendo es opcional: `jamendo.client_id=TU_ID` en `local.properties`.
