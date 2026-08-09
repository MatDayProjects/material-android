package org.openvm.app.backend

import android.content.Context
import android.os.Build
import org.openvm.app.model.VmProfile
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption

data class BundledQemuRuntime(
    val hostAbi: String,
    val executable: File,
    val dataDirectory: File?,
)

/**
 * Locates the optional QEMU runtime installed by Android in nativeLibraryDir.
 *
 * The app never downloads this runtime. A source-only APK simply returns null and
 * continues to support the explicit user-imported executable path.
 */
class NativeQemuRuntime(private val context: Context) {
    fun locate(profile: VmProfile): BundledQemuRuntime? {
        if (Build.VERSION.SDK_INT < MINIMUM_NATIVE_API) return null
        val guest = guestExecutableName(profile.architecture) ?: return null
        val hostAbi = supportedHostAbi() ?: return null
        val executable = File(context.applicationInfo.nativeLibraryDir, "libopenvm-qemu-$guest.so")
        if (!isExecutable(executable)) return null
        val dataDirectory = materializeQemuData(hostAbi)
        return BundledQemuRuntime(hostAbi, executable, dataDirectory)
    }

    fun hasRuntime(profile: VmProfile): Boolean {
        if (Build.VERSION.SDK_INT < MINIMUM_NATIVE_API) return false
        val guest = guestExecutableName(profile.architecture) ?: return false
        supportedHostAbi() ?: return false
        return isExecutable(File(context.applicationInfo.nativeLibraryDir, "libopenvm-qemu-$guest.so"))
    }

    fun hasAnyRuntime(): Boolean = if (Build.VERSION.SDK_INT < MINIMUM_NATIVE_API) {
        false
    } else {
        supportedHostAbi()?.let {
            GUEST_EXECUTABLES.any { guest -> isExecutable(File(context.applicationInfo.nativeLibraryDir, "libopenvm-qemu-$guest.so")) }
        } ?: false
    }

    private fun isExecutable(file: File): Boolean =
        file.isFile && file.length() > 0L && file.canRead() && file.canExecute()

    private fun supportedHostAbi(): String? = Build.SUPPORTED_64_BIT_ABIS.firstOrNull { it in SUPPORTED_HOST_ABIS }

    private fun materializeQemuData(hostAbi: String): File? {
        val assetRoot = "native-qemu/$hostAbi/share/qemu"
        val children = context.assets.list(assetRoot).orEmpty()
        if (children.isEmpty()) return null

        synchronized(dataLock) {
            val destination = File(context.filesDir, "runtime-assets/native-qemu/$hostAbi/share/qemu")
            if (destination.isDirectory && destination.listFiles().orEmpty().isNotEmpty()) return destination

            val temporary = File(destination.parentFile, ".qemu-data-${System.nanoTime()}")
            temporary.deleteRecursively()
            try {
                temporary.mkdirs()
                val budget = ExtractionBudget()
                copyAssetTree(assetRoot, temporary, budget, depth = 0)
                destination.deleteRecursively()
                destination.parentFile?.mkdirs()
                try {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                return destination
            } catch (error: Throwable) {
                temporary.deleteRecursively()
                throw IllegalStateException("Bundled QEMU data could not be materialized", error)
            }
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File, budget: ExtractionBudget, depth: Int) {
        require(depth <= MAX_ASSET_DEPTH) { "Bundled QEMU data is nested too deeply" }
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isNotEmpty()) {
            destination.mkdirs()
            children.forEach { child ->
                require(child.isNotBlank() && child != "." && child != ".." && !child.contains('/')) {
                    "Bundled QEMU data contains an unsafe asset name"
                }
                copyAssetTree("$assetPath/$child", File(destination, child), budget, depth + 1)
            }
            return
        }

        budget.fileCount++
        require(budget.fileCount <= MAX_ASSET_FILES) { "Bundled QEMU data has too many files" }
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    budget.byteCount += read
                    require(budget.byteCount <= MAX_ASSET_BYTES) { "Bundled QEMU data exceeds the size limit" }
                    output.write(buffer, 0, read)
                }
            }
        }
        destination.setReadable(true, true)
    }

    private data class ExtractionBudget(var fileCount: Int = 0, var byteCount: Long = 0)

    companion object {
        private val dataLock = Any()
        private val SUPPORTED_HOST_ABIS = setOf("arm64-v8a", "x86_64")
        private val GUEST_EXECUTABLES = setOf("aarch64", "x86_64")
        private const val MINIMUM_NATIVE_API = 29
        private const val MAX_ASSET_DEPTH = 12
        private const val MAX_ASSET_FILES = 4000
        private const val MAX_ASSET_BYTES = 64L * 1024L * 1024L

        fun guestExecutableName(architecture: String): String? = when (architecture) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }
}
