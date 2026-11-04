package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.storage.StorageProblem
import io.github.sameerkrmishra.jotter.core.storage.storageProblemOf

// What the apps and the CLI say when the notes database fails, in plain words: what happened and
// what to do, never an exception's text. The exception goes to the log instead. In none of these
// cases did Jotter change or replace the file.

/** Where a report of a failure that may be a bug can say to look. */
private const val REPORT = "If it keeps happening, report it and attach the debug log."

/**
 * Why the notes at [path] could not be opened, and what to do, from [failure], the exception that
 * opening them threw.
 */
public fun describeOpenFailure(
    failure: Throwable,
    path: String,
): String =
    when (storageProblemOf(failure)) {
        StorageProblem.LOCKED ->
            "Your notes at $path are in use by another Jotter program, such as the command " +
                "line. Wait until it has finished, then try again."
        StorageProblem.DISK_FULL ->
            "The disk that holds your notes at $path is full. Free some space, then try again. " +
                "Your notes have not been changed."
        StorageProblem.DAMAGED ->
            "Your notes file $path is damaged, so Jotter cannot read it. Jotter has not changed " +
                "or replaced it. Keep a copy of the file before anything else. Then restore it " +
                "from a backup, or move it aside and let this device sync your notes again from " +
                "another device."
        StorageProblem.READ_ONLY ->
            "Jotter may read your notes at $path but not change them. Check that you are " +
                "allowed to change the file and its folder."
        StorageProblem.NO_ACCESS ->
            "Jotter cannot open $path. Check that its folder exists and that you are allowed to " +
                "read and change the file."
        StorageProblem.DISK_ERROR ->
            "The disk could not be read or written at $path. If it is a removable drive, check " +
                "that it is plugged in, then try again."
        StorageProblem.NEWER_VERSION ->
            "Your notes at $path were saved by a newer version of Jotter. Update Jotter to open " +
                "them. The file has not been changed."
        StorageProblem.NOT_JOTTER ->
            "$path is not a Jotter notes file, so Jotter left it alone. Check that this is the " +
                "file you meant."
        StorageProblem.UPGRADE_FAILED ->
            "Jotter could not update your notes at $path for this version, so it left them as " +
                "they were: nothing was lost. Please report this and attach the debug log."
        StorageProblem.OTHER ->
            "Jotter could not open your notes at $path. The file has not been changed. Try " +
                "again. $REPORT"
    }

/**
 * Why reading or writing the open notes failed, in a sentence or two, from [failure]. The screen
 * says before it what could not be done, such as "Your text is kept here but could not be saved."
 */
public fun describeStorageFailure(failure: Throwable): String =
    when (storageProblemOf(failure)) {
        StorageProblem.LOCKED -> "Another Jotter program is using the notes. Try again."
        StorageProblem.DISK_FULL -> "The disk is full. Free some space, then try again."
        StorageProblem.DAMAGED ->
            "The notes file is damaged. Keep a copy of it before anything else, and see the " +
                "user guide on restoring it."
        StorageProblem.READ_ONLY, StorageProblem.NO_ACCESS ->
            "Jotter is not allowed to change the notes file. Check its permissions."
        StorageProblem.DISK_ERROR ->
            "The disk could not be written. If it is a removable drive, check that it is " +
                "plugged in."
        StorageProblem.NEWER_VERSION, StorageProblem.NOT_JOTTER, StorageProblem.UPGRADE_FAILED,
        StorageProblem.OTHER,
        -> "Something unexpected went wrong. $REPORT"
    }
