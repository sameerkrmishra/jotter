package io.github.sameerkrmishra.jotter.backend.webdav

/**
 * The user name and password for a WebDAV server, sent with HTTP Basic authentication. Use an app
 * password where the provider offers one (mailbox.org, Nextcloud), so that the account password
 * never sits on a device and access can be revoked for Jotter alone.
 *
 * [toString] leaves the password out, so that it cannot reach a log by accident.
 *
 * @property username The user name, often the e-mail address.
 * @property password The password or app password.
 */
public class WebDavCredentials(
    public val username: String,
    public val password: String,
) {
    init {
        // RFC 7617: the user-id cannot contain a colon, which separates it from the password.
        require(':' !in username) { "A user name for Basic authentication cannot contain ':'" }
    }

    override fun toString(): String = "WebDavCredentials(username=$username, password=***)"
}
