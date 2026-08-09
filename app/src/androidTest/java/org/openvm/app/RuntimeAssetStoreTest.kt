package org.openvm.app

import android.net.Uri
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openvm.app.runtime.RuntimeAssetStore

@RunWith(AndroidJUnit4::class)
class RuntimeAssetStoreTest {
    @Test
    fun guestImageIsCopiedHashedAndCommittedInsideAppStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "openvm-guest-image-test.img").apply { writeBytes("guest-image".toByteArray()) }
        val store = RuntimeAssetStore(context)

        try {
            val asset = store.materializeGuestImage("instrumentation-profile", Uri.fromFile(source), 1024)

            assertTrue(asset.file.isFile)
            assertEquals("guest-image".length.toLong(), asset.sizeBytes)
            assertEquals("e94ce1d722a89cc7b692b3db45b5beab1eb9a77a5232ef5211bc56ee59a21148", asset.sha256)
            assertTrue(asset.file.canonicalPath.contains("runtime-assets"))
        } finally {
            source.delete()
            store.existingGuestImage("instrumentation-profile").delete()
        }
    }

    @Test
    fun profileIdsThatSanitizeToTheSameTextUseDifferentAssetPaths() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "openvm-collision-test.img").apply { writeBytes(byteArrayOf(7)) }
        val store = RuntimeAssetStore(context)

        try {
            val first = store.materializeGuestImage("a/b", Uri.fromFile(source), 1024)
            val second = store.materializeGuestImage("a?b", Uri.fromFile(source), 1024)

            assertTrue(first.file != second.file)
            assertTrue(first.file.isFile)
            assertTrue(second.file.isFile)
        } finally {
            source.delete()
            store.existingGuestImage("a/b").delete()
            store.existingGuestImage("a?b").delete()
        }
    }
}
