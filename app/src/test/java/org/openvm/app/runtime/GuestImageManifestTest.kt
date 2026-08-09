package org.openvm.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.openvm.app.model.VmProfile

class GuestImageManifestTest {
    @Test
    fun validDiskOnlyManifestLoads() {
        val manifest = GuestImageManifestLoader.load(diskOnlyJson())

        assertEquals("arm64-v8a", manifest.architecture)
        assertEquals("virt", manifest.machine)
        assertEquals(4096L, manifest.sizeBytes)
        assertTrue(manifest.validationErrors().isEmpty())
    }

    @Test
    fun validKernelInitrdManifestLoads() {
        val manifest = GuestImageManifestLoader.load(
            diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"boot/Image\",\"initrdPath\":\"boot/initrd\",\"kernelCommandLine\":\"console=ttyAMA0\",\"kernelSizeBytes\":4,\"kernelSha256\":\"$HASH\",\"initrdSizeBytes\":4,\"initrdSha256\":\"$HASH\"",
            ),
        )

        assertEquals("boot/Image", manifest.kernelPath)
        assertEquals("boot/initrd", manifest.initrdPath)
        assertEquals("console=ttyAMA0", manifest.kernelCommandLine)
        assertEquals(4L, manifest.kernelSizeBytes)
        assertEquals(HASH, manifest.kernelSha256)
        assertEquals(4L, manifest.initrdSizeBytes)
        assertEquals(HASH, manifest.initrdSha256)
    }

    @Test
    fun requiredFieldsAreRejectedWhenMissing() {
        assertRejected(diskOnlyJson().replace("\"sha256\":\"$HASH\",", ""), "sha256")
    }

    @Test
    fun unsupportedSchemaArchitectureMachineFormatAndContractAreRejected() {
        assertRejected(diskOnlyJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2"), "schema version")
        assertRejected(diskOnlyJson().replace("arm64-v8a", "armeabi-v7a"), "architecture")
        assertRejected(diskOnlyJson().replace("\"machine\":\"virt\"", "\"machine\":\"microvm\""), "machine")
        assertRejected(diskOnlyJson().replace("\"diskFormat\":\"raw\"", "\"diskFormat\":\"qcow2\""), "disk format")
        assertRejected(diskOnlyJson().replace("\"bootContract\":\"disk-only\"", "\"bootContract\":\"uefi\""), "boot contract")
    }

    @Test
    fun architectureAndMachinePairMustAgree() {
        assertRejected(diskOnlyJson().replace("\"machine\":\"virt\"", "\"machine\":\"q35\""), "does not match")
    }

    @Test
    fun kernelInitrdRequiresBothSafePaths() {
        val noKernel = diskOnlyJson().replace(
            "\"bootContract\":\"disk-only\"",
            "\"bootContract\":\"kernel-initrd\",\"initrdPath\":\"boot/initrd\"",
        )
        val noInitrd = diskOnlyJson().replace(
            "\"bootContract\":\"disk-only\"",
            "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"boot/Image\"",
        )

        assertRejected(noKernel, "kernelPath")
        assertRejected(noInitrd, "initrdPath")
    }

    @Test
    fun unsafeBootPathsAreRejected() {
        listOf(
            "../Image" to "traversal",
            "/boot/Image" to "absolute",
            "https://example.test/Image" to "URI scheme",
            "boot\\Image" to "backslashes",
            "boot//Image" to "empty path component",
            "boot/./Image" to "traversal",
            "boot/../Image" to "traversal",
            "boot/Image/Image" to "duplicate",
            "C:/boot/Image" to "URI scheme",
        ).forEach { (path, expectedMessage) ->
            val encodedPath = path.replace("\\", "\\\\")
            val json = diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"$encodedPath\",\"initrdPath\":\"boot/initrd\"",
            )
            assertRejected(json, expectedMessage)
        }
    }

    @Test
    fun diskOnlyRejectsBootArtifacts() {
        assertRejected(
            diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"disk-only\",\"kernelPath\":\"boot/Image\"",
            ),
            "must not include",
        )
    }

    @Test
    fun kernelCommandLineIsBoundedAndFreeOfControlCharacters() {
        assertRejected(
            diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"boot/Image\",\"initrdPath\":\"boot/initrd\",\"kernelCommandLine\":\"${"x".repeat(GuestImageManifest.MAX_KERNEL_COMMAND_LINE_CHARS + 1)}\"",
            ),
            "kernelCommandLine",
        )
        assertRejected(
            diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"boot/Image\",\"initrdPath\":\"boot/initrd\",\"kernelCommandLine\":\"console=tty\\nserial\"",
            ),
            "control character",
        )
    }

    @Test
    fun sizeAndHashBoundsAreEnforced() {
        assertRejected(diskOnlyJson().replace("\"sizeBytes\":4096", "\"sizeBytes\":0"), "sizeBytes")
        assertRejected(
            diskOnlyJson().replace("\"sizeBytes\":4096", "\"sizeBytes\":4398046511105"),
            "sizeBytes",
        )
        assertRejected(diskOnlyJson().replace(HASH, "A".repeat(64)), "sha256")
        assertRejected(diskOnlyJson().replace(HASH, "0".repeat(63)), "sha256")
    }

    @Test
    fun unknownAndDuplicateFieldsAreRejected() {
        assertRejected(diskOnlyJson().replace("}", ",\"futureField\":true}"), "unknown")
        assertRejected(diskOnlyJson().replace("}", ",\"schemaVersion\":1}"), "Duplicate")
    }

    @Test
    fun jsonInputIsBoundedByUtf8ByteSize() {
        val oversized = diskOnlyJson().replace("}", ",\"padding\":\"${"x".repeat(GuestImageManifest.MAX_JSON_BYTES)}\"}")

        assertRejected(oversized, "at most")
    }

    @Test
    fun malformedUtf8IsRejectedInsteadOfBeingReplaced() {
        assertRejectedBytes(diskOnlyJson().toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte(), 0x28), "UTF-8")
    }

    @Test
    fun duplicateGuardHasARecursionDepthLimit() {
        val nested = buildString {
            append("{\"nested\":")
            repeat(40) { append('[') }
            append("null")
            repeat(40) { append(']') }
            append('}')
        }

        assertRejected(nested, "nested too deeply")
    }

    @Test
    fun profileArchitectureAndDerivedMachineMustMatch() {
        val armManifest = GuestImageManifestLoader.load(diskOnlyJson())
        val x86Profile = VmProfile(name = "x86", architecture = "x86_64")

        val errors = armManifest.validationErrors(x86Profile)

        assertTrue(errors.any { it.contains("architecture") && it.contains("does not match") })
        assertTrue(errors.any { it.contains("machine") && it.contains("expected q35") })
        assertFalse(armManifest.isCompatibleWith(x86Profile))
        assertTrue(armManifest.isCompatibleWith(VmProfile(name = "arm", architecture = "arm64-v8a")))
    }

    @Test
    fun loaderAliasAndCompatibilityRequirementUseTheSameValidation() {
        val manifest = loadGuestImageManifest(diskOnlyJson())
        val profile = VmProfile(name = "arm", architecture = "arm64-v8a")

        assertEquals(manifest, manifest.requireCompatibleWith(profile))
    }

    @Test
    fun imageIntegrityMetadataMustMatchMaterializedAsset() {
        val manifest = GuestImageManifestLoader.load(diskOnlyJson())
        val matching = MaterializedRuntimeAsset(
            file = java.io.File("guest.img"),
            sizeBytes = 4096L,
            sha256 = HASH,
        )
        val wrongHash = matching.copy(sha256 = "f".repeat(64))
        val wrongSize = matching.copy(sizeBytes = 4095L)

        assertEquals(manifest, manifest.requireImageMatch(matching))
        assertRejectedIntegrity { manifest.requireImageMatch(wrongHash) }
        assertRejectedIntegrity({ manifest.requireImageMatch(wrongSize) }, "size")
    }

    @Test
    fun kernelAndInitrdIntegrityMetadataMustMatchMaterializedAssets() {
        val manifest = GuestImageManifestLoader.load(
            diskOnlyJson().replace(
                "\"bootContract\":\"disk-only\"",
                "\"bootContract\":\"kernel-initrd\",\"kernelPath\":\"boot/Image\",\"initrdPath\":\"boot/initrd\",\"kernelSizeBytes\":4,\"kernelSha256\":\"$HASH\",\"initrdSizeBytes\":4,\"initrdSha256\":\"$HASH\"",
            ),
        )
        val matching = MaterializedRuntimeAsset(java.io.File("Image"), 4L, HASH)

        assertEquals(manifest, manifest.requireKernelMatch(matching))
        assertEquals(manifest, manifest.requireInitrdMatch(matching))
        assertRejectedIntegrity({ manifest.requireKernelMatch(matching.copy(sizeBytes = 3L)) }, "kernel size")
        assertRejectedIntegrity({ manifest.requireInitrdMatch(matching.copy(sha256 = "f".repeat(64))) }, "initrd SHA-256")
    }

    private fun assertRejected(json: String, expectedMessagePart: String) {
        try {
            GuestImageManifestLoader.load(json)
            fail("Expected manifest rejection containing: $expectedMessagePart")
        } catch (error: GuestImageManifestException) {
            assertTrue(
                "Expected '${error.message}' to contain '$expectedMessagePart'",
                error.message?.contains(expectedMessagePart, ignoreCase = true) == true,
            )
        }
    }

    private fun assertRejectedIntegrity(action: () -> Unit) {
        assertRejectedIntegrity(action, "SHA-256")
    }

    private fun assertRejectedIntegrity(action: () -> Unit, expectedMessagePart: String) {
        try {
            action()
            fail("Expected image integrity rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message?.contains(expectedMessagePart, ignoreCase = true) == true)
        }
    }

    private fun assertRejectedBytes(bytes: ByteArray, expectedMessagePart: String) {
        try {
            GuestImageManifestLoader.load(bytes)
            fail("Expected manifest rejection containing: $expectedMessagePart")
        } catch (error: GuestImageManifestException) {
            assertTrue(error.message?.contains(expectedMessagePart, ignoreCase = true) == true)
        }
    }

    private fun diskOnlyJson(): String = """
        {
          "schemaVersion":1,
          "architecture":"arm64-v8a",
          "machine":"virt",
          "diskFormat":"raw",
          "sizeBytes":4096,
          "sha256":"$HASH",
          "bootContract":"disk-only"
        }
    """.trimIndent()

    private companion object {
        const val HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
