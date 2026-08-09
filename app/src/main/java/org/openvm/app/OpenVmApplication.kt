package org.openvm.app

import android.app.Application
import org.openvm.app.backend.QemuRuntimeController

class OpenVmApplication : Application() {
    /**
     * The runtime outlives an Activity recreation so rotating the editor or returning from
     * the document picker does not terminate a guest that is already running.
     */
    val qemuRuntimeController: QemuRuntimeController by lazy {
        QemuRuntimeController(this)
    }
}
