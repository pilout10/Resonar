package com.resonar.app.data

/**
 * Ids de agrupación: artista y álbum.
 *
 * POR QUÉ NO USAR EL ID DE MEDIASTORE:
 * MediaStore da un id real de artista solo para los archivos que TRAEN la
 * etiqueta. Para los que no la traen (casi todas las descargas en línea y
 * muchos mp3 sueltos) devuelve 0 para TODOS: si se usara ese id, todas las
 * canciones sin artista caerían en un solo artista y la pestaña "Artistas"
 * mostraría un grupo con el nombre del primero que se coló.
 *
 * Por eso el id se calcula del NOMBRE que ve el usuario: es lo que la
 * pestaña muestra y lo que la persona espera ver junto.
 *
 * Los ids son negativos para no pisar los de MediaStore (que son positivos y
 * enormes).
 */
object GroupIds {

    /** Id estable y negativo a partir de una clave (artista o álbum). */
    fun of(key: String): Long {
        var h = 1125899906842597L
        for (c in key) h = 31L * h + c.code
        val positive = h and Long.MAX_VALUE
        return if (positive == 0L) -3L else -positive
    }

    /** Id del grupo de un artista (por nombre, sin distinguir mayúsculas). */
    fun artist(name: String): Long = of("artist|${name.trim().lowercase()}")

    /** Id del grupo de un álbum (por nombre, sin distinguir mayúsculas). */
    fun album(name: String): Long = of("album|${name.trim().lowercase()}")

    /**
     * Nombre visible y id del álbum.
     * La carpeta ("Descargas") no es un álbum. Si el archivo no trae la etiqueta,
     * el tema queda solo, con su título, para no juntar artistas distintos.
     */
    fun albumIdentity(tagged: String, folder: String, fileKey: String, fallbackTitle: String): Pair<String, Long> {
        val clean = tagged.trim()
        val real = clean.takeIf {
            it.isNotBlank() &&
                !it.equals("<sin álbum>", ignoreCase = true) &&
                !it.equals(folder.trim(), ignoreCase = true)
        }
        return if (real != null) {
            real to album(real)
        } else {
            val title = fallbackTitle.trim().ifBlank { fileKey }
            title to album("file|$fileKey")
        }
    }
}
