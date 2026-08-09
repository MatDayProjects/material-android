package org.openvm.app.runtime

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openvm.app.model.VmProfile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** A bounded, portable description of the files QEMU needs for one guest image. */
@Serializable
data class GuestImageManifest(
    val schemaVersion: Int,
    val architecture: String,
    val machine: String,
    val diskFormat: String,
    val sizeBytes: Long,
    val sha256: String,
    val bootContract: String,
    val kernelPath: String? = null,
    val initrdPath: String? = null,
    val kernelCommandLine: String? = null,
    val kernelSizeBytes: Long? = null,
    val kernelSha256: String? = null,
    val initrdSizeBytes: Long? = null,
    val initrdSha256: String? = null,
) {
    fun validationErrors(): List<String> = buildList {
        if (schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
            add("Unsupported guest image manifest schema version: $schemaVersion")
        }

        if (architecture !in SUPPORTED_ARCHITECTURES) {
            add("Unsupported guest image architecture: $architecture")
        }

        if (machine !in SUPPORTED_MACHINES) {
            add("Unsupported QEMU machine: $machine")
        } else {
            val expectedMachine = machineForArchitecture(architecture)
            if (expectedMachine != null && machine != expectedMachine) {
                add("QEMU machine $machine does not match architecture $architecture; expected $expectedMachine")
            }
        }

        if (diskFormat !in SUPPORTED_DISK_FORMATS) {
            add("Unsupported guest image disk format: $diskFormat")
        }

        if (sizeBytes <= 0L || sizeBytes > MAX_SIZE_BYTES) {
            add("Guest image sizeBytes must be greater than 0 and at most $MAX_SIZE_BYTES bytes")
        }

        if (!SHA256_PATTERN.matches(sha256)) {
            add("Guest image sha256 must be exactly 64 lowercase hexadecimal characters")
        }

        if (bootContract !in SUPPORTED_BOOT_CONTRACTS) {
            add("Unsupported guest image boot contract: $bootContract")
        } else if (bootContract == BOOT_CONTRACT_DISK_ONLY) {
            if (kernelPath != null || initrdPath != null || kernelCommandLine != null ||
                kernelSizeBytes != null || kernelSha256 != null || initrdSizeBytes != null || initrdSha256 != null
            ) {
                add("disk-only bootContract must not include kernel/initrd boot metadata")
            }
        } else {
            validateBootPath("kernelPath", kernelPath)?.let(::add)
            validateBootPath("initrdPath", initrdPath)?.let(::add)
            validateBootArtifact("kernel", kernelSizeBytes, kernelSha256)?.let(::add)
            validateBootArtifact("initrd", initrdSizeBytes, initrdSha256)?.let(::add)
            if (kernelCommandLine != null) {
                if (kernelCommandLine.length > MAX_KERNEL_COMMAND_LINE_CHARS) {
                    add("kernelCommandLine must be at most $MAX_KERNEL_COMMAND_LINE_CHARS characters")
                }
                if (kernelCommandLine.any { Character.isISOControl(it) }) {
                    add("kernelCommandLine contains an invalid control character")
                }
            }
        }
    }

    fun validationErrors(profile: VmProfile): List<String> = buildList {
        addAll(validationErrors())

        if (profile.architecture !in SUPPORTED_ARCHITECTURES) {
            add("VM profile has unsupported guest architecture: ${profile.architecture}")
        } else if (architecture != profile.architecture) {
            add("Guest image architecture $architecture does not match VM profile architecture ${profile.architecture}")
        }

        machineForArchitecture(profile.architecture)?.let { expectedMachine ->
            if (machine != expectedMachine) {
                add("Guest image machine $machine does not match VM profile architecture ${profile.architecture}; expected $expectedMachine")
            }
        }
    }

    fun isCompatibleWith(profile: VmProfile): Boolean = validationErrors(profile).isEmpty()

    fun requireCompatibleWith(profile: VmProfile): GuestImageManifest {
        val errors = validationErrors(profile)
        if (errors.isNotEmpty()) {
            throw GuestImageManifestException(errors.joinToString("; "))
        }
        return this
    }

    fun requireImageMatch(asset: MaterializedRuntimeAsset): GuestImageManifest {
        require(asset.sizeBytes == sizeBytes) {
            "Guest image size ${asset.sizeBytes} does not match manifest size $sizeBytes"
        }
        require(asset.sha256 == sha256) {
            "Guest image SHA-256 ${asset.sha256} does not match the manifest"
        }
        return this
    }

    fun requireKernelMatch(asset: MaterializedRuntimeAsset): GuestImageManifest =
        requireBootArtifactMatch("kernel", asset, kernelSizeBytes, kernelSha256)

    fun requireInitrdMatch(asset: MaterializedRuntimeAsset): GuestImageManifest =
        requireBootArtifactMatch("initrd", asset, initrdSizeBytes, initrdSha256)

    private fun requireBootArtifactMatch(
        label: String,
        asset: MaterializedRuntimeAsset,
        expectedSize: Long?,
        expectedSha256: String?,
    ): GuestImageManifest {
        require(expectedSize != null && expectedSha256 != null) {
            "The manifest does not contain complete $label integrity metadata"
        }
        require(asset.sizeBytes == expectedSize) {
            "$label size ${asset.sizeBytes} does not match manifest size $expectedSize"
        }
        require(asset.sha256 == expectedSha256) {
            "$label SHA-256 ${asset.sha256} does not match the manifest"
        }
        return this
    }

    companion object {
        const val MAX_JSON_BYTES: Int = 64 * 1024
        const val MAX_SIZE_BYTES: Long = 4L * 1024L * 1024L * 1024L * 1024L
        const val MAX_KERNEL_COMMAND_LINE_CHARS: Int = 4096
        const val BOOT_CONTRACT_DISK_ONLY: String = "disk-only"
        const val BOOT_CONTRACT_KERNEL_INITRD: String = "kernel-initrd"

        val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)
        val SUPPORTED_ARCHITECTURES: Set<String> = setOf("arm64-v8a", "x86_64")
        val SUPPORTED_MACHINES: Set<String> = setOf("virt", "q35")
        val SUPPORTED_DISK_FORMATS: Set<String> = setOf("raw")
        val SUPPORTED_BOOT_CONTRACTS: Set<String> = setOf(
            BOOT_CONTRACT_DISK_ONLY,
            BOOT_CONTRACT_KERNEL_INITRD,
        )

        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val URI_SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

        fun machineForArchitecture(architecture: String): String? = when (architecture) {
            "arm64-v8a" -> "virt"
            "x86_64" -> "q35"
            else -> null
        }

        private fun validateBootPath(fieldName: String, path: String?): String? {
            if (path == null || path.isBlank()) {
                return "$fieldName must be a nonblank relative path"
            }
            if (path.contains('\\')) {
                return "$fieldName must not contain backslashes"
            }
            if (path.startsWith('/')) {
                return "$fieldName must be relative, not absolute"
            }
            if (URI_SCHEME_PATTERN.containsMatchIn(path)) {
                return "$fieldName must not contain a URI scheme"
            }
            if (path.any { Character.isISOControl(it) }) {
                return "$fieldName contains an invalid control character"
            }

            val components = path.split('/')
            if (components.any { it.isEmpty() }) {
                return "$fieldName contains an empty path component"
            }
            if (components.any { it == "." || it == ".." }) {
                return "$fieldName must not contain traversal path components"
            }
            if (components.any { it.contains(':') }) {
                return "$fieldName contains an invalid path component"
            }
            if (components.distinct().size != components.size) {
                return "$fieldName must not contain duplicate path components"
            }
            return null
        }

        private fun validateBootArtifact(label: String, sizeBytes: Long?, sha256: String?): String? {
            if (sizeBytes == null || sha256 == null) {
                return "$label boot artifact metadata must include sizeBytes and sha256"
            }
            if (sizeBytes <= 0L || sizeBytes > MAX_BOOT_ARTIFACT_BYTES) {
                return "$label sizeBytes must be greater than 0 and at most $MAX_BOOT_ARTIFACT_BYTES bytes"
            }
            if (!SHA256_PATTERN.matches(sha256)) {
                return "$label sha256 must be exactly 64 lowercase hexadecimal characters"
            }
            return null
        }

        private const val MAX_BOOT_ARTIFACT_BYTES: Long = 512L * 1024L * 1024L
    }
}

class GuestImageManifestException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object GuestImageManifestLoader {
    private val strictJson = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = true
    }

    fun load(json: String): GuestImageManifest = parse(json, json.toByteArray(Charsets.UTF_8).size)

    fun load(bytes: ByteArray): GuestImageManifest {
        if (bytes.size > GuestImageManifest.MAX_JSON_BYTES) {
            throw GuestImageManifestException(
                "Guest image manifest JSON must be at most ${GuestImageManifest.MAX_JSON_BYTES} UTF-8 bytes",
            )
        }
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw GuestImageManifestException("Guest image manifest is not valid UTF-8", error)
        }
        return parse(json, bytes.size)
    }

    private fun parse(json: String, byteCount: Int): GuestImageManifest {
        if (byteCount > GuestImageManifest.MAX_JSON_BYTES) {
            throw GuestImageManifestException(
                "Guest image manifest JSON must be at most ${GuestImageManifest.MAX_JSON_BYTES} UTF-8 bytes",
            )
        }

        JsonDuplicateFieldGuard.rejectDuplicateRootFields(json)

        val manifest = try {
            strictJson.decodeFromString(GuestImageManifest.serializer(), json)
        } catch (error: SerializationException) {
            throw GuestImageManifestException(
                "Invalid guest image manifest JSON: ${error.message ?: "syntax or field error"}",
                error,
            )
        }

        val errors = manifest.validationErrors()
        if (errors.isNotEmpty()) {
            throw GuestImageManifestException(errors.joinToString("; "))
        }
        return manifest
    }
}

fun loadGuestImageManifest(json: String): GuestImageManifest = GuestImageManifestLoader.load(json)

private object JsonDuplicateFieldGuard {
    private const val MAX_NESTING_DEPTH = 32

    fun rejectDuplicateRootFields(input: String) {
        val cursor = Cursor(input)
        cursor.skipWhitespace()
        cursor.expect('{')
        cursor.skipWhitespace()
        val fields = mutableSetOf<String>()

        if (!cursor.consume('}')) {
            while (true) {
                cursor.skipWhitespace()
                val field = cursor.readString()
                if (!fields.add(field)) {
                    throw GuestImageManifestException("Duplicate guest image manifest field: $field")
                }
                cursor.skipWhitespace()
                cursor.expect(':')
                cursor.skipValue()
                cursor.skipWhitespace()
                if (cursor.consume('}')) break
                cursor.expect(',')
            }
        }

        cursor.skipWhitespace()
        if (!cursor.atEnd()) {
            throw GuestImageManifestException("Guest image manifest JSON must contain one object")
        }
    }

    private class Cursor(private val input: String) {
        private var position = 0

        fun atEnd(): Boolean = position == input.length

        fun skipWhitespace() {
            while (position < input.length && input[position] in " \n\r\t") position++
        }

        fun expect(expected: Char) {
            if (position >= input.length || input[position] != expected) {
                throw GuestImageManifestException("Invalid guest image manifest JSON")
            }
            position++
        }

        fun consume(value: Char): Boolean = if (position < input.length && input[position] == value) {
            position++
            true
        } else {
            false
        }

        fun readString(): String {
            expect('"')
            val result = StringBuilder()
            while (position < input.length) {
                when (val character = input[position++]) {
                    '"' -> return result.toString()
                    '\\' -> readEscapeInto(result)
                    in '\u0000'..'\u001f' -> throw GuestImageManifestException("Invalid control character in JSON field name")
                    else -> result.append(character)
                }
            }
            throw GuestImageManifestException("Unterminated JSON field name")
        }

        fun skipValue(depth: Int = 0) {
            if (depth > MAX_NESTING_DEPTH) {
                throw GuestImageManifestException("Guest image manifest JSON is nested too deeply")
            }
            skipWhitespace()
            if (position >= input.length) throw GuestImageManifestException("Invalid guest image manifest JSON")
            when (input[position]) {
                '"' -> readString()
                '{' -> skipObject(depth + 1)
                '[' -> skipArray(depth + 1)
                't' -> skipToken("true")
                'f' -> skipToken("false")
                'n' -> skipToken("null")
                '-', in '0'..'9' -> skipNumber()
                else -> throw GuestImageManifestException("Invalid guest image manifest JSON")
            }
        }

        private fun skipObject(depth: Int) {
            expect('{')
            skipWhitespace()
            if (consume('}')) return
            while (true) {
                skipWhitespace()
                readString()
                skipWhitespace()
                expect(':')
                skipValue(depth)
                skipWhitespace()
                if (consume('}')) return
                expect(',')
            }
        }

        private fun skipArray(depth: Int) {
            expect('[')
            skipWhitespace()
            if (consume(']')) return
            while (true) {
                skipValue(depth)
                skipWhitespace()
                if (consume(']')) return
                expect(',')
            }
        }

        private fun skipToken(token: String) {
            if (!input.startsWith(token, position)) {
                throw GuestImageManifestException("Invalid guest image manifest JSON")
            }
            position += token.length
        }

        private fun skipNumber() {
            while (position < input.length && input[position] !in " \n\r\t,]}") position++
        }

        private fun readEscapeInto(result: StringBuilder) {
            if (position >= input.length) throw GuestImageManifestException("Invalid JSON escape")
            when (val escape = input[position++]) {
                '"', '\\', '/' -> result.append(escape)
                'b' -> result.append('\b')
                'f' -> result.append('\u000c')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    if (position + 4 > input.length) throw GuestImageManifestException("Invalid JSON unicode escape")
                    val digits = input.substring(position, position + 4)
                    if (!digits.all { it in "0123456789abcdefABCDEF" }) {
                        throw GuestImageManifestException("Invalid JSON unicode escape")
                    }
                    result.append(digits.toInt(16).toChar())
                    position += 4
                }
                else -> throw GuestImageManifestException("Invalid JSON escape")
            }
        }
    }
}
