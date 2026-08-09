package org.openvm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.openvm.app.backend.NativeQemuRuntime
import org.openvm.app.backend.QemuRuntimeController
import org.openvm.app.backend.RuntimeProcessState
import org.openvm.app.model.VmProfile
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NativeQemuRuntimeSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledRuntimeExecutesVersionAndMachineHelpWhenPackaged() {
        val runtime = requireRuntimeOrSkip()

        val version = runQemu(runtime.executable, runtime.dataDirectory, "--version")
        assertEquals(0, version.exitCode)
        assertTrue(version.output.contains("QEMU emulator version 11.0.3"))

        val machines = runQemu(runtime.executable, runtime.dataDirectory, "-machine", "help")
        assertEquals(0, machines.exitCode)
        assertTrue(machines.output.contains("virt"))
        assertTrue(machines.output.contains("q35"))
    }

    @Test
    fun controllerStartsAndStopsThePackagedRuntimeThroughItsProductionPath() {
        val runtime = requireRuntimeOrSkip()
        val image = File(context.filesDir, "runtime-assets/native-smoke.img")
        image.parentFile?.mkdirs()
        image.outputStream().use { output -> output.channel.truncate(1024L * 1024L) }
        val profile = VmProfile(
            name = "native controller smoke",
            architecture = "x86_64",
            backendId = "qemu",
            imageUri = "native-smoke.img",
        )
        val controller = QemuRuntimeController(context)
        try {
            val started = controller.start(
                profile = profile,
                executable = runtime.executable,
                image = image,
                qemuDataDirectory = runtime.dataDirectory,
            )
            assertEquals(RuntimeProcessState.RUNNING, started.state)
            val stopped = controller.stop(profile.id)
            assertNotEquals(RuntimeProcessState.RUNNING, stopped.state)
        } finally {
            controller.close()
            image.delete()
        }
    }

    private fun requireRuntimeOrSkip() = NativeQemuRuntime(context).locate(
        VmProfile(name = "native smoke", architecture = "x86_64"),
    ).also { runtime ->
        val required = InstrumentationRegistry.getArguments()
            .getString("requireNativeRuntime")
            .toBoolean()
        if (required) {
            requireNotNull(runtime) { "The packaged-runtime lane requires a bundled x86_64 QEMU runtime" }
        } else {
            assumeTrue("This source-only APK intentionally has no bundled QEMU runtime", runtime != null)
        }
    }!!

    private fun runQemu(executable: File, dataDirectory: File?, vararg arguments: String): ProcessResult {
        val command = buildList {
            add(executable.absolutePath)
            if (dataDirectory != null) addAll(listOf("-L", dataDirectory.absolutePath))
            arguments.forEach(::add)
        }
        val process = ProcessBuilder(command)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
            }
            .start()
        assertTrue("QEMU probe timed out", process.waitFor(20, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(val exitCode: Int, val output: String)
}
