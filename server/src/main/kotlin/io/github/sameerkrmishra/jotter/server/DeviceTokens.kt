package io.github.sameerkrmishra.jotter.server

import io.github.sameerkrmishra.jotter.server.dto.DeviceDto
import io.github.sameerkrmishra.jotter.server.dto.TokenFileDto
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.encodeUtf8
import okio.FileNotFoundException
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import java.nio.file.Files
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

private const val FORMAT_VERSION = 1
private const val MAX_NAME_LENGTH = 64
private const val BEARER = "Bearer "

/**
 * A device that may use the hub.
 *
 * @property id The public part of its token, which identifies it in [PeerHub.unpair].
 * @property name What the user called it when pairing.
 */
public data class PairedDevice(
    val id: String,
    val name: String,
)

/**
 * The result of [PeerHub.pair]: a new device and its token. The hub keeps only a hash, so this is
 * the one time the token can be shown. [toString] leaves it out, so that it cannot reach a log.
 *
 * @property device The paired device.
 * @property token What the device passes to `PeerBlobStore`, together with the hub's address.
 */
public class Pairing internal constructor(
    public val device: PairedDevice,
    public val token: String,
) {
    override fun toString(): String = "Pairing(device=$device)"
}

/**
 * The paired devices of a hub, kept in [file]. A token is `<id>.<secret>`: 32 hex digits naming the
 * device, then 32 random bytes in unpadded base64url. The file holds the id, the name and the
 * SHA-256 of the secret. Checking a token compares hashes in constant time, against every device,
 * so the time it takes says nothing about how close a guess was.
 */
internal class DeviceTokens(
    private val file: Path,
    private val fileSystem: FileSystem,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val devices = atomic<List<DeviceDto>?>(null)

    /** Every paired device. */
    suspend fun list(): List<PairedDevice> = loaded().map { PairedDevice(it.id, it.name) }

    /**
     * Pairs a new device called [name].
     *
     * @throws IllegalArgumentException if [name] is empty, longer than 64 characters or has a
     *   control character.
     * @throws IOException if the file cannot be written; nothing is paired then.
     */
    suspend fun pair(name: String): Pairing {
        val valid = name.isNotBlank() && name.length <= MAX_NAME_LENGTH
        require(valid && name.none { it.isISOControl() }) {
            "A device name has 1 to $MAX_NAME_LENGTH characters and no control characters"
        }
        val id = Uuid.random().toHexString()
        val secret = base64Url.encode(Uuid.random().toByteArray() + Uuid.random().toByteArray())
        update { it + DeviceDto(id, name, hashOf(secret).hex()) }
        return Pairing(PairedDevice(id, name), "$id.$secret")
    }

    /** Forgets the device [id]. Returns whether it was paired. Its token stops working at once. */
    suspend fun unpair(id: String): Boolean {
        var found = false
        update { current ->
            found = current.any { it.id == id }
            current.filterNot { it.id == id }
        }
        return found
    }

    /**
     * Reads the file, so that a damaged one fails here rather than in the middle of serving.
     *
     * @throws IOException if it cannot be read or is not a file of paired devices.
     */
    suspend fun load() {
        loaded()
    }

    /**
     * Returns the device whose token is in [authorization], an HTTP `Authorization` header, or
     * null. Only after [load].
     */
    fun authenticate(authorization: String?): PairedDevice? {
        val token = authorization?.takeIf { it.startsWith(BEARER) }?.substring(BEARER.length)
        val id = token?.substringBefore('.', missingDelimiterValue = "")
        val hash =
            hashOf(
                token?.substringAfter('.', missingDelimiterValue = "").orEmpty(),
            ).toByteArray()
        var match: DeviceDto? = null
        // Every device is compared, with no early exit, whatever matches.
        for (device in devices.value.orEmpty()) {
            val sameHash = constantTimeEquals(hash, device.secretSha256.decodeHex().toByteArray())
            if (sameHash and (device.id == id)) match = device
        }
        return match?.let { PairedDevice(it.id, it.name) }
    }

    private suspend fun loaded(): List<DeviceDto> =
        devices.value ?: mutex.withLock {
            devices.value ?: withContext(dispatcher) { read() }.also { devices.value = it }
        }

    private suspend fun update(change: (List<DeviceDto>) -> List<DeviceDto>) {
        mutex.withLock {
            val current = devices.value ?: withContext(dispatcher) { read() }
            val next = change(current)
            withContext(dispatcher) { write(next) }
            devices.value = next
        }
    }

    /**
     * The paired devices in the file, none if there is no file. Any other failure throws, so that
     * a file that cannot be read is never replaced by pairing a device.
     */
    private fun read(): List<DeviceDto> {
        try {
            // Not read's error: on the JVM a file that may not be read is "not found" there too.
            fileSystem.metadata(file)
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        return decode(fileSystem.read(file) { readUtf8() })?.devices
            ?: throw IOException("$file is not a file of paired devices of version $FORMAT_VERSION")
    }

    private fun decode(text: String): TokenFileDto? =
        try {
            json.decodeFromString<TokenFileDto>(text).takeIf { parsed ->
                parsed.version == FORMAT_VERSION &&
                    parsed.devices.all { it.secretSha256.matches(SHA256_HEX) }
            }
        } catch (_: IllegalArgumentException) {
            null // A SerializationException is one.
        }

    /** Replaces the file in one step, so that a crash leaves the old list or the new one. */
    private fun write(next: List<DeviceDto>) {
        val bytes = json.encodeToString(TokenFileDto(FORMAT_VERSION, next)).encodeToByteArray()
        val directory = file.parent ?: ".".toPath()
        val temporary = directory / "${file.name}.${Uuid.random().toHexString()}.tmp"
        try {
            createPrivately(directory, temporary)
            fileSystem.openReadWrite(temporary, mustExist = true).use { handle ->
                handle.write(0, bytes, 0, bytes.size)
                handle.flush()
            }
            fileSystem.atomicMove(temporary, file)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    /**
     * Creates [temporary] empty, readable by this user alone from the call that creates it, and
     * [directory] with mode 0700 if it is missing. Another file system, as in tests, gets plain
     * files.
     */
    private fun createPrivately(
        directory: Path,
        temporary: Path,
    ) {
        if (fileSystem !== FileSystem.SYSTEM) {
            fileSystem.createDirectories(directory)
            fileSystem.openReadWrite(temporary, mustCreate = true).close()
            return
        }
        val nioDirectory = directory.toNioPath()
        if (!Files.isDirectory(nioDirectory)) {
            Files.createDirectories(nioDirectory, attributeOf("rwx------"))
        }
        Files.createFile(temporary.toNioPath(), attributeOf("rw-------"))
    }

    private companion object {
        val json = Json { prettyPrint = true }

        fun attributeOf(permissions: String): FileAttribute<Set<PosixFilePermission>> =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions))

        val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val SHA256_HEX = Regex("[0-9a-f]{64}")

        fun hashOf(secret: String) = secret.encodeUtf8().sha256()

        fun constantTimeEquals(
            a: ByteArray,
            b: ByteArray,
        ): Boolean {
            if (a.size != b.size) return false
            var difference = 0
            for (index in a.indices) {
                difference = difference or (a[index].toInt() xor b[index].toInt())
            }
            return difference == 0
        }
    }
}
