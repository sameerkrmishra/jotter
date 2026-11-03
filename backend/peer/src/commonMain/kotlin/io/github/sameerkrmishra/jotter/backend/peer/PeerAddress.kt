package io.github.sameerkrmishra.jotter.backend.peer

private const val MAX_PORT = 65535
private const val MAX_PORT_DIGITS = 5

/**
 * Where a hub listens: as the user typed it, usually the PC's Tailscale address, such as
 * `100.101.102.103` or a MagicDNS name. There is no discovery.
 *
 * @property host An IPv4 address or a host name. IPv6 literals are not supported.
 * @property port The TCP port, 1 to 65535.
 * @throws IllegalArgumentException if [host] is empty or not a plain host name or IPv4 address, or
 *   [port] is out of range.
 */
public data class PeerAddress(
    val host: String,
    val port: Int = PeerProtocol.DEFAULT_PORT,
) {
    init {
        require(host.isNotEmpty() && host.all(::isHostCharacter)) {
            "\"$host\" is not a host name or IPv4 address"
        }
        require(port in 1..MAX_PORT) { "$port is not a TCP port" }
    }

    override fun toString(): String = "$host:$port"

    /** Parsing of what a user typed. */
    public companion object {
        /**
         * Reads `host` or `host:port`. The port is [PeerProtocol.DEFAULT_PORT] if none is given.
         *
         * @throws IllegalArgumentException if [text] is neither.
         */
        public fun parse(text: String): PeerAddress {
            val trimmed = text.trim()
            val colon = trimmed.lastIndexOf(':')
            if (colon < 0) return PeerAddress(trimmed)
            val port = trimmed.substring(colon + 1)
            require(
                port.isNotEmpty() && port.all { it in '0'..'9' } && port.length <= MAX_PORT_DIGITS,
            ) {
                "\"$port\" is not a TCP port"
            }
            return PeerAddress(trimmed.substring(0, colon), port.toInt())
        }

        // Letters, digits, '-' and '.': what a DNS name or an IPv4 address uses. Anything else
        // could change the URL that the address is put into.
        private fun isHostCharacter(char: Char): Boolean =
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '-' || char == '.'
    }
}
