package org.openvm.app.runtime

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Connects the app to a QEMU VNC UNIX socket without opening a network listener. */
class VncDisplayClient(
    private val socketPath: String,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
    private val socketFactory: () -> LocalSocket = { LocalSocket() },
) : AutoCloseable {
    @Volatile private var closed = false
    @Volatile private var socket: LocalSocket? = null
    @Volatile private var rfbClient: RfbClient? = null
    @Volatile private var errorHandler: ((String) -> Unit)? = null

    fun start(
        onFrame: (RfbFramebuffer) -> Unit,
        onError: (String) -> Unit,
        onDisconnected: () -> Unit = {},
    ) {
        errorHandler = onError
        executor.execute {
            runCatching {
                val connected = connectWithRetry()
                connected.use { localSocket ->
                    val client = RfbClient(localSocket.inputStream, localSocket.outputStream)
                    client.handshake()
                    rfbClient = client
                    while (!closed) {
                        client.readFrame()?.let(onFrame)
                        client.requestUpdate()
                    }
                }
            }.onFailure { error ->
                if (!closed) onError(error.message ?: "The guest display connection failed")
            }
            rfbClient = null
            if (!closed) onDisconnected()
        }
    }

    fun sendKeyEvent(keySymbol: Int, pressed: Boolean): Boolean = sendToGuest { it.sendKeyEvent(keySymbol, pressed) }

    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int = 0): Boolean =
        sendToGuest { it.sendPointerEvent(x, y, buttonMask) }

    override fun close() {
        closed = true
        rfbClient = null
        runCatching { socket?.close() }
        executor.shutdownNow()
    }

    private fun sendToGuest(action: (RfbClient) -> Unit): Boolean {
        val client = rfbClient ?: return false
        return runCatching {
            synchronized(client) { action(client) }
            true
        }.getOrElse { error ->
            if (!closed) errorHandler?.invoke(error.message ?: "The guest input could not be sent")
            false
        }
    }

    private fun connectWithRetry(): LocalSocket {
        var lastError: Throwable? = null
        repeat(MAX_CONNECT_ATTEMPTS) {
            if (closed) throw IOException("The guest display connection was cancelled")
            val candidate = socketFactory()
            socket = candidate
            try {
                candidate.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                return candidate
            } catch (error: Throwable) {
                lastError = error
                if (socket === candidate) socket = null
                runCatching { candidate.close() }
                Thread.sleep(CONNECT_RETRY_DELAY_MILLIS)
            }
        }
        throw IOException("The QEMU display socket was not available", lastError)
    }

    companion object {
        private const val MAX_CONNECT_ATTEMPTS = 20
        private const val CONNECT_RETRY_DELAY_MILLIS = 250L
    }
}
