package org.openvm.app.backend

import android.content.Context
import android.os.Build
import org.openvm.app.model.VmProfile

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
            readiness = BackendReadiness.NOT_CONFIGURED,
            explanation = "The portable QEMU adapter is an open-source native component planned for a follow-up module; no binary is downloaded or hidden in this build.",
        ),
    )

    fun startReadiness(profile: VmProfile): String {
        val backend = descriptors().firstOrNull { it.id == profile.backendId }
            ?: return "The selected runtime backend is unknown."
        return when {
            profile.imageUri.isNullOrBlank() -> "Import a guest image before starting this profile."
            backend.readiness != BackendReadiness.READY -> backend.explanation
            else -> "The runtime is ready."
        }
    }
}

