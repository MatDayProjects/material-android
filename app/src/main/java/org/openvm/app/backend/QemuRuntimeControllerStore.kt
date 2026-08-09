package org.openvm.app.backend

import android.app.Application

/**
 * Keeps one controller per process-level Application without making Application startup resolve
 * the controller class. The controller therefore survives Activity recreation while the Android
 * instrumentation target can start with a minimal Application bootstrap.
 */
object QemuRuntimeControllerStore {
    @Volatile
    private var owner: Application? = null

    @Volatile
    private var controller: QemuRuntimeController? = null

    fun forApplication(application: Application): QemuRuntimeController {
        controller?.let { existing ->
            if (owner === application) return existing
        }

        return synchronized(this) {
            controller?.let { existing ->
                if (owner === application) return@synchronized existing
                existing.close()
            }
            QemuRuntimeController(application).also {
                owner = application
                controller = it
            }
        }
    }
}
