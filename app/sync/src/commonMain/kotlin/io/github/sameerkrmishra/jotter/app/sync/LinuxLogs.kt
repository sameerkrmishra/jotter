package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.core.logging.LogLevel
import io.github.sameerkrmishra.jotter.core.logging.Logging
import io.github.sameerkrmishra.jotter.core.logging.RotatingFileSink
import okio.Path
import okio.Path.Companion.toPath

/** Sets the log level of one run on Linux: `debug`, `info`, `warn` or `error`. */
public const val LOG_LEVEL_ENVVAR: String = "JOTTER_LOG_LEVEL"

/**
 * Where the desktop app and the CLI keep their logs (ADR-0021): `$XDG_STATE_HOME/jotter/logs`, or
 * `$HOME/.local/state/jotter/logs`. Only absolute paths from the environment count, as the XDG
 * spec says, and there is no fallback to the JVM's idea of home: without either variable, there is
 * no log, rather than one in a place the user did not choose.
 */
public fun linuxLogDirectory(readEnvvar: (String) -> String?): Path? {
    val state = readEnvvar("XDG_STATE_HOME")?.takeIf { it.startsWith('/') }
    val home = readEnvvar("HOME")?.takeIf { it.startsWith('/') }
    return when {
        state != null -> "$state/jotter/logs".toPath()
        home != null -> "$home/.local/state/jotter/logs".toPath()
        else -> null
    }
}

/**
 * Installs the log of the app [name] (`desktop` or `cli`) in [linuxLogDirectory], at the level
 * [LOG_LEVEL_ENVVAR] asks for, or INFO, with `HOME` written as `~` (ADR-0021). Returns the
 * directory, or null if there is none, and then
 * installs nothing. The file is opened at the first line, so this costs a CLI command nothing.
 */
public fun installLinuxLogging(
    name: String,
    readEnvvar: (String) -> String?,
): Path? {
    val directory = linuxLogDirectory(readEnvvar) ?: return null
    val level = LogLevel.parse(readEnvvar(LOG_LEVEL_ENVVAR)) ?: LogLevel.INFO
    val home = readEnvvar("HOME")?.takeIf { it.startsWith('/') }
    Logging.install(RotatingFileSink(directory, name), level, home = home)
    return directory
}
