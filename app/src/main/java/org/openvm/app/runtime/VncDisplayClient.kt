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

    fun start(
        onFrame: (RfbFramebuffer) -> Unit,
        onError: (String) -> Unit,
    ) {
        executor.execute {
            runCatching {
                val connected = connectWithRetry()
                socket = connected
                connected.use { localSocket ->
                    val client = RfbClient(localSocket.inputStream, localSocket.outputStream)
                    client.handshake()
                    while (!closed) {
                        client.readFrame()?.let(onFrame)
                        client.requestUpdate()
                    }
                }
            }.onFailure { error ->
                if (!closed) onError(error.message ?: "The guest display connection failed")
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        executor.shutdownNow()
    }

    private fun connectWithRetry(): LocalSocket {
        var lastError: Throwable? = null
        repeat(MAX_CONNECT_ATTEMPTS) {
            if (closed) throw IOException("The guest display connection was cancelled")
            val candidate = socketFactory()
            try {
                candidate.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                return candidate
            } catch (error: Throwable) {
                lastError = error
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
