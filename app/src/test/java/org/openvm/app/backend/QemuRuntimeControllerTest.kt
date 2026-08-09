package org.openvm.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openvm.app.model.VmProfile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QemuRuntimeControllerTest {
    private val builder = QemuCommandBuilder()

    @Test
    fun x86CommandUsesTcgAndVirtioRawDisk() {
        val profile = VmProfile(name = "x86", architecture = "x86_64", memoryMb = 1024, vcpus = 4)
        val executable = java.io.File("runtime", "qemu-system-x86_64")
        val image = java.io.File("images", "guest.img")
        val command = builder.build(profile, executable, image)

        assertEquals(executable.absolutePath, command[0])
        assertTrue(command.containsAll(listOf("-machine", "q35,accel=tcg", "-m", "1024M", "-smp", "4")))
        assertTrue(command.contains("file=${image.absolutePath},format=raw,if=virtio"))
        assertTrue(command.containsAll(listOf("-nic", "none", "-display", "none", "-monitor", "none", "-no-reboot")))
    }

    @Test
    fun commandCanExposeOnlyAnAppPrivateUnixDisplaySocket() {
        val profile = VmProfile(name = "display", architecture = "x86_64")
        val socket = java.io.File("runtime", "display.sock")

        val command = builder.build(profile, java.io.File("qemu-system-x86_64"), java.io.File("guest.img"), socket)

        assertTrue(command.containsAll(listOf("-vnc", "unix:${socket.absolutePath}")))
    }

    @Test
    fun commandCanPointAtPrivateQemuDataDirectory() {
        val profile = VmProfile(name = "data", architecture = "arm64-v8a")
        val dataDirectory = java.io.File("runtime", "native-qemu/share/qemu")

        val command = builder.build(
            profile,
            java.io.File("qemu-system-aarch64"),
            java.io.File("guest.img"),
            qemuDataDirectory = dataDirectory,
        )

        assertTrue(command.containsAll(listOf("-L", dataDirectory.absolutePath)))
    }

    @Test
    fun kernelInitrdCommandUsesExplicitBootArtifacts() {
        val profile = VmProfile(name = "boot", architecture = "arm64-v8a")
        val kernel = java.io.File("runtime", "Image")
        val initrd = java.io.File("runtime", "ramdisk.img")

        val command = builder.build(
            profile,
            java.io.File("qemu-system-aarch64"),
            java.io.File("guest.img"),
            bootArtifacts = GuestBootArtifacts(kernel, initrd, "console=ttyAMA0 androidboot.hardware=openvm"),
        )

        assertTrue(command.containsAll(listOf(
            "-kernel", kernel.absolutePath,
            "-initrd", initrd.absolutePath,
            "-append", "console=ttyAMA0 androidboot.hardware=openvm",
        )))
    }

    @Test
    fun armCommandUsesVirtMachine() {
        val profile = VmProfile(name = "arm", architecture = "arm64-v8a")

        assertTrue(builder.build(profile, java.io.File("qemu-system-aarch64"), java.io.File("guest.img")).contains("virt,accel=tcg"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedArchitectureCannotBecomeACommand() {
        builder.build(VmProfile(name = "bad", architecture = "armeabi-v7a"), java.io.File("qemu"), java.io.File("guest.img"))
    }

    @Test
    fun controllerRejectsAssetsOutsideItsPrivateRootBeforeStartingAProcess() {
        val root = Files.createTempDirectory("openvm-runtime").toFile()
        val executable = root.resolve("qemu-system").apply {
            writeBytes(fakeX86Elf())
            setExecutable(true)
        }
        val externalImage = Files.createTempFile("openvm-external", ".img").toFile().apply { writeBytes(byteArrayOf(1)) }
        var starterCalled = false
        val controller = QemuRuntimeController(
            trustedRoot = root,
            processStarter = ProcessStarter { _, _ ->
                starterCalled = true
                error("Process must not start for an external image")
            },
        )

        val result = controller.start(VmProfile(name = "unsafe", backendId = "qemu"), executable, externalImage)

        assertEquals(RuntimeProcessState.ERROR, result.state)
        assertTrue(result.message.contains("app-private", ignoreCase = true))
        assertTrue(!starterCalled)
        controller.close()
        externalImage.delete()
        root.deleteRecursively()
    }

    @Test
    fun controllerTracksAProcessThatExitsNaturally() {
        val root = Files.createTempDirectory("openvm-runtime-exit").toFile()
        val executable = root.resolve("qemu-system").apply {
            writeBytes(fakeX86Elf())
            setExecutable(true)
        }
        val image = root.resolve("guest.img").apply { writeBytes(byteArrayOf(1)) }
        val exited = CountDownLatch(1)
        val javaExecutable = java.io.File(
            System.getProperty("java.home"),
            "bin/java${if (System.getProperty("os.name").orEmpty().startsWith("Windows")) ".exe" else ""}",
        )
        val controller = QemuRuntimeController(
            trustedRoot = root,
            processStarter = ProcessStarter { _, workingDirectory ->
                ProcessBuilder(javaExecutable.absolutePath, "-version")
                    .directory(workingDirectory)
                    .redirectErrorStream(true)
                    .start()
            },
        )

        val started = controller.start(VmProfile(name = "exit", backendId = "qemu"), executable, image) { snapshot ->
            if (snapshot.state == RuntimeProcessState.STOPPED) exited.countDown()
        }

        assertTrue(started.state == RuntimeProcessState.RUNNING || started.state == RuntimeProcessState.STOPPED)
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertEquals(RuntimeProcessState.STOPPED, controller.snapshot(started.profileId).state)
        controller.close()
        root.deleteRecursively()
    }

    @Test
    fun cancelledStartDoesNotInvokeProcessStarter() {
        val root = Files.createTempDirectory("openvm-runtime-cancel").toFile()
        val executable = root.resolve("qemu-system").apply {
            writeBytes(fakeX86Elf())
            setExecutable(true)
        }
        val image = root.resolve("guest.img").apply { writeBytes(byteArrayOf(1)) }
        val socket = root.resolve("display.sock")
        var starterCalled = false
        val controller = QemuRuntimeController(
            trustedRoot = root,
            processStarter = ProcessStarter { _, _ ->
                starterCalled = true
                error("Process must not start after cancellation")
            },
        )

        val result = controller.start(
            VmProfile(name = "cancel", backendId = "qemu"),
            executable,
            image,
            socket,
            shouldStart = { false },
        )

        assertEquals(RuntimeProcessState.STOPPED, result.state)
        assertTrue(result.message.contains("cancel", ignoreCase = true))
        assertTrue(!starterCalled)
        controller.close()
        root.deleteRecursively()
    }

    @Test
    fun closedControllerRejectsLaterStarts() {
        val root = Files.createTempDirectory("openvm-runtime-closed").toFile()
        val executable = root.resolve("qemu-system").apply {
            writeBytes(fakeX86Elf())
            setExecutable(true)
        }
        val image = root.resolve("guest.img").apply { writeBytes(byteArrayOf(1)) }
        val controller = QemuRuntimeController(trustedRoot = root)
        controller.close()

        val result = controller.start(VmProfile(name = "closed", backendId = "qemu"), executable, image)

        assertEquals(RuntimeProcessState.ERROR, result.state)
        assertTrue(result.message.contains("closed", ignoreCase = true))
        root.deleteRecursively()
    }

    private fun fakeX86Elf(): ByteArray = ByteArray(20).apply {
        this[0] = 0x7f.toByte()
        this[1] = 'E'.code.toByte()
        this[2] = 'L'.code.toByte()
        this[3] = 'F'.code.toByte()
        this[4] = 2
        this[5] = 1
        this[18] = 62
    }
}
