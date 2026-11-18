# Resonar

App de música para Android (Kotlin + Jetpack Compose + Media3). Pensada para
**gama de entrada** (probada en un Motorola E15, 2 GB de RAM): sin Room, sin OkHttp,
sin librerías de red extra, con DI manual.

**Licencia GPLv3** — obligatoria porque usa NewPipeExtractor (ver
[MANTENIMIENTO.md](MANTENIMIENTO.md)). Si se distribuye el APK, el código tiene
que poder obtenerse.

Si algo externo se rompe (sobre todo YouTube), el procedimiento está en
**[MANTENIMIENTO.md](MANTENIMIENTO.md)**.

## Qué hace hoy (verificado en el teléfono)

- **Biblioteca local**: lee MediaStore y, si elegís una carpeta, esa carpeta y sus
  subcarpetas (FLAC entra). Filtra WhatsApp, tonos, alarmas y notificaciones.
- **Carpetas en la nube (Google Drive)**: se eligen con el selector del sistema.
  Los archivos se transmiten por red; por eso la app los baja al teléfono
  (descarga automática) y guarda en disco lo que escuchás (caché de streaming,
  256 MB con desalojo LRU).
- **Reproducción**: Media3 + MediaSession. Reanuda desde donde quedaste, cola,
  shuffle, repeat, ecualizador del sistema, pantalla de bloqueo y notificación.
- **Búsqueda en línea**: YouTube (vía NewPipeExtractor), Audius, ccMixter,
  Openverse, Internet Archive y Jamendo (con key propia) — en paralelo, con
  filtro de música (canciones / videos) y botón de descarga.
- **Descargas**: se guardan en `Descargas/` dentro de la carpeta elegida (Drive),
  en `Music/Resonar` (opción "Guardar en Music/", visible por otros
  reproductores), o en la privada de la app. Servicio en primer plano con
  pausa/reanudar, reintento automático y opción "solo por Wi-Fi". Se ven en
  "Tu música".
- **Borrado limpio**: al borrar una canción se borra su copia local y su caché;
  en Drive va a la papelera (se puede recuperar).
- **Radio automática** (opcional): cuando la cola termina, suma sola canciones
  parecidas — mismo artista, tu biblioteca, o búsqueda en línea. Anti-duplicado
  por id y por título+artista normalizado.
- **Temporizador de sueño**: 15/30/45/60 min o "al terminar esta canción".
- **Ajustes por grupos** con subpáginas, ecualizador, calidad de audio, ahorro
  de datos y más.
- **Robustez auditada**: respuestas de red con tope de tamaño, validación de
  que lo bajado sea audio de verdad, cola de descargas sin carreras de datos.
  Cubierto por 21 tests de unidad (`gradlew testDebugUnitTest`).

## Qué NO hace todavía (honestidad)

- Las **carátulas** de los archivos que no las traen de origen: si el mp3 no tiene
  una imagen embebida y la carpeta no tiene un `cover.jpg`/`folder.jpg` al lado,
  se muestra la nota musical (es lo único que hay para dibujar). Las descargas
  en línea sí muestran la portada de la fuente (YouTube, Audius) y las carpetas
  locales leen la carátula de carpeta, que es el estándar de la industria.
- Los **nombres** se parten como `Tema - Artista`. Un archivo llamado
  `Artista - Tema` se muestra invertido, salvo los descargados por Resonar
  (llevan la marca `[fuente_id]`).
- **YouTube** puede dejar de funcionar de un día a otro (cambios del sitio,
  verificaciones anti-bot). No es un bug de la app; Audius e Internet Archive
  no dependen de ese servicio.

## Descargar la app (APK)

Los APK firmados se publican en
[Releases](https://github.com/pilout10/Resonar/releases): descargar, tocar el
archivo y permitir "instalar de fuentes desconocidas". Cada APK está firmado
con una clave de release propia; las actualizaciones se instalan encima sin
perder datos (si la clave cambia, hay que reinstalar — por eso la clave vive
respaldada FUERA de este repositorio, junto con su contraseña en
`local.properties`, que no se versiona). Compilar el release:

```
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleRelease --console=plain
# APK: app/build/outputs/apk/release/app-release.apk
```

## Fuentes en línea (buscador federado)

Se consultan **en paralelo** (máximo 3 a la vez, timeout de 8 s por fuente):
una fuente caída no rompe a las demás, y los resultados se intercalan por
relevancia global, no agrupados por fuente.

| Fuente | Qué es | Qué entrega |
|---|---|---|
| **YouTube** | NewPipeExtractor (extractor, no API) | Tema entero. Carátula de YouTube |
| **Deezer** | API oficial, sin key | **Fuera de las búsquedas**: su API pública da un previo de 30 s, no el tema entero. Solo se usa como fuente de metadatos (ISRC, año, sello, carátula grande) |
| **Audius** | API oficial, sin key | Tema entero (MP3) |
| **ccMixter** | API oficial, sin key | Tema entero en MP3/FLAC, con licencia Creative Commons |
| **Openverse** | API oficial, sin key | Agregador (Jamendo, Freesound, Wikimedia): tema entero con su licencia |
| **Internet Archive** | API oficial, sin key | Tema entero (MP3/M4A/OGG/FLAC), dominio público y CC |
| **Jamendo** | API oficial, **requiere key** | Tema entero. Se saltea sola si no hay key |

- **Key de Jamendo** (gratis): en `local.properties` → `jamendo.client_id=TU_ID`.
  Sin la línea, la fuente no se usa y el resto de la app funciona igual.
- **SoundCloud** quedó afuera: su API exige un `client_id` que hoy no se puede
  descubrir de forma confiable (el que sale del bundle web da 403/404).
- **Free Music Archive** quedó afuera: su API JSON ya no existe (devuelve HTML).
- **Todas las fuentes de la lista dan el tema COMPLETO**: no se incluyen
  fuentes que solo entreguen previews.
- Si una búsqueda no devuelve nada, se reintenta sola con variantes de la
  consulta (sin paréntesis, sin "feat", sin "official", sin tildes).

### Descargar con calidad (2B)

- Antes de bajar, si la fuente tiene **más de una versión real**, se abre una
  hoja con las calidades que la fuente de verdad ofrece (formato, bitrate y
  tamaño cuando los dice). Ejemplo real: ccMixter ofrece **MP3 (CBR) y FLAC
  (VBR)** del mismo tema. No se inventan opciones.
- Los resultados se **cachean 24 h** (archivo JSON de ~8 KB, tope de 30
  consultas). Solo se cachea una búsqueda **completa**: si una fuente falló, se
  vuelve a consultar.
- **Pegar un enlace**: YouTube (watch / youtu.be / shorts), Audius, Internet
  Archive, Openverse y archivos de audio directos.
- **Enriquecimiento con MusicBrainz** (en segundo plano, no bloquea nada):
  completa la carátula que faltaba y trae el ISRC cuando existe. El ISRC se
  usa además para saber si dos resultados son la misma grabación.

## Compilar

Requisitos: JDK 17 (el de Android Studio sirve), Android SDK 35, y GradleWrapper
incluido en `gradle/wrapper/`.

Desde la línea de comandos (en `Resonar/`):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat installDebug --console=plain
```

Sale con código 1 aunque diga `BUILD SUCCESSFUL` (comportamiento del wrapper en
PowerShell): si aparece `Installed on 1 device`, la app quedó en el teléfono.

En Android Studio: abrir la carpeta `Resonar/` → sincronizar Gradle → **Run ▶**
con el Motorola E15 conectado por USB.

## Estructura

```
Resonar/
├─ settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/wrapper/
├─ MANTENIMIENTO.md          ← YouTube roto, llave de firma, notas GPLv3
├─ README.md
└─ app/src/main/
   ├─ AndroidManifest.xml     ← permisos, servicio en primer plano, MediaButtonReceiver
   ├─ res/                    ← íconos, strings, tema base
   └─ java/com/resonar/app/
      ├─ MainActivity.kt      ← entrada, permiso de notificaciones, tema
      ├─ ResonarApp.kt
      ├─ di/
      │  └─ AppContainer.kt   ← DI manual: una sola fuente de verdad
      ├─ data/
      │  ├─ LocalLibraryRepository.kt  ← biblioteca (MediaStore + SAF + descargas)
      │  ├─ FolderTreeScanner.kt        ← escaneo de la carpeta elegida (SAF)
      │  ├─ LibrarySyncer.kt            ← descarga automática desde la nube
      │  ├─ OnlineDownloader.kt         ← descargas a Drive / privada
      │  ├─ TrackDeleter.kt              ← borrado limpio
      │  ├─ MusicFolderStore.kt          ← carpeta elegida (SAF) persistida
      │  ├─ MusicPlaces.kt               ← reglas de qué es música y qué es local
      │  ├─ MusicCollections.kt          ← playlists y favoritos
      │  └─ UserLibraryStore.kt
      ├─ online/
      │  ├─ MusicSource.kt, OnlineRepository.kt, OnlineCatalog.kt
      │  ├─ AudiusSource.kt, InternetArchiveSource.kt, YoutubeSource.kt
      │  └─ Http.kt                     ← HTTP mínimo (sin OkHttp)
      ├─ player/
      │  ├─ MusicService.kt      ← MediaSessionService + ExoPlayer
      │  ├─ PlayerConnection.kt  ← puente único con el servicio (estado para la UI)
      │  ├─ PlaybackStateStore.kt← estado de sesión en SharedPreferences + JSON
      │  ├─ StreamCache.kt       ← caché de streaming y ruteo local/nube
      │  ├─ AudioFocusHandler.kt, PlaybackEqualizer.kt
      └─ ui/
         ├─ AppShell.kt, HomeScreen.kt, HomeTab.kt
         ├─ SearchTab.kt, OnlineSearchPanel.kt
         ├─ LibraryTab.kt, DetailScreen.kt, PlayerScreen.kt
         ├─ QueueScreen.kt, EqualizerScreen.kt, SettingsScreen.kt
         ├─ ResonarViewModel.kt  ← MVVM manual
         ├─ Components.kt, Design.kt
         └─ theme/Theme.kt
```

