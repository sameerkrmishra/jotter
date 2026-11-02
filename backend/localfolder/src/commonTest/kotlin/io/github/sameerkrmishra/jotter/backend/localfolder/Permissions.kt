package io.github.sameerkrmishra.jotter.backend.localfolder

import okio.Path

/** Sets the mode of [directory] to 700, or to 000 to cause real `EACCES` errors. */
internal expect fun setSearchable(
    directory: Path,
    searchable: Boolean,
)
