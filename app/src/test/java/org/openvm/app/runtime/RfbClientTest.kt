package org.openvm.app.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RfbClientTest {
    @Test
    fun handshakeAndRawUpdateProduceArgbPixels() {
        val output = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(server(width = 2, height = 1, encoding = 0)), output)

        assertEquals(2 to 1, client.handshake())
        val frame = client.readFrame() ?: error("Expected a framebuffer update")

        assertArrayEquals(intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()), frame.pixels)
        assertTrue(output.toByteArray().copyOfRange(0, 12).contentEquals("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)))
    }

    @Test
    fun handshakeHandlesFragmentedReads() {
        val client = RfbClient(
            OneByteAtATimeInputStream(server(width = 1, height = 1, encoding = 0)),
            ByteArrayOutputStream(),
        )

        assertEquals(1 to 1, client.handshake())
        assertEquals(0xffff0000.toInt(), client.readFrame()?.pixels?.single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedEncodingFailsClosed() {
        val client = RfbClient(ByteArrayInputStream(server(width = 1, height = 1, encoding = 1)), ByteArrayOutputStream())
        client.handshake()
        client.readFrame()
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedFramebufferIsRejectedDuringHandshake() {
        RfbClient(ByteArrayInputStream(server(width = 5000, height = 1, encoding = 0)), ByteArrayOutputStream()).handshake()
    }

    private fun server(width: Int, height: Int, encoding: Int): ByteArray {
        val output = ByteArrayOutputStream()
        output.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(1)
        output.write(1)
        writeInt(output, 0)
        writeShort(output, width)
        writeShort(output, height)
        output.write(byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0))
        val name = "OpenVM".toByteArray(StandardCharsets.US_ASCII)
        writeInt(output, name.size)
        output.write(name)
        output.write(0)
        output.write(0)
        writeShort(output, 1)
        writeShort(output, 0)
        writeShort(output, 0)
        writeShort(output, width)
        writeShort(output, height)
        writeInt(output, encoding)
        repeat(width * height) {
            if (it % 2 == 0) output.write(byteArrayOf(0, 0, 255.toByte(), 0))
            else output.write(byteArrayOf(0, 255.toByte(), 0, 0))
        }
        return output.toByteArray()
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value ushr 8)
        output.write(value)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write(value ushr 24)
        output.write(value ushr 16)
        output.write(value ushr 8)
        output.write(value)
    }

    private class OneByteAtATimeInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(length, 1))
    }
}
