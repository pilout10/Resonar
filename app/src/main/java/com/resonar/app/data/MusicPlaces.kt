package com.resonar.app.data

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Dónde está la música del teléfono y qué no entra a la biblioteca.
 *
 * La carpeta de Resonar es Music/Resonar, pública, para que otros reproductores
 * también la vean. No es un cerco: si la canción ya está en Music/ o en otra
 * carpeta que no es chat, también se muestra. Esconder todo lo que no esté en
 * una sola carpeta vacía dejaría la app sin música.
 *
 * WhatsApp guarda notas de voz y audios reenviados como si fueran canciones
 * (IS_MUSIC = 1, nombres PTT- y AUD-…WA). Por eso se filtra por ruta y por
 * nombre, no por la etiqueta de MediaStore.
 */
object MusicPlaces {

    /** Sugerencia al abrir el selector. El usuario puede elegir otra. */
    const val APP_FOLDER = "Music/Resonar"

    /**
     * Autoridades SAF que son el almacenamiento LOCAL del teléfono: leer ahí es
     * disco, sin red. Google Drive y otras nubes usan otras autoridades y NO entran.
     * Se comparte con el reproductor para no volver a copiar una carpeta local.
     */
    private val LOCAL_SAF_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.media.documents",
    )

    fun isLocalSaf(authority: String?): Boolean = authority in LOCAL_SAF_AUTHORITIES

    /** "primary:Music/Foo" → "Music/Foo". Vacío si eligió el almacenamiento entero. */
    fun prefixFromTree(uri: Uri): String {
        val doc = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return ""
        return doc.substringAfter(':', "").trim().trim('/')
    }

    fun labelFor(prefix: String): String = prefix.ifBlank { "Almacenamiento" }

    /** La ruta de MediaStore cae dentro de la carpeta elegida (ella o una subcarpeta). */
    fun inChosenFolder(place: String, prefix: String): Boolean {
        if (prefix.isBlank()) return true
        val folder = prefix.lowercase().trim('/')
        val text = place.replace('\\', '/').lowercase()
        return text.startsWith("$folder/") ||
            text.startsWith("$folder ") ||
            text.contains("/$folder/") ||
            text.contains(" $folder/") ||
            text.endsWith("/$folder") ||
            text.endsWith(" $folder")
    }

    /**
     * true si este archivo no es música para escuchar: chat, tono, alarma o aviso.
     * [path] puede ser la ruta completa o RELATIVE_PATH; se miran las dos.
     */
    fun reject(
        path: String,
        fileName: String,
        isRingtone: Boolean,
        isAlarm: Boolean,
        isNotification: Boolean,
        isMusic: Boolean,
    ): Boolean {
        // Un tono que además está marcado como música se queda: a veces el tag miente.
        if ((isRingtone || isAlarm || isNotification) && !isMusic) return true
        val name = fileName.lowercase()
        // Notas de voz y audios que WhatsApp nombra solo.
        if (name.startsWith("ptt-") || name.startsWith("pta-")) return true
        if (name.startsWith("aud-") && "wa" in name) return true
        val folder = path.replace('\\', '/').lowercase()
        if ("whatsapp" in folder || "com.whatsapp" in folder) return true
        if (folder.contains("/ringtones/") || folder.contains("/alarms/") || folder.contains("/notifications/")) {
            return true
        }
        return false
    }

    /**
     * 0 = Music/Resonar, 1 = cualquier Music/, 2 = el resto.
     * Inicio muestra primero lo que ya está en la carpeta de música.
     */
    fun rank(path: String): Int {
        val folder = path.replace('\\', '/').lowercase()
        if (folder.contains("/music/resonar") || folder.contains("/musica/resonar") || folder.contains("/música/resonar")) {
            return 0
        }
        if (folder.contains("/music/") || folder.contains("/musica/") || folder.contains("/música/") ||
            folder.startsWith("music/") || folder.startsWith("musica/")
        ) {
            return 1
        }
        return 2
    }
}
