package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.sameerkrmishra.jotter.app.sync.Backend
import io.github.sameerkrmishra.jotter.app.sync.PeerConfig
import io.github.sameerkrmishra.jotter.app.sync.WebDavConfig

internal const val FOLDER_FIELD = "folder"
internal const val SERVER_URL_FIELD = "server-url"
internal const val SERVER_USER_FIELD = "server-user"
internal const val SERVER_PASSWORD_FIELD = "server-password"
internal const val NEW_PASSWORD_FIELD = "new-password"
internal const val ADD_SERVER = "Add server"
internal const val NEW_PASSWORD = "Enter new password"
internal const val HUB_ADDRESS_FIELD = "hub-address"
internal const val HUB_TOKEN_FIELD = "hub-token"
internal const val ADD_HUB = "Add PC hub"

/** Where a hub's token may go, for the add form. */
internal const val HUB_CLIENT_RULE: String =
    "Enter the PC's Tailscale address or name, and the token it showed when you paired this " +
        "device. Jotter sends the token only to a Tailscale address or to this machine, never " +
        "to another network."
internal const val BACKENDS_TITLE = "Sync"
internal const val NO_BACKENDS =
    "This device syncs with no backend yet. Add a folder, such as a USB stick or a folder " +
        "that another program keeps in step with other computers."

/**
 * The keyboard of a field that holds a password or a token: a password field, so that a phone's
 * keyboard neither learns, suggests nor syncs what is typed, and no autocorrection.
 */
internal val SECRET_KEYBOARD =
    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)

/** [NO_BACKENDS] on a device that cannot add a folder. */
internal const val NO_BACKENDS_NOR_FOLDERS =
    "This device syncs with no backend yet. Add a WebDAV server, or the hub on your PC."

/** Below this width, as on a phone, a form's fields go one under another. */
internal val NARROW_FORM = 600.dp

/**
 * A form's fields and button: side by side, each field as wide as its weight says, or one under
 * another at full width where the screen is narrower than [NARROW_FORM].
 *
 * @param content Gets the modifier of a field of a given weight.
 */
@Composable
internal fun Form(content: @Composable (field: (weight: Float) -> Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < NARROW_FORM) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                content { Modifier.fillMaxWidth() }
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content { weight -> Modifier.weight(weight) }
            }
        }
    }
}

/** The test tag of the card of the backend [id]. */
internal fun backendTag(id: String) = "backend-$id"

/**
 * The backend screen: see [BackendsViewModel]. [onDebugLog] opens the debug log, if the app has
 * one: this is where the user looks when sync fails. [onExport] opens "Export database", if the
 * app has it: the screen is where a phone keeps what is not about notes.
 */
@Composable
internal fun BackendsScreen(
    backends: BackendsViewModel,
    onClose: () -> Unit,
    onDebugLog: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("Back") }
            Text(BACKENDS_TITLE, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            onDebugLog?.let { TextButton(onClick = it) { Text(DEBUG_LOG_TITLE) } }
            Button(onClick = backends::syncNow, enabled = !backends.syncing) {
                Text(if (backends.syncing) "Syncing…" else "Sync now")
            }
        }
        backends.problem?.let { Message(it) {} }
        backends.message?.let {
            Message(it) { TextButton(onClick = backends::dismissMessage) { Text("Dismiss") } }
        }
        BackendList(backends)
        backends.foldersUnavailable?.let { Text(it) } ?: AddFolder(backends)
        AddServer(backends)
        AddHub(backends)
        backends.hub?.let { HubSection(it) }
        onExport?.let { ExportDatabaseSection(it) }
    }
    backends.settingUp?.let { path ->
        AlertDialog(
            onDismissRequest = backends::cancelSetUp,
            title = { Text(SET_UP_TITLE) },
            text = { Text(setUpExplanation(path)) },
            confirmButton = {
                TextButton(
                    onClick = backends::confirmSetUp,
                ) { Text("Set up and add") }
            },
            dismissButton = { TextButton(onClick = backends::cancelSetUp) { Text("Cancel") } },
        )
    }
    BackendsDialogs(backends)
    RemoveDialogs(backends)
}

@Composable
private fun BackendList(backends: BackendsViewModel) {
    if (backends.backends.isEmpty()) {
        Text(if (backends.foldersUnavailable == null) NO_BACKENDS else NO_BACKENDS_NOR_FOLDERS)
    }
    backends.backends.forEach { backend ->
        key(backend.id) {
            BackendCard(
                backend,
                backends.statusOf(backend),
                onRemove = { backends.askToRemove(backend) },
                onPassword =
                    { backends.askForPassword(backend) }.takeIf {
                        backend.config is WebDavConfig || backend.config is PeerConfig
                    },
            )
        }
    }
}

/** Confirming a removal, and removing anyway when the secret cannot be deleted. */
@Composable
private fun RemoveDialogs(backends: BackendsViewModel) {
    backends.removing?.let { backend ->
        AlertDialog(
            onDismissRequest = backends::cancelRemove,
            title = { Text(REMOVE_TITLE) },
            text = { Text(removeExplanation(backend)) },
            confirmButton = { TextButton(onClick = backends::confirmRemove) { Text("Remove") } },
            dismissButton = { TextButton(onClick = backends::cancelRemove) { Text("Cancel") } },
        )
    }
    backends.removingAnyway?.let { stuck ->
        AlertDialog(
            onDismissRequest = backends::cancelRemoveAnyway,
            title = { Text(REMOVE_ANYWAY_TITLE) },
            text = {
                Text(
                    "Jotter cannot delete the password of ${stuck.backend.name} now: " +
                        "${stuck.reason} You can remove the backend anyway. ${stuck.leftBehind}",
                )
            },
            confirmButton = {
                TextButton(onClick = backends::confirmRemoveAnyway) { Text("Remove anyway") }
            },
            dismissButton = {
                TextButton(onClick = backends::cancelRemoveAnyway) { Text("Keep it") }
            },
        )
    }
}

/** The dialogs of adding a server and replacing its password. */
@Composable
private fun BackendsDialogs(backends: BackendsViewModel) {
    backends.settingUpServer?.let { url ->
        AlertDialog(
            onDismissRequest = backends::cancelSetUpServer,
            title = { Text(SET_UP_SERVER_TITLE) },
            text = { Text(setUpServerExplanation(url)) },
            confirmButton = {
                TextButton(onClick = backends::confirmSetUpServer) { Text("Set up and add") }
            },
            dismissButton = {
                TextButton(onClick = backends::cancelSetUpServer) { Text("Cancel") }
            },
        )
    }
    backends.noKeyring?.let { reason ->
        AlertDialog(
            onDismissRequest = backends::cancelPasswordFile,
            title = { Text(NO_KEYRING_TITLE) },
            text = { Text(noKeyringExplanation(reason, backends.secretsFile)) },
            confirmButton = {
                TextButton(onClick = backends::usePasswordFile) { Text("Keep it in the file") }
            },
            dismissButton = {
                TextButton(onClick = backends::cancelPasswordFile) { Text("Cancel") }
            },
        )
    }
    backends.changingPassword?.let { PasswordDialog(backends, it) }
}

@Composable
private fun PasswordDialog(
    backends: BackendsViewModel,
    backend: Backend,
) {
    val hub = backend.config is PeerConfig
    AlertDialog(
        onDismissRequest = backends::cancelPassword,
        title = { Text(if (hub) "Enter a new pairing token" else PASSWORD_TITLE) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                Text(
                    if (hub) {
                        "Pair this device again on the PC, and enter the token it shows. " +
                            "Jotter checks it with the hub before it keeps it."
                    } else {
                        "Make a new app password for Jotter in the settings of " +
                            "${backend.config.location}'s account, and enter it here. " +
                            "Jotter checks it with the server before it keeps it."
                    },
                )
                PasswordField(
                    backends.davPassword,
                    { backends.editServer(password = it) },
                    Modifier.fillMaxWidth().testTag(NEW_PASSWORD_FIELD),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = backends::confirmPassword,
                enabled = !backends.busy && backends.davPassword.isNotEmpty(),
            ) { Text("Check and keep") }
        },
        dismissButton = { TextButton(onClick = backends::cancelPassword) { Text("Cancel") } },
    )
}

@Composable
private fun AddServer(backends: BackendsViewModel) {
    Form { field ->
        OutlinedTextField(
            value = backends.davUrl,
            onValueChange = { backends.editServer(url = it) },
            modifier = field(2f).testTag(SERVER_URL_FIELD),
            label = { Text("WebDAV folder URL, https://…") },
            singleLine = true,
        )
        OutlinedTextField(
            value = backends.davUser,
            onValueChange = { backends.editServer(user = it) },
            modifier = field(1f).testTag(SERVER_USER_FIELD),
            label = { Text("User name") },
            singleLine = true,
        )
        PasswordField(
            backends.davPassword,
            { backends.editServer(password = it) },
            field(1f).testTag(SERVER_PASSWORD_FIELD),
        )
        Button(
            onClick = backends::addServer,
            enabled =
                !backends.busy &&
                    backends.changingPassword == null &&
                    backends.davUrl.isNotBlank() &&
                    backends.davUser.isNotBlank() &&
                    backends.davPassword.isNotEmpty(),
        ) { Text(ADD_SERVER) }
    }
}

/** A field whose text is never shown. */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text("App password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = SECRET_KEYBOARD,
    )
}

@Composable
private fun AddFolder(backends: BackendsViewModel) {
    Form { field ->
        OutlinedTextField(
            value = backends.folder,
            onValueChange = backends::editFolder,
            modifier = field(1f).testTag(FOLDER_FIELD),
            label = { Text("Folder, such as /run/media/you/STICK") },
            singleLine = true,
        )
        Button(
            onClick = backends::addFolder,
            enabled = !backends.busy && backends.folder.isNotBlank(),
        ) { Text("Add folder") }
    }
}

@Composable
private fun BackendCard(
    backend: Backend,
    lines: BackendLines,
    onRemove: () -> Unit,
    onPassword: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth().testTag(backendTag(backend.id))) {
        Column(Modifier.padding(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Text(backend.name, style = MaterialTheme.typography.titleMedium)
            Text(backend.config.location)
            lines.secret?.let { Text(it) }
            Text(lines.reachability)
            Text(lines.lastSuccess)
            lines.lastProblem?.let { Text(it) }
            lines.issues.forEach { Text("• $it") }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                onPassword?.let {
                    if (lines.passwordRefused) {
                        Button(onClick = it) { Text(NEW_PASSWORD) }
                    } else {
                        TextButton(onClick = it) { Text(NEW_PASSWORD) }
                    }
                }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}
