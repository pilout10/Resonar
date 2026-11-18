package com.resonar.app.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Auditoría: el tope de tamaño de respuesta es la guarda contra un servidor
 * que manda cientos de MB de "JSON" y llena la RAM de un teléfono de 2 GB.
 * Se prueba la lectura acotada sin red real (stream en memoria).
 */
class HttpCapTest {

    @Test
    fun `respuesta chica pasa completa`() {
        val datos = "{\"ok\":true,\"items\":[1,2,3]}".toByteArray(Charsets.UTF_8)
        val leido = readResponseCapped(ByteArrayInputStream(datos))
        assertEquals(String(datos, Charsets.UTF_8), leido)
    }

    @Test
    fun `respuesta dentro del tope pasa`() {
        val grande = ByteArray(MAX_RESPONSE_BYTES) { 'a'.code.toByte() }
        val leido = readResponseCapped(ByteArrayInputStream(grande))
        assertEquals(MAX_RESPONSE_BYTES, leido.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `respuesta que excede el tope falla fuerte en vez de llenar la RAM`() {
        val enorme = ByteArray(MAX_RESPONSE_BYTES + 1) { 'x'.code.toByte() }
        assertThrows(IllegalStateException::class.java) {
            readResponseCapped(ByteArrayInputStream(enorme))
        }
    }

    @Test
    fun `tope configurable se respeta`() {
        val datos = ByteArray(100) { 'a'.code.toByte() }
        assertThrows(IllegalStateException::class.java) {
            readResponseCapped(ByteArrayInputStream(datos), maxBytes = 50)
        }
    }
}
