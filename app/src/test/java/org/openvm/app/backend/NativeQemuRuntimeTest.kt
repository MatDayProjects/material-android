package org.openvm.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeQemuRuntimeTest {
    @Test
    fun guestArchitectureMapsToBundledQemuExecutable() {
        assertEquals("aarch64", NativeQemuRuntime.guestExecutableName("arm64-v8a"))
        assertEquals("x86_64", NativeQemuRuntime.guestExecutableName("x86_64"))
    }

    @Test
    fun unsupportedGuestArchitectureHasNoBundledExecutable() {
        assertNull(NativeQemuRuntime.guestExecutableName("armeabi-v7a"))
    }
}
