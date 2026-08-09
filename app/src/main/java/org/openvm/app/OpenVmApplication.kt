package org.openvm.app

import android.app.Application

/**
 * Keep application startup deliberately small. Runtime controller construction is deferred to
 * [QemuRuntimeControllerStore] so slow software-only Android emulators can attach instrumentation
 * before resolving the production QEMU stack.
 */
class OpenVmApplication : Application()
