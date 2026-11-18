package com.resonar.app.data

import java.io.File

/**
 * AUDITORÍA: ¿el archivo bajado es audio de verdad?
 *
 * Lee la firma del arranque (los primeros 16 bytes) y compara con los
 * formatos que la app guarda: MP3 (ID3 o frame sync), M4A/MP4 (ftyp), FLAC,
 * OGG, WebM (Matroska), WAV (RIFF). Un HTML de error ("<html>…") no coincide
 * con ninguno → se descarta en vez de guardarlo como .mp3 roto.
 *
 * Regla honesta: NO adivinamos qué es; solo descartamos lo que SEGURAMENTE
 * no es audio. Está en un objeto aparte (sin Android) para que los tests de
 * unidad lo prueben sin emulador ni dispositivo.
 */
internal object AudioSignatures {

    /** ¿El archivo empieza con la firma de algún formato de audio que bajamos? */
    fun esAudioDeVerdad(file: File): Boolean {
        val head = ByteArray(16)
        file.inputStream().use { input ->
            var leidos = 0
            while (leidos < head.size) {
                val n = input.read(head, leidos, head.size - leidos)
                if (n < 0) break
                leidos += n
            }
            if (leidos < 4) return false
        }
        val texto = String(head, Charsets.ISO_8859_1)
        return when {
            texto.startsWith("ID3") -> true // MP3 con etiqueta
            texto.startsWith("fLaC") -> true // FLAC
            texto.startsWith("OggS") -> true // OGG
            texto.startsWith("RIFF") -> true // WAV
            head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> true // MP4/M4A
            head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
                head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> true // WebM/Matroska
            else ->
                // MP3 sin etiqueta: arranca con frame sync (0xFF Ex). El HTML
                // de un error empieza con '<' (0x3C) y no entra por acá.
                head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0
        }
    }
}
