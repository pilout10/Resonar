package com.resonar.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Auditoría: la clave de dedupe de la radio debe reconocer la MISMA canción
 * aunque llegue con metadatos sucios de distintas fuentes ("( Official
 * Video)", "y2mate", artista pegado al título, tildes). Es el fix verificado
 * en el teléfono (radio 30→38 y 38→46 sin repetidos).
 */
class RadioSeedKeyTest {

    @Test
    fun `misma cancion con y sin ruido de video oficial colapsan a la misma clave`() {
        assertEquals(
            RadioSeed.queueKey("Chica Paranormal (Official Video)", "Paulo Londra"),
            RadioSeed.queueKey("Chica Paranormal", "Paulo Londra"),
        )
    }

    @Test
    fun `tildes y mayusculas no importan`() {
        assertEquals(
            RadioSeed.queueKey("ARRANCÁRMELO", "WOS"),
            RadioSeed.queueKey("arrancarmelo", "wos"),
        )
    }

    @Test
    fun `artista pegado al titulo se limpia`() {
        // Re-subida de Archive.org: "Tal Vez Paulo Londra" con artista repetido.
        assertEquals(
            RadioSeed.queueKey("Tal Vez Paulo Londra", "Paulo Londra"),
            RadioSeed.queueKey("Tal Vez", "Paulo Londra"),
        )
    }

    @Test
    fun `canciones distintas no colapsan`() {
        assertNotEquals(
            RadioSeed.queueKey("Chica Paranormal", "Paulo Londra"),
            RadioSeed.queueKey("Tal Vez", "Paulo Londra"),
        )
    }

    @Test
    fun `artista distinto da clave distinta`() {
        assertNotEquals(
            RadioSeed.queueKey("Tal Vez", "Paulo Londra"),
            RadioSeed.queueKey("Tal Vez", "Internet Archive"),
        )
    }

    @Test
    fun `titulo vacio no tiene clave`() {
        assertNull(RadioSeed.queueKey("", "Alguien"))
        assertNull(RadioSeed.queueKey(null, "Alguien"))
        assertNull(RadioSeed.queueKey("   ", "Alguien"))
    }
}
