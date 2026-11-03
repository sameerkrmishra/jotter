package io.github.sameerkrmishra.jotter.app.sync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Makes [directory], which must be Jotter's own, such as `~/.config/jotter`, readable by this user
 * alone: creates it with mode 0700 if it is missing, and tightens it to 0700 if it is there with
 * more, as a folder made by an older version or by hand with the usual 0755 may be.
 *
 * Only ever pass the app's own directory, never a shared parent such as `~/.config` or `$HOME`:
 * other programs keep files there that they may need others to read, so changing its mode is not
 * Jotter's to do. Parents that are missing are created 0700 too, as before.
 *
 * @throws java.io.IOException if it cannot be created or its mode cannot be changed.
 */
public fun privateOwnDirectory(directory: Path) {
    if (!Files.isDirectory(directory)) {
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        return
    }
    if (Files.getPosixFilePermissions(directory) != OWNER_ONLY) {
        Files.setPosixFilePermissions(directory, OWNER_ONLY)
    }
}

private val OWNER_ONLY = PosixFilePermissions.fromString("rwx------")
