package io.github.sameerkrmishra.jotter.app.sync

import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * Secrets in plain text in `secrets.json` in [directory], for a user who chose that because no
 * keyring answers, such as over SSH (ADR-0016). Anything that can read the user's files can read
 * them.
 *
 * The directory is created with mode 0700. Every version of the file is written to a new file in
 * it that is created with mode 0600, in the same call that creates it, and then renamed into
 * place: it is never readable by others, not even for a moment. A file that group or others may
 * read, that is not this user's, or that is a link, is refused. A lock file keeps two processes
 * from writing at once.
 *
 * @param directory Such as `~/.config/jotter`.
 * @param io Where the files are read and written.
 */
public class SecretFileStore(
    directory: Path,
    private val io: CoroutineContext,
) : SecretStore {
    private val directory = directory.toAbsolutePath().normalize()

    /** The file. */
    public val file: Path = this.directory.resolve(FILE_NAME)

    override suspend fun read(key: SecretKey): Secret? =
        runInterruptible(io) { load()[name(key)]?.let(::Secret) }

    override suspend fun write(
        key: SecretKey,
        secret: Secret,
    ): Unit = change { it[name(key)] = secret.reveal() }

    override suspend fun delete(key: SecretKey): Unit = change { it.remove(name(key)) }

    private suspend fun change(edit: (MutableMap<String, String>) -> Unit) {
        runInterruptible(io) {
            createDirectory()
            try {
                FileChannel
                    .open(
                        directory.resolve(LOCK_NAME),
                        setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                        PRIVATE_FILE,
                    ).use { lock ->
                        lock.lock().use {
                            val secrets = load().toMutableMap()
                            edit(secrets)
                            save(secrets)
                        }
                    }
            } catch (e: IOException) {
                throw SecretStoreException("Cannot write $file: ${e.message}", e)
            }
        }
    }

    private fun createDirectory() {
        try {
            if (!Files.isDirectory(directory)) {
                Files.createDirectories(directory, PRIVATE_DIRECTORY)
            }
        } catch (e: IOException) {
            throw SecretStoreException("Cannot create the folder $directory: ${e.message}", e)
        }
    }

    /** The secrets in the file, after checking that only this user can read it. */
    private fun load(): Map<String, String> {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
        check()
        val text =
            try {
                Files.readString(file)
            } catch (e: IOException) {
                throw SecretStoreException("Cannot read $file: ${e.message}", e)
            }
        return parse(text) ?: throw SecretStoreException(damaged())
    }

    /** The secrets in [text], or null if it is damaged. */
    @Suppress("SwallowedException") // A parser's message may quote the text, which holds secrets.
    private fun parse(text: String): Map<String, String>? =
        try {
            json.decodeFromString(serializer, text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun check() {
        val problem =
            try {
                when {
                    Files.isSymbolicLink(file) -> "it is a link"
                    Files.getAttribute(file, UID, LinkOption.NOFOLLOW_LINKS) !=
                        Files.getAttribute(SELF, UID) -> "it belongs to another user"
                    Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).any {
                        it !in OWNER_ONLY
                    } -> "other users may read it"
                    else -> null
                }
            } catch (e: IOException) {
                "its permissions cannot be read (${e.message})"
            }
        if (problem != null) {
            throw SecretStoreException(
                "Jotter does not use $file, because $problem. Delete it and enter the passwords " +
                    "again, or make it yours alone with chmod 600.",
            )
        }
    }

    private fun save(secrets: Map<String, String>) {
        val temporary = directory.resolve("$FILE_NAME.${Random.nextLong().toULong()}.tmp")
        try {
            // O_CREAT | O_EXCL with mode 0600, in one call: never readable by others.
            Files.createFile(temporary, PRIVATE_FILE)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = json.encodeToString(serializer, secrets).encodeToByteArray()
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun damaged() =
        "$file is damaged, so the passwords in it cannot be read. Delete it and enter them again."

    private companion object {
        const val FILE_NAME = "secrets.json"
        const val LOCK_NAME = "secrets.lock"

        /** This process, whose owner is this user. */
        val SELF: Path = Path.of("/proc/self")
        const val UID = "unix:uid"

        val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val PRIVATE_FILE: FileAttribute<Set<PosixFilePermission>> =
            PosixFilePermissions.asFileAttribute(OWNER_ONLY)
        val PRIVATE_DIRECTORY: FileAttribute<Set<PosixFilePermission>> =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))

        val json = Json { prettyPrint = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())

        fun name(key: SecretKey) = "${key.backendId}/${key.kind.name}"
    }
}
