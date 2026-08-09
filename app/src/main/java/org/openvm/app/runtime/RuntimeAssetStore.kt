package org.openvm.app.runtime

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

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

    fun hasAnyQemuExecutable(): Boolean = executables.listFiles()?.any { it.isFile && it.canRead() && it.canExecute() } == true

    private fun materialize(uri: Uri, destination: File, maxBytes: Long): MaterializedRuntimeAsset {
        require(maxBytes > 0) { "The runtime asset limit must be positive" }
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, ".${destination.name}.partial")
        if (temporary.exists()) temporary.delete()

        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) {
                            throw IOException("The selected runtime asset exceeds the ${maxBytes} byte limit")
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("The selected runtime asset could not be opened")
            if (total == 0L) throw IOException("The selected runtime asset is empty")
            if (destination.exists() && !destination.delete()) {
                throw IOException("The existing runtime asset could not be replaced")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("The runtime asset could not be committed")
            }
            return MaterializedRuntimeAsset(destination, total, digest.digest().hex())
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun safeName(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .ifBlank { "profile" }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val COPY_BUFFER_BYTES = 1024 * 1024
        const val MAX_EXECUTABLE_BYTES = 512L * 1024L * 1024L
    }
}
