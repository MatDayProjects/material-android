package org.openvm.app.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VmProfileTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun aProfileWithShippedDefaultsIsValid() {
        val profile = VmProfile(name = "Pixel lab")

        assertTrue(profile.validationErrors().isEmpty())
    }

    @Test
    fun resourceBoundsAreRejected() {
        val profile = VmProfile(name = "broken", memoryMb = 128, storageGb = 0, vcpus = 0)

        assertEquals(3, profile.validationErrors().size)
    }

    @Test
    fun profileDocumentRoundTripsWithoutLosingImageUri() {
        val original = VmProfile(
            name = "Android test",
            androidVersion = "Android 15",
            imageUri = "content://com.example.documents/tree/guest.img",
        )
        val encoded = json.encodeToString(VmProfile.serializer(), original)
        val decoded = json.decodeFromString(VmProfile.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun runningStateIsNotPortableProfileMetadata() {
        val profile = VmProfile(name = "Runtime test", status = VmStatus.RUNNING)

        val restored = profile.restoredForHost()

        assertEquals(VmStatus.STOPPED, restored.status)
    }

    @Test
    fun qemuProfilesRetainTheSelectedExecutableReference() {
        val profile = VmProfile(
            name = "QEMU test",
            backendId = "qemu",
            qemuExecutableUri = "content://com.example.documents/qemu-system-x86_64",
            guestManifestUri = "content://com.example.documents/guest.json",
            kernelUri = "content://com.example.documents/Image",
            initrdUri = "content://com.example.documents/ramdisk.img",
        )

        assertTrue(profile.validationErrors().isEmpty())
        assertEquals("qemu", profile.backendId)
        assertEquals("content://com.example.documents/qemu-system-x86_64", profile.qemuExecutableUri)
        assertEquals("content://com.example.documents/guest.json", profile.guestManifestUri)
        assertEquals("content://com.example.documents/Image", profile.kernelUri)
        assertEquals("content://com.example.documents/ramdisk.img", profile.initrdUri)
    }

    @Test
    fun unknownRuntimeBackendsAreRejected() {
        val profile = VmProfile(name = "Unknown backend", backendId = "mystery")

        assertTrue(profile.validationErrors().any { it.contains("backend", ignoreCase = true) })
    }
}
