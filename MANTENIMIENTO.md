# Mantenimiento de Resonar

Guía para mantener Resonar vivo cuando algo externo cambia. Pensada para cualquier ayuda (vos, u otra persona o IA) que llegue al proyecto dentro de un año.

## Nervio principal: YouTube vía NewPipeExtractor

**Qué es y cómo falla.** YouTube está integrado con [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), que extrae la búsqueda y los streams de audio de la web de YouTube sin API key. Cuando YouTube cambia su web (algo que hace seguido), la fuente `youtube` puede fallar **temporalmente hasta que el equipo de NewPipe publica la corrección**. Cuando pase, Resonar:

- muestra honestamente el error ("YouTube no respondió"; las demás fuentes siguen funcionando),
- el toggle de Ajustes → Fuentes en línea → YouTube permite apagarla,
- Audius e Internet Archive siguen andando: la búsqueda federada no depende de YouTube para nada.

No es un bug de la app si esto falla; es el servicio. **No lo "arregles" con trucos que simulen éxito.**

### Diagnóstico rápido (2 minutos)

1. Conectá el teléfono, abrí Resonar → Buscar → En línea → buscá algo ("adele hello").
2. Si no hay resultados de YouTube pero sí de otras fuentes: YouTube está roto o sin permiso.
3. Con `adb logcat -d -s ResonarOnline:I | tail` se ve qué pasó. Errores típicos:
   - `HTTP 4xx/5xx` en la búsqueda → YouTube cambió algo en la web.
   - `sin stream de audio` al reproducir → el extractor viejo no sabe leer la respuesta actual.
   - `429 Too Many Requests` → límite de peticiones; esperado en ciertas redes/NAT.

### La actualización que casi siempre alcanza

```powershell
# 1. En Resonar/app/build.gradle.kts, cambiar la versión de NewPipeExtractor:
implementation("com.github.TeamNewPipe:NewPipeExtractor:vX.Y.Z")   # última en jitpack.io
# 2. Compilar e instalar:
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat installDebug --console=plain
# 3. Probar en el teléfono: búsqueda en modo Canciones y en Videos, y reproducción de un tema.
```

Si la nueva versión compila sin cambios de código, listo. Si da errores de "unresolved reference" o firmas distintas, hay que ajustar `app/src/main/java/com/resonar/app/online/YoutubeSource.kt` a la API nueva (la documentación está en https://teamnewpipe.github.io/NewPipeExtractor/javadoc/).

**Si la última versión todavía no arregla YouTube**, esperar al próximo release de NewPipe. No hay nada que hacer en la app mientras tanto; apagar la fuente en Ajustes es válido.

### Firma de la app (importante para actualizaciones)

Para que una instalación nueva reemplace a la anterior sin borrar todo, la app debe estar firmada con la **misma clave de depuración** (`~/.android/debug.keystore`) — ya es así en tu setup. Si algún día generás un `release.keystore` para distribución, **guardalo fuera del repo y hacé backup**: perder la clave obliga a reinstalar desde cero (los datos privados se borran al cambiar la firma).

## Licencia y distribución

- Resonar es **GPLv3** porque usa NewPipeExtractor (GPLv3). Si distribuís el APK, el código fuente debe acompañarlo o ser accesible (p. ej. un repo público de GitHub).
- Audius e Internet Archive no imponen condiciones de distribución; sus APIs son públicas.
- YouTube puede limitar o cortar el extractor en cualquier momento: es un riesgo asumido del proyecto.

## Datos que NO están en el código

Carpeta de música del usuario (Drive vía SAF), índice de descargas online (`files/descargas_online.json` en el almacenamiento privado), estado de sesión, favoritos y playlists: todo vive **solo en ese teléfono**. Nada de eso se sube al repo ni se pierde compilando, salvo que se borre el almacenamiento de la app (o se rompa la firma, ver arriba).
