package org.openvm.app.backend

import android.content.Context
import android.os.Build
import org.openvm.app.model.VmProfile
import org.openvm.app.runtime.RuntimeAssetStore

enum class BackendReadiness { READY, UNAVAILABLE, NOT_CONFIGURED }

data class BackendDescriptor(
    val id: String,
    val name: String,
    val readiness: BackendReadiness,
    val explanation: String,
)

/**
 * Capability detection is deliberately separate from guest lifecycle control.
 * A normal Android app must not claim that a platform hypervisor is usable just
 * because the SDK exposes a matching API level or a device node name.
 */
class RuntimeBackendRegistry(private val context: Context) {
    private val assetStore = RuntimeAssetStore(context)

    fun descriptors(): List<BackendDescriptor> = listOf(
        BackendDescriptor(
            id = "avf",
            name = "Android Virtualization Framework",
            readiness = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                BackendReadiness.NOT_CONFIGURED
            } else {
                BackendReadiness.UNAVAILABLE
            },
            explanation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "The platform API level is new enough, but this build still needs a native AVF guest adapter and a compatible guest image."
            } else {
                "AVF-backed guests require Android 13 or newer on a device that exposes the service to this app."
            },
        ),
        BackendDescriptor(
            id = "qemu",
            name = "QEMU",
            readiness = if (assetStore.hasAnyQemuExecutable()) {
                BackendReadiness.READY
            } else {
                BackendReadiness.NOT_CONFIGURED
            },
            explanation = "QEMU is a process-backed open-source adapter. Import an Android-compatible QEMU executable and a bootable guest image; OpenVM copies both into app-private storage and never downloads a binary.",
        ),
    )

    fun startReadiness(profile: VmProfile): String {
        val backend = descriptors().firstOrNull { it.id == profile.backendId }
            ?: return "The selected runtime backend is unknown."
        return when {
            profile.imageUri.isNullOrBlank() -> "Import a guest image before starting this profile."
            profile.backendId == "qemu" && profile.qemuExecutableUri.isNullOrBlank() -> "Import a QEMU executable before starting this profile."
            backend.readiness != BackendReadiness.READY && profile.backendId != "qemu" -> backend.explanation
            else -> "The runtime is ready."
        }
    }
}
