package com.resonar.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Auditoría: la validación de "lo bajado es audio de verdad" es la guarda
 * contra servidores que devuelven 200 con una página HTML. Estos tests fijan
 * el contrato de las firmas que aceptamos y las que descartamos.
 */
class AudioSignaturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun archivo(vararg bytes: Byte): File {
        val f = File(tmp.root, "a.bin")
        f.writeBytes(bytes)
        return f
    }

    private fun archivo(texto: String): File = archivo(*texto.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun `mp3 con etiqueta ID3 pasa`() {
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo("ID3\u0004\u0000\u0000")))
    }

    @Test
    fun `mp3 sin etiqueta con frame sync pasa`() {
        // 0xFF 0xFB: frame sync MPEG válido (el arranque típico de un MP3 "pelado")
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo(0xFF.toByte(), 0xFB.toByte(), 0x50, 0x00)))
    }

    @Test
    fun `m4a con ftyp pasa`() {
        // El arranque real de un M4A: tamaño (4 bytes) + "ftyp" + marca
        assertTrue(
            AudioSignatures.esAudioDeVerdad(
                archivo(0x00, 0x00, 0x00, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 0x4D, 0x34, 0x41),
            ),
        )
    }

    @Test
    fun `flac ogg wav y webm pasan`() {
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo("fLaC")))
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo("OggS")))
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo("RIFF")))
        assertTrue(AudioSignatures.esAudioDeVerdad(archivo(0x1A.toByte(), 0x45.toByte(), 0xDF.toByte(), 0xA3.toByte())))
    }

    @Test
    fun `pagina HTML de error se descarta`() {
        assertFalse(AudioSignatures.esAudioDeVerdad(archivo("<html><head><title>Error")))
        assertFalse(AudioSignatures.esAudioDeVerdad(archivo("<!DOCTYPE html>")))
    }

    @Test
    fun `archivo vacio o demasiado corto se descarta`() {
        assertFalse(AudioSignatures.esAudioDeVerdad(archivo("ID")))
        assertFalse(AudioSignatures.esAudioDeVerdad(archivo("")))
    }
}
