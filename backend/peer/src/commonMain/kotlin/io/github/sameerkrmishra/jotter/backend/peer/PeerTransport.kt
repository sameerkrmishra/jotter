package io.github.sameerkrmishra.jotter.backend.peer

import io.ktor.client.engine.HttpClientEngine

/**
 * How a [PeerBlobStore] reaches its hub when the default does not do: Ktor's CIO engine and the
 * system's name lookup. An Android app gives one that only uses Tailscale's network (ADR-0018):
 * its [engine]'s sockets are bound to that network, and [lookup] asks that network's DNS, so that
 * neither the token nor a MagicDNS name leaves by the mobile network if the VPN goes away.
 *
 * @property engine Makes the connections. It must not retry or follow redirects itself.
 * @property lookup The address of a host name or address literal, as the 4 or 16 bytes of an IPv4
 *   or IPv6 address, or null if it has none. It is asked before every request. A
 *   [io.github.sameerkrmishra.jotter.backend.api.BackendUnavailableException] it throws is the
 *   request's failure as it is; anything else it throws means that the name has no address.
 */
public class PeerTransport(
    public val engine: HttpClientEngine,
    public val lookup: (host: String) -> ByteArray?,
)
