package com.resonar.app.data

import android.content.Context
import android.net.Uri

/**
 * Carpeta que eligió el usuario en Configuración.
 * Se guarda el URI del sistema (para volver a leerla) y el nombre visible.
 * Si no hay nada guardado, la biblioteca sigue siendo todo el teléfono menos WhatsApp.
 */
class MusicFolderStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun uri(): Uri? = prefs.getString(KEY_URI, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun prefix(): String = prefs.getString(KEY_PREFIX, "").orEmpty()

    fun label(): String = prefs.getString(KEY_LABEL, "").orEmpty()

    fun save(uri: Uri, prefix: String, label: String) {
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_PREFIX, prefix)
            .putString(KEY_LABEL, label)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS = "resonar_folder"
        const val KEY_URI = "tree_uri"
        const val KEY_PREFIX = "prefix"
        const val KEY_LABEL = "label"
    }
}
