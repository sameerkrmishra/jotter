package io.github.sameerkrmishra.jotter.backend.peer

private const val IPV4_BYTES = 4
private const val IPV6_BYTES = 16
private const val LOOPBACK_FIRST = 127
private const val TAILSCALE_FIRST = 100

// 100.64.0.0/10: the top two bits of the second byte are 01.
private const val TAILSCALE_MASK = 0xC0
private const val TAILSCALE_SECOND = 0x40
private const val BYTE_MASK = 0xFF
private const val HEX_GROUP_BYTES = 2
private const val BITS_PER_BYTE = 8
private const val HEX = 16

// Tailscale's IPv6 range, fd7a:115c:a1e0::/48.
private val TAILSCALE_V6_PREFIX =
    byteArrayOf(0xFD.toByte(), 0x7A, 0x11, 0x5C, 0xA1.toByte(), 0xE0.toByte())

/**
 * Whether [address], a resolved IPv4 (4 bytes) or IPv6 (16 bytes) address, may receive a token:
 * loopback (`127.0.0.0/8`, `::1`), Tailscale's IPv4 range (`100.64.0.0/10`) or its IPv6 range
 * (`fd7a:115c:a1e0::/48`). The connection there is plain HTTP, so anywhere else the token could be
 * read on the way (`docs/adr/0015-pc-hub-peer-protocol.md`).
 */
internal fun isTailnetOrLoopback(address: ByteArray): Boolean {
    val octets = address.map { it.toInt() and BYTE_MASK }
    return when (address.size) {
        IPV4_BYTES ->
            octets[0] == LOOPBACK_FIRST ||
                (octets[0] == TAILSCALE_FIRST && octets[1] and TAILSCALE_MASK == TAILSCALE_SECOND)
        IPV6_BYTES ->
            octets.dropLast(1).all { it == 0 } &&
                octets.last() == 1 ||
                address.copyOf(TAILSCALE_V6_PREFIX.size).contentEquals(TAILSCALE_V6_PREFIX)
        else -> false
    }
}

/** [address] as the host part of a URL: dotted IPv4, or bracketed IPv6 without compression. */
internal fun urlHostOf(address: ByteArray): String =
    if (address.size == IPV4_BYTES) {
        address.joinToString(".") { (it.toInt() and BYTE_MASK).toString() }
    } else {
        address
            .toList()
            .chunked(HEX_GROUP_BYTES)
            .joinToString(":", "[", "]") { (high, low) ->
                ((high.toInt() and BYTE_MASK) shl BITS_PER_BYTE or (low.toInt() and BYTE_MASK))
                    .toString(
                        HEX,
                    )
            }
    }
