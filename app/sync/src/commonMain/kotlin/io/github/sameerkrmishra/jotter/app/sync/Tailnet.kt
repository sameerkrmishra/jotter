package io.github.sameerkrmishra.jotter.app.sync

import io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException
import io.github.sameerkrmishra.jotter.backend.api.BlobInfo
import io.github.sameerkrmishra.jotter.backend.api.BlobStore
import io.github.sameerkrmishra.jotter.backend.api.Reachability
import io.github.sameerkrmishra.jotter.backend.api.UnreachableReason
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPath
import io.github.sameerkrmishra.jotter.backend.api.requireValidBlobPrefix
import io.github.sameerkrmishra.jotter.backend.peer.PeerTransport

/**
 * Whether a hub's pairing token may go to a host now, beyond the hub client's address rule
 * (ADR-0015, ADR-0018). That rule lets a token go to `100.64.0.0/10`, which mobile carriers also
 * use for carrier-grade NAT: on a phone where Tailscale is not running, such an address can be a
 * stranger's, and the token would cross the carrier's network in plain HTTP. An app that can
 * tell, as Android can from its VPN, refuses then: the hub is unreachable, and nothing is sent.
 */
public fun interface TailnetCheck {
    /** Why a token must not go to [host] now, in plain words, or null if it may. */
    public fun refuse(host: String): String?

    /**
     * How the hub client reaches the hub at [host], once [refuse] let it through, or null for its
     * own engine and the system's name lookup. Android gives an engine whose sockets are bound to
     * Tailscale's network, and a lookup on that network, so that a request fails, rather than
     * leaves by another network, if the VPN goes away meanwhile. The hub client owns the engine
     * and closes it.
     */
    public fun transport(host: String): PeerTransport? = null

    /** The checks every app may use. */
    public companion object {
        /** No check beyond the address rule, as on a PC, which has no such carrier. */
        public val NONE: TailnetCheck = TailnetCheck { null }
    }
}

/**
 * The hub at a host that [TailnetCheck] refuses: unreachable with [reason], so that sync and the
 * status screen say why, and nothing at all is sent.
 */
internal class OffTailnet(
    reason: String,
) : BlobStore {
    private val unreachable = Reachability.Unreachable(UnreachableReason.FAILED, reason)

    override suspend fun list(prefix: String): List<BlobInfo> {
        requireValidBlobPrefix(prefix)
        throw BackendUnavailableException(unreachable)
    }

    override suspend fun read(path: String): ByteArray {
        requireValidBlobPath(path)
        throw BackendUnavailableException(unreachable)
    }

    override suspend fun writeNew(
        path: String,
        bytes: ByteArray,
    ) {
        requireValidBlobPath(path)
        throw BackendUnavailableException(unreachable)
    }

    override suspend fun ping(): Reachability = unreachable
}
