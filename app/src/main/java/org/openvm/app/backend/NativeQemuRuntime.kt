package org.openvm.app.backend

import android.content.Context
import android.os.Build
import org.openvm.app.model.VmProfile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class BundledQemuRuntime(
    val hostAbi: String,
    val executable: File,
    val dataDirectory: File?,
    val libraryDirectory: File?,
)

/**
 * Locates the optional QEMU executable installed by Android in nativeLibraryDir
 * and materializes its pinned dependency libraries and firmware data privately.
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
        val libraryDirectory = materializeQemuLibraries(hostAbi)
        val dataDirectory = materializeQemuData(hostAbi)
        return BundledQemuRuntime(hostAbi, executable, dataDirectory, libraryDirectory)
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

    private fun materializeQemuLibraries(hostAbi: String): File? {
        val assetRoot = "native-qemu/$hostAbi/lib"
        val children = context.assets.list(assetRoot).orEmpty()
        if (children.isEmpty()) return null
        val assets = children.sorted().map { child ->
            requireSafeLibraryAsset(assetRoot, child)
            LibraryAsset(child, sha256Asset("$assetRoot/$child"))
        }

        synchronized(dataLock) {
            val destination = File(context.filesDir, "runtime-assets/native-qemu/$hostAbi/lib")
            if (isValidLibraryCache(destination, assets)) return destination

            val temporary = File(destination.parentFile, ".qemu-libs-${System.nanoTime()}")
            temporary.deleteRecursively()
            try {
                temporary.mkdirs()
                val budget = ExtractionBudget(MAX_LIBRARY_FILES, MAX_LIBRARY_BYTES)
                assets.forEach { asset ->
                    val child = asset.name
                    copyAssetFile("$assetRoot/$child", File(temporary, child), budget)
                }
                File(temporary, LIBRARY_MANIFEST_NAME).writeText(libraryManifest(assets))
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
                throw IllegalStateException("Bundled QEMU libraries could not be materialized", error)
            }
        }
    }

    private fun requireSafeLibraryAsset(assetRoot: String, child: String) {
        require(child.isNotBlank() && child != "." && child != ".." && !child.contains('/')) {
            "Bundled QEMU library contains an unsafe asset name"
        }
        require(context.assets.list("$assetRoot/$child").orEmpty().isEmpty()) {
            "Bundled QEMU library tree must be flat"
        }
    }

    private fun isValidLibraryCache(destination: File, assets: List<LibraryAsset>): Boolean {
        if (!destination.isDirectory) return false
        val manifest = File(destination, LIBRARY_MANIFEST_NAME)
        if (!manifest.isFile || manifest.length() > MAX_LIBRARY_MANIFEST_BYTES) return false
        val expected = libraryManifest(assets)
        if (runCatching { manifest.readText() }.getOrNull() != expected) return false
        val files = destination.listFiles().orEmpty()
            .filterNot { it.name == LIBRARY_MANIFEST_NAME }
            .associateBy { it.name }
        if (files.keys != assets.map { it.name }.toSet()) return false
        return assets.all { asset ->
            val file = files[asset.name] ?: return@all false
            file.isFile && file.length() > 0L && sha256File(file) == asset.sha256
        }
    }

    private fun libraryManifest(assets: List<LibraryAsset>): String = assets.joinToString(
        separator = "\n",
        postfix = "\n",
    ) { "${it.name}\t${it.sha256}" }

    private fun sha256Asset(assetPath: String): String = context.assets.open(assetPath).use { input ->
        sha256Stream(input)
    }

    private fun sha256File(file: File): String = file.inputStream().use { input -> sha256Stream(input) }

    private fun sha256Stream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

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
                val budget = ExtractionBudget(MAX_ASSET_FILES, MAX_ASSET_BYTES)
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

        destination.parentFile?.mkdirs()
        copyAssetFile(assetPath, destination, budget)
    }

    private fun copyAssetFile(assetPath: String, destination: File, budget: ExtractionBudget) {
        budget.fileCount++
        require(budget.fileCount <= budget.maxFiles) { "Bundled QEMU assets have too many files" }
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    budget.byteCount += read
                    require(budget.byteCount <= budget.maxBytes) { "Bundled QEMU assets exceed the size limit" }
                    output.write(buffer, 0, read)
                }
            }
        }
        destination.setReadable(true, true)
        require(destination.length() > 0L) { "Bundled QEMU asset is empty" }
    }

    private data class ExtractionBudget(
        val maxFiles: Int,
        val maxBytes: Long,
        var fileCount: Int = 0,
        var byteCount: Long = 0,
    )

    private data class LibraryAsset(val name: String, val sha256: String)

    companion object {
        private val dataLock = Any()
        private val SUPPORTED_HOST_ABIS = setOf("arm64-v8a", "x86_64")
        private val GUEST_EXECUTABLES = setOf("aarch64", "x86_64")
        private const val MINIMUM_NATIVE_API = 29
        private const val MAX_ASSET_DEPTH = 12
        private const val MAX_ASSET_FILES = 4000
        private const val MAX_ASSET_BYTES = 64L * 1024L * 1024L
        private const val MAX_LIBRARY_FILES = 4000
        private const val MAX_LIBRARY_BYTES = 128L * 1024L * 1024L
        private const val LIBRARY_MANIFEST_NAME = ".openvm-qemu-libraries"
        private const val MAX_LIBRARY_MANIFEST_BYTES = 512L * 1024L

        fun guestExecutableName(architecture: String): String? = when (architecture) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> null
        }
    }
}
