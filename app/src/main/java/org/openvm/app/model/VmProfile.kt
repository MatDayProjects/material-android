package org.openvm.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class VmStatus {
    STOPPED,
    RUNNING,
    STARTING,
    STOPPING,
    ERROR,
}

@Serializable
data class VmProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val androidVersion: String = "Android 14",
    val architecture: String = "arm64-v8a",
    val memoryMb: Int = 2048,
    val storageGb: Int = 16,
    val vcpus: Int = 2,
    val imageUri: String? = null,
    val guestManifestUri: String? = null,
    val backendId: String = "avf",
    val qemuExecutableUri: String? = null,
    val kernelUri: String? = null,
    val initrdUri: String? = null,
    val status: VmStatus = VmStatus.STOPPED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    fun validationErrors(): List<String> = buildList {
        if (name.trim().length !in 1..64) add("Profile name must be 1–64 characters")
        if (androidVersion.trim().isEmpty()) add("Guest Android version is required")
        if (memoryMb !in 256..65536) add("Memory must be between 256 and 65536 MB")
        if (storageGb !in 1..4096) add("Storage must be between 1 and 4096 GB")
        if (vcpus !in 1..32) add("Virtual CPUs must be between 1 and 32")
        if (architecture !in SUPPORTED_ARCHITECTURES) add("Unsupported guest architecture")
        if (backendId !in SUPPORTED_BACKENDS) add("Unsupported runtime backend")
    }

    fun imageLabel(): String? = imageUri?.substringAfterLast('/').takeUnless { it.isNullOrBlank() }

    fun guestManifestLabel(): String? = guestManifestUri?.substringAfterLast('/').takeUnless { it.isNullOrBlank() }

    /** Lifecycle belongs to the runtime process and must not cross an export or restart. */
    fun restoredForHost(): VmProfile = if (status == VmStatus.STOPPED) this else copy(status = VmStatus.STOPPED)

    companion object {
        val SUPPORTED_ARCHITECTURES = setOf("arm64-v8a", "x86_64")
        val SUPPORTED_BACKENDS = setOf("avf", "qemu")
    }
}

@Serializable
data class VmProfileDocument(
    val schemaVersion: Int = 1,
    val profiles: List<VmProfile> = emptyList(),
)
