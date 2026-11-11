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
- **Búsqueda en línea**: Audius, Internet Archive y YouTube (vía NewPipeExtractor),
  con filtro de música (canciones / videos) y botón de descarga.
- **Descargas**: se guardan en `Descargas/` dentro de la carpeta elegida (Drive) o,
  si no hay carpeta elegida, en la privada de la app. Se ven en "Tu música".
- **Borrado limpio**: al borrar una canción se borra su copia local y su caché;
  en Drive va a la papelera (se puede recuperar).

## Qué NO hace todavía (honestidad)

- Las **carátulas** de temas que viven en Drive: Drive no expone la carátula y la
  app no abre cada archivo a buscar la etiqueta embebida (sería lentísimo por red).
  Las carpetas locales sí leen las etiquetas del audio.
- Los **nombres** se parten como `Tema - Artista`. Un archivo llamado
  `Artista - Tema` se muestra invertido, salvo los descargados por Resonar
  (llevan la marca `[fuente_id]`).
- **YouTube** puede dejar de funcionar de un día a otro (cambios del sitio,
  verificaciones anti-bot). No es un bug de la app; Audius e Internet Archive
  no dependen de ese servicio.
- La app **no tiene historial de versiones**: no hay Git instalado en la máquina
  donde se desarrolla. Hay respaldos en `../respaldos/` y un `.gitignore` listo
  para cuando se instale.

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

## Decisiones que conviene no romper

- **Nada de "simular" que funciona**: si una fuente falla, la app lo dice.
- **DI manual** (`AppContainer`): sin Hilt, para no inflar el APK ni la RAM.
- **SharedPreferences + JSON** en vez de Room: el estado del player no necesita
  base de datos.
- **Caché de streaming separada de la descarga automática**: apagar la descarga
  no tiene que volver lento el streaming.
- **Carpeta local ≠ nube**: una carpeta elegida en el propio teléfono no se copia
  ni se cachea; ya está en disco.
