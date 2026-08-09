package org.openvm.app.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class MaterializedRuntimeAsset(
    val file: File,
    val sizeBytes: Long,
    val sha256: String,
)

/**
 * Copies user-selected SAF assets into app-private storage before a runtime sees them.
 * The copy is bounded, hashed, and committed atomically so a half-written disk image or
 * executable can never be handed to a guest process as if it were complete.
 */
class RuntimeAssetStore(
    context: Context,
    private val resolver: ContentResolver = context.contentResolver,
) {
    private val root = File(context.filesDir, "runtime-assets")
    private val images = File(root, "guest-images")
    private val executables = File(root, "executables")
    private val displaySockets = File(root, "display")

    fun materializeGuestImage(profileId: String, uri: Uri, maxBytes: Long): MaterializedRuntimeAsset =
        materialize(uri, File(images, "${safeName(profileId)}.img"), maxBytes)

    fun materializeQemuExecutable(profileId: String, uri: Uri, maxBytes: Long = MAX_EXECUTABLE_BYTES): MaterializedRuntimeAsset =
        materialize(uri, File(executables, "${safeName(profileId)}-qemu-system"), maxBytes).also {
            if (!it.file.setExecutable(true, true) && !it.file.canExecute()) {
                throw IOException("The imported QEMU executable could not be marked executable")
            }
        }

    fun existingGuestImage(profileId: String): File = File(images, "${safeName(profileId)}.img")

    fun existingQemuExecutable(profileId: String): File = File(executables, "${safeName(profileId)}-qemu-system")

    fun prepareDisplaySocket(profileId: String): File = File(displaySockets, "${socketName(profileId)}.sock").also { socket ->
        socket.parentFile?.mkdirs()
        if (socket.exists() && !socket.delete()) {
            throw IOException("The stale guest display socket could not be removed")
        }
    }

    fun deleteDisplaySocket(socket: File) {
        if (socket.exists()) socket.delete()
    }

    fun hasAnyQemuExecutable(): Boolean = executables.listFiles()?.any { it.isFile && it.canRead() && it.canExecute() } == true

    private fun materialize(uri: Uri, destination: File, maxBytes: Long): MaterializedRuntimeAsset {
        require(maxBytes > 0) { "The runtime asset limit must be positive" }
        destination.parentFile?.mkdirs()
        require(destination.parentFile?.isDirectory == true) { "The runtime asset directory could not be created" }
        val temporary = File(destination.parentFile, ".${destination.name}.partial")
        if (temporary.exists()) temporary.delete()

        var total = 0L
        var zeroReads = 0
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) {
                            zeroReads += 1
                            if (zeroReads >= MAX_ZERO_READS) {
                                throw IOException("The selected runtime asset made no copy progress")
                            }
                            continue
                        }
                        zeroReads = 0
                        total += read
                        if (total > maxBytes) {
                            throw IOException("The selected runtime asset exceeds the ${maxBytes} byte limit")
                        }
                        if (temporary.parentFile?.usableSpace ?: 0L < read.toLong()) {
                            throw IOException("There is not enough app storage for the runtime asset")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("The selected runtime asset could not be opened")
            if (total == 0L) throw IOException("The selected runtime asset is empty")
            replaceAtomically(temporary, destination)
            return MaterializedRuntimeAsset(destination, total, digest.digest().hex())
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun replaceAtomically(temporary: File, destination: File) {
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("The runtime asset filesystem does not support atomic replacement", error)
        } catch (error: UnsupportedOperationException) {
            throw IOException("The runtime asset filesystem does not support atomic replacement", error)
        }
    }

    private fun safeName(value: String): String {
        val readable = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifBlank { "profile" }
        return "$readable-${stableKey(value)}"
    }

    private fun socketName(value: String): String = "p-${stableKey(value)}"

    private fun stableKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .hex()
        .take(16)

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        private const val MAX_ZERO_READS = 8
        const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
    }
}
