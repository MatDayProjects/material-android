package org.openvm.app.backend

import android.content.Context
import android.os.Build
import org.openvm.app.model.VmProfile
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class RuntimeProcessState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class RuntimeProcessSnapshot(
    val profileId: String,
    val state: RuntimeProcessState,
    val message: String,
    val exitCode: Int? = null,
    val outputTail: List<String> = emptyList(),
    val displaySocketPath: String? = null,
)

fun interface ProcessStarter {
    fun start(command: List<String>, workingDirectory: File): Process
}

/** Builds a shell-free, deterministic QEMU command for a raw guest disk image. */
class QemuCommandBuilder {
    fun build(profile: VmProfile, executable: File, image: File, displaySocket: File? = null): List<String> {
        val machine = when (profile.architecture) {
            "x86_64" -> "q35,accel=tcg"
            "arm64-v8a" -> "virt,accel=tcg"
            else -> throw IllegalArgumentException("Unsupported guest architecture: ${profile.architecture}")
        }
        return buildList {
            addAll(listOf(
            executable.absolutePath,
            "-machine", machine,
            "-m", "${profile.memoryMb}M",
            "-smp", profile.vcpus.toString(),
            "-drive", "file=${image.absolutePath},format=raw,if=virtio",
            "-display", "none",
            "-serial", "stdio",
            "-monitor", "none",
            "-no-reboot",
            ))
            if (displaySocket != null) addAll(listOf("-vnc", "unix:${displaySocket.absolutePath}"))
        }
    }
}

/**
 * Owns one QEMU process per profile. It never promotes a missing, invalid, or exited
 * process to RUNNING, and it never invokes a shell to construct the command line.
 */
class QemuRuntimeController(
    private val trustedRoot: File,
    private val commandBuilder: QemuCommandBuilder = QemuCommandBuilder(),
    private val supportedHostMachines: Set<Int> = emptySet(),
    private val processStarter: ProcessStarter = ProcessStarter { command, workingDirectory ->
        ProcessBuilder(command)
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .start()
    },
    private val executor: ExecutorService = Executors.newCachedThreadPool(),
) : AutoCloseable {
    constructor(
        context: Context,
        commandBuilder: QemuCommandBuilder = QemuCommandBuilder(),
    ) : this(File(context.filesDir, RUNTIME_ASSET_DIRECTORY), commandBuilder, supportedHostMachines())

    private data class RunningProcess(
        val process: Process,
        val output: OutputTail,
        val listener: (RuntimeProcessSnapshot) -> Unit,
        val displaySocket: File?,
    )

    private val running = ConcurrentHashMap<String, RunningProcess>()
    private val snapshots = ConcurrentHashMap<String, RuntimeProcessSnapshot>()

    fun start(
        profile: VmProfile,
        executable: File,
        image: File,
        displaySocket: File? = null,
        shouldStart: () -> Boolean = { true },
        listener: (RuntimeProcessSnapshot) -> Unit = {},
    ): RuntimeProcessSnapshot {
        synchronized(running) {
            val existing = running[profile.id]
            if (existing != null && isAlive(existing.process)) {
                return snapshots[profile.id]
                    ?: RuntimeProcessSnapshot(profile.id, RuntimeProcessState.RUNNING, "QEMU is already running")
            }
        }
        val initial = RuntimeProcessSnapshot(profile.id, RuntimeProcessState.STARTING, "Validating QEMU runtime assets")
        snapshots[profile.id] = initial
        val command = try {
            require(profile.backendId == "qemu") { "The selected profile is not configured for QEMU" }
            require(profile.validationErrors().isEmpty()) { "The VM profile is invalid: ${profile.validationErrors().joinToString("; ")}" }
            validateAsset(executable, "QEMU executable", mustExecute = true)
            validateElfExecutable(executable)
            validateAsset(image, "Guest image", mustExecute = false)
            validateDisplaySocket(displaySocket)
            commandBuilder.build(profile, executable, image, displaySocket)
        } catch (error: Throwable) {
            return fail(profile.id, error.message ?: "QEMU runtime validation failed", listener)
        }

        synchronized(running) {
            val existing = running[profile.id]
            if (existing != null && isAlive(existing.process)) {
                return RuntimeProcessSnapshot(profile.id, RuntimeProcessState.RUNNING, "QEMU is already running")
            }
            if (!shouldStart()) {
                displaySocket?.delete()
                return cancel(profile.id, displaySocket, listener)
            }
            if (existing != null) running.remove(profile.id)
            val process = try {
                processStarter.start(command, trustedRoot)
            } catch (error: Throwable) {
                return fail(profile.id, error.message ?: "QEMU could not be started", listener)
            }
            val output = OutputTail()
            val record = RunningProcess(process, output, listener, displaySocket)
            running[profile.id] = record
            executor.execute { drainOutput(process, output) }
            if (!isAlive(process)) {
                val exitCode = process.exitValueOrNull()
                return finish(profile.id, record, exitCode, listener)
            }
            val started = RuntimeProcessSnapshot(
                profile.id,
                RuntimeProcessState.RUNNING,
                "QEMU process started",
                displaySocketPath = displaySocket?.absolutePath,
            )
            snapshots[profile.id] = started
            listener(started)
            executor.execute { awaitExit(profile.id, record) }
            return started
        }
    }

    fun stop(profileId: String): RuntimeProcessSnapshot {
        val record = synchronized(running) { running[profileId] }
            ?: return snapshots[profileId] ?: RuntimeProcessSnapshot(profileId, RuntimeProcessState.STOPPED, "QEMU is not running")
        val stopping = RuntimeProcessSnapshot(profileId, RuntimeProcessState.STOPPING, "Stopping QEMU")
        snapshots[profileId] = stopping
        record.listener(stopping)
        record.process.destroy()
        var exited = waitForExit(record.process, STOP_TIMEOUT_MILLIS)
        if (!exited) {
            record.process.destroyForcibly()
            exited = waitForExit(record.process, FORCE_STOP_TIMEOUT_MILLIS)
        }
        val stopped = if (exited) {
            record.displaySocket?.delete()
            RuntimeProcessSnapshot(
                profileId,
                RuntimeProcessState.STOPPED,
                "QEMU stopped",
                record.process.exitValueOrNull(),
                record.output.snapshot(),
                record.displaySocket?.absolutePath,
            )
        } else {
            RuntimeProcessSnapshot(
                profileId,
                RuntimeProcessState.ERROR,
                "QEMU did not exit after a forced stop",
                record.process.exitValueOrNull(),
                record.output.snapshot(),
                record.displaySocket?.absolutePath,
            )
        }
        if (exited) synchronized(running) { running.remove(profileId, record) }
        snapshots[profileId] = stopped
        record.listener(stopped)
        return stopped
    }

    fun snapshot(profileId: String): RuntimeProcessSnapshot =
        snapshots[profileId] ?: RuntimeProcessSnapshot(profileId, RuntimeProcessState.STOPPED, "QEMU is not running")

    override fun close() {
        running.keys.toList().forEach(::stop)
        executor.shutdownNow()
    }

    private fun awaitExit(profileId: String, record: RunningProcess) {
        val exitCode = runCatching { record.process.waitFor() }.getOrNull()
        val current = synchronized(running) { running[profileId] }
        if (current !== record) return
        finish(profileId, record, exitCode, record.listener)
    }

    private fun finish(
        profileId: String,
        record: RunningProcess,
        exitCode: Int?,
        listener: (RuntimeProcessSnapshot) -> Unit,
    ): RuntimeProcessSnapshot {
        val state = if (exitCode == 0) RuntimeProcessState.STOPPED else RuntimeProcessState.ERROR
        val message = if (state == RuntimeProcessState.STOPPED) "QEMU exited" else "QEMU exited with code ${exitCode ?: "unknown"}"
        record.displaySocket?.delete()
        val result = RuntimeProcessSnapshot(
            profileId,
            state,
            message,
            exitCode,
            record.output.snapshot(),
            record.displaySocket?.absolutePath,
        )
        synchronized(running) { running.remove(profileId, record) }
        snapshots[profileId] = result
        listener(result)
        return result
    }

    private fun fail(profileId: String, message: String, listener: (RuntimeProcessSnapshot) -> Unit): RuntimeProcessSnapshot {
        val result = RuntimeProcessSnapshot(profileId, RuntimeProcessState.ERROR, message)
        snapshots[profileId] = result
        listener(result)
        return result
    }

    private fun cancel(profileId: String, displaySocket: File?, listener: (RuntimeProcessSnapshot) -> Unit): RuntimeProcessSnapshot {
        val result = RuntimeProcessSnapshot(
            profileId,
            RuntimeProcessState.STOPPED,
            "QEMU start cancelled",
            displaySocketPath = displaySocket?.absolutePath,
        )
        snapshots[profileId] = result
        listener(result)
        return result
    }

    private fun validateAsset(file: File, label: String, mustExecute: Boolean) {
        val canonicalRoot = trustedRoot.canonicalFile
        val canonical = file.canonicalFile
        require(canonical.exists() && canonical.isFile) { "$label is missing" }
        require(canonical.length() > 0L) { "$label is empty" }
        require(canonical.path == canonicalRoot.path || canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            "$label must be inside the app-private runtime directory"
        }
        require(canonical.canRead()) { "$label is not readable" }
        if (mustExecute) require(canonical.canExecute() || File.separatorChar == '\\') { "$label is not executable" }
    }

    private fun validateDisplaySocket(socket: File?) {
        if (socket == null) return
        val canonicalRoot = trustedRoot.canonicalFile
        val canonical = socket.canonicalFile
        require(canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            "The guest display socket must be inside the app-private runtime directory"
        }
        require(!canonical.exists() || canonical.delete()) { "The stale guest display socket could not be removed" }
        canonical.parentFile?.mkdirs()
        require(canonical.parentFile?.isDirectory == true) { "The guest display socket directory could not be created" }
    }

    private fun validateElfExecutable(file: File) {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) break
                offset += read
            }
            require(offset == header.size && header.copyOfRange(0, ELF_MAGIC.size).contentEquals(ELF_MAGIC)) {
                "QEMU executable is not an ELF binary"
            }
            require(header[4].toInt() == ELFCLASS64 && header[5].toInt() == ELFDATA2LSB) {
                "QEMU executable is not a little-endian 64-bit ELF binary"
            }
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            require(supportedHostMachines.isEmpty() || machine in supportedHostMachines) {
                "QEMU executable does not match the Android host ABI"
            }
        }
    }

    private fun drainOutput(process: Process, output: OutputTail) {
        runCatching {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buffer = CharArray(4096)
                val line = StringBuilder(MAX_OUTPUT_LINE_CHARS)
                var truncated = false
                fun flushLine() {
                    val value = line.toString().removeSuffix("\r") + if (truncated) "… [truncated]" else ""
                    output.add(value)
                    line.setLength(0)
                    truncated = false
                }
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    repeat(count) { index ->
                        val character = buffer[index]
                        if (character == '\n') {
                            flushLine()
                        } else if (line.length < MAX_OUTPUT_LINE_CHARS) {
                            line.append(character)
                        } else {
                            truncated = true
                        }
                    }
                }
                if (line.isNotEmpty() || truncated) flushLine()
            }
        }
    }

    private fun isAlive(process: Process): Boolean = process.exitValueOrNull() == null

    private fun Process.exitValueOrNull(): Int? = try {
        exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (isAlive(process) && System.nanoTime() < deadline) Thread.sleep(POLL_MILLIS)
        return !isAlive(process)
    }

    private class OutputTail {
        private val lines = ArrayDeque<String>()

        @Synchronized
        fun add(line: String) {
            if (lines.size == MAX_OUTPUT_LINES) lines.removeFirst()
            lines.addLast(line)
        }

        @Synchronized
        fun snapshot(): List<String> = lines.toList()
    }

    companion object {
        const val RUNTIME_ASSET_DIRECTORY = "runtime-assets"
        private const val MAX_OUTPUT_LINES = 80
        private const val STOP_TIMEOUT_MILLIS = 3000L
        private const val FORCE_STOP_TIMEOUT_MILLIS = 1000L
        private const val POLL_MILLIS = 50L
        private const val MAX_OUTPUT_LINE_CHARS = 16_384
        private const val ELFCLASS64 = 2
        private const val ELFDATA2LSB = 1
        private val ELF_MAGIC = byteArrayOf(0x7f.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())

        private fun supportedHostMachines(): Set<Int> = Build.SUPPORTED_64_BIT_ABIS.mapNotNull {
            when (it) {
                "x86_64" -> 62
                "arm64-v8a" -> 183
                else -> null
            }
        }.toSet()
    }
}
