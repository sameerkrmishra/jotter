package io.github.sameerkrmishra.jotter.core.storage

/** The environment variable that names the database file. */
public const val DATABASE_ENVVAR: String = "JOTTER_DB"

/**
 * Returns the database file of the Linux apps, which the CLI and the desktop app share: [explicit]
 * if given, then `jotter/jotter.db` in `$XDG_DATA_HOME`, then in `~/.local/share`. The XDG Base
 * Directory specification says to ignore an empty or relative `XDG_DATA_HOME`. An empty [explicit]
 * or `HOME` counts as not given.
 *
 * @param explicit The file the user named, for example with `--db` or [DATABASE_ENVVAR].
 * @param readEnvvar Reads an environment variable.
 * @param userHome The home directory if `HOME` is not set.
 * @return The file, or null if there is no usable location: the home directory is not absolute.
 *   The JVM's `user.home` is `?` for a user without a passwd entry, and a relative home would put
 *   the notes wherever the program happened to start.
 */
public fun linuxDatabasePath(
    explicit: String?,
    readEnvvar: (String) -> String?,
    userHome: String,
): String? {
    if (!explicit.isNullOrEmpty()) return explicit
    val dataHome =
        readEnvvar("XDG_DATA_HOME")?.takeIf { it.startsWith('/') }
            ?: homeDirectory(readEnvvar, userHome)?.let { "${it.trimEnd('/')}/.local/share" }
    return dataHome?.let { "${it.trimEnd('/')}/jotter/jotter.db" }
}

private fun homeDirectory(
    readEnvvar: (String) -> String?,
    userHome: String,
): String? = (readEnvvar("HOME")?.takeUnless(String::isEmpty) ?: userHome).takeIf { it.startsWith('/') }
