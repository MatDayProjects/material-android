package org.openvm.app.runtime

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

data class RfbFramebuffer(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

/**
 * Small, deliberately bounded RFB 3.8 client for QEMU's local VNC display.
 * Only the raw encoding is accepted; unsupported encodings fail closed instead
 * of silently rendering corrupt guest pixels. Input is limited to bounded key and
 * pointer messages; clipboard and other extensions are intentionally unsupported.
 */
class RfbClient(
    private val input: InputStream,
    private val output: OutputStream,
) {
    private var width = 0
    private var height = 0
    private lateinit var framebuffer: IntArray

    fun handshake(): Pair<Int, Int> {
        val serverVersion = readBytes(PROTOCOL_VERSION_BYTES.size).toString(StandardCharsets.US_ASCII)
        require(serverVersion == "RFB 003.008\n") { "Unsupported RFB protocol version" }
        output.write(PROTOCOL_VERSION_BYTES)
        output.flush()

        val securityCount = readUnsignedByte()
        require(securityCount > 0) { "The VNC server offered no security types" }
        val securityTypes = readBytes(securityCount)
        require(securityTypes.any { (it.toInt() and 0xff) == SECURITY_TYPE_NONE }) {
            "The VNC server requires unsupported authentication"
        }
        writeInt32(SECURITY_TYPE_NONE.toLong())
        output.flush()
        require(readUnsignedInt32() == 0L) { "The VNC server rejected the unauthenticated session" }

        output.write(1) // shared desktop
        output.flush()

        width = readUnsignedShort()
        height = readUnsignedShort()
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "VNC framebuffer dimensions are unsafe" }
        require(width.toLong() * height <= MAX_PIXELS) { "VNC framebuffer is too large" }
        readBytes(PIXEL_FORMAT_BYTES)
        val nameLength = readUnsignedInt32()
        require(nameLength <= MAX_NAME_BYTES) { "VNC desktop name is too large" }
        readBytes(nameLength.toInt())
        framebuffer = IntArray(width * height)

        sendPixelFormat()
        sendRawEncoding()
        requestUpdate(incremental = false)
        return width to height
    }

    /** Reads one server message. A null result means a non-frame notification. */
    fun readFrame(): RfbFramebuffer? {
        val messageType = input.read()
        if (messageType < 0) throw EOFException("The VNC server closed the display")
        return when (messageType) {
            SERVER_FRAMEBUFFER_UPDATE -> readFramebufferUpdate()
            SERVER_BELL -> null
            SERVER_CUT_TEXT -> {
                readBytes(3)
                val length = readUnsignedInt32()
                require(length <= MAX_CUT_TEXT_BYTES) { "VNC cut text is too large" }
                readBytes(length.toInt())
                null
            }
            else -> throw IOException("Unsupported VNC server message: $messageType")
        }
    }

    fun requestUpdate(incremental: Boolean = true) {
        output.write(CLIENT_FRAMEBUFFER_UPDATE_REQUEST)
        output.write(if (incremental) 1 else 0)
        writeUnsignedShort(0)
        writeUnsignedShort(0)
        writeUnsignedShort(width)
        writeUnsignedShort(height)
        output.flush()
    }

    fun sendKeyEvent(keySymbol: Int, pressed: Boolean) {
        require(keySymbol in 0..0x7fffffff) { "RFB key symbol is outside the supported range" }
        output.write(CLIENT_KEY_EVENT)
        output.write(if (pressed) 1 else 0)
        output.write(ByteArray(2))
        writeInt32(keySymbol.toLong())
        output.flush()
    }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int = 0) {
        require(width > 0 && height > 0) { "RFB pointer events require a completed handshake" }
        require(x in 0 until width && y in 0 until height) { "RFB pointer coordinates are outside the framebuffer" }
        require(buttonMask in 0..0xff) { "RFB pointer button mask is outside the supported range" }
        output.write(CLIENT_POINTER_EVENT)
        output.write(buttonMask)
        writeUnsignedShort(x)
        writeUnsignedShort(y)
        output.flush()
    }

    private fun readFramebufferUpdate(): RfbFramebuffer? {
        readBytes(1)
        val rectangleCount = readUnsignedShort()
        require(rectangleCount <= MAX_RECTANGLES) { "VNC update contains too many rectangles" }
        var changed = false
        var updatePixels = 0L
        repeat(rectangleCount) {
            val x = readUnsignedShort()
            val y = readUnsignedShort()
            val rectangleWidth = readUnsignedShort()
            val rectangleHeight = readUnsignedShort()
            require(x + rectangleWidth <= width && y + rectangleHeight <= height) {
                "VNC rectangle is outside the framebuffer"
            }
            val encoding = readInt32()
            require(encoding == ENCODING_RAW) { "Unsupported VNC rectangle encoding: $encoding" }
            updatePixels += rectangleWidth.toLong() * rectangleHeight
            require(updatePixels <= MAX_UPDATE_PIXELS) { "VNC update contains too many pixels" }
            repeat(rectangleHeight) { row ->
                val destinationOffset = (y + row) * width + x
                repeat(rectangleWidth) { column ->
                    val blue = readUnsignedByte()
                    val green = readUnsignedByte()
                    val red = readUnsignedByte()
                    readUnsignedByte() // padding/unused alpha byte in the requested format
                    framebuffer[destinationOffset + column] = 0xff000000.toInt() or
                        (red shl 16) or (green shl 8) or blue
                }
            }
            changed = true
        }
        return if (changed) RfbFramebuffer(width, height, framebuffer.copyOf()) else null
    }

    private fun sendPixelFormat() {
        output.write(CLIENT_SET_PIXEL_FORMAT)
        output.write(ByteArray(3))
        output.write(32)
        output.write(24)
        output.write(0)
        output.write(1)
        writeUnsignedShort(255)
        writeUnsignedShort(255)
        writeUnsignedShort(255)
        writeUnsignedByte(16)
        writeUnsignedByte(8)
        writeUnsignedByte(0)
        output.write(ByteArray(3))
        output.flush()
    }

    private fun sendRawEncoding() {
        output.write(CLIENT_SET_ENCODINGS)
        output.write(0)
        writeUnsignedShort(1)
        writeInt32(ENCODING_RAW.toLong())
        output.flush()
    }

    private fun readBytes(count: Int): ByteArray {
        require(count >= 0) { "Negative RFB read length" }
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(result, offset, count - offset)
            if (read < 0) throw EOFException("The VNC server closed the display")
            offset += read
        }
        return result
    }

    private fun readUnsignedByte(): Int = input.read().also { if (it < 0) throw EOFException("The VNC server closed the display") }

    private fun readUnsignedShort(): Int = (readUnsignedByte() shl 8) or readUnsignedByte()

    private fun readUnsignedInt32(): Long =
        (readUnsignedByte().toLong() shl 24) or
            (readUnsignedByte().toLong() shl 16) or
            (readUnsignedByte().toLong() shl 8) or
            readUnsignedByte().toLong()

    private fun readInt32(): Int = readUnsignedInt32().toInt()

    private fun writeUnsignedByte(value: Int) = output.write(value and 0xff)

    private fun writeUnsignedShort(value: Int) {
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun writeInt32(value: Long) {
        output.write(((value ushr 24) and 0xff).toInt())
        output.write(((value ushr 16) and 0xff).toInt())
        output.write(((value ushr 8) and 0xff).toInt())
        output.write((value and 0xff).toInt())
    }

    companion object {
        private const val SECURITY_TYPE_NONE = 1
        private const val ENCODING_RAW = 0
        private const val SERVER_FRAMEBUFFER_UPDATE = 0
        private const val SERVER_BELL = 2
        private const val SERVER_CUT_TEXT = 3
        private const val CLIENT_SET_PIXEL_FORMAT = 0
        private const val CLIENT_SET_ENCODINGS = 2
        private const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val CLIENT_KEY_EVENT = 4
        private const val CLIENT_POINTER_EVENT = 5
        private const val MAX_DIMENSION = 4096
        private const val MAX_PIXELS = 16_777_216L
        private const val MAX_UPDATE_PIXELS = MAX_PIXELS
        private const val MAX_RECTANGLES = 4096
        private const val MAX_NAME_BYTES = 1024L * 1024L
        private const val MAX_CUT_TEXT_BYTES = 1024L * 1024L
        private val PROTOCOL_VERSION_BYTES = "RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)
        private const val PIXEL_FORMAT_BYTES = 16
    }
}
