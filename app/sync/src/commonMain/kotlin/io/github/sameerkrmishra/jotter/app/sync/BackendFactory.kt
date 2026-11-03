package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.localfolder.LocalFolderBlobStore
import okio.Path.Companion.toPath

/** Opens the [BlobStore] that a configured backend describes. */
public fun interface BackendFactory {
    /**
     * Returns a store for [backend]. Opening touches nothing: a store that cannot be reached says
     * so in its `ping`.
     *
     * @throws IllegalArgumentException if this version cannot open such a backend.
     * @throws SecretStoreException if a secret it needs cannot be read.
     */
    public suspend fun open(backend: Backend): BlobStore
}

/**
 * The backends of this version: local folders. A backend that needs a secret reads it from a
 * [SecretStore] here, when WebDAV and the PC hub arrive (ADR-0016).
 */
public object StandardBackendFactory : BackendFactory {
    override suspend fun open(backend: Backend): BlobStore =
        when (val config = backend.config) {
            is LocalFolderConfig -> LocalFolderBlobStore(config.path.toPath())
            is UnknownConfig -> throw IllegalArgumentException("Cannot open ${config.location}")
        }
}
