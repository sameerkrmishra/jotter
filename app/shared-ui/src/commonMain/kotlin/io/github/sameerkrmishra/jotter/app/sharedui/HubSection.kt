package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal fun AddHub(backends: BackendsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
        Form { field ->
            OutlinedTextField(
                value = backends.hubAddress,
                onValueChange = { backends.editHub(address = it) },
                modifier = field(1f).testTag(HUB_ADDRESS_FIELD),
                label = { Text("PC hub address, such as 100.101.102.103") },
                singleLine = true,
            )
            OutlinedTextField(
                value = backends.hubToken,
                onValueChange = { backends.editHub(token = it) },
                modifier = field(1f).testTag(HUB_TOKEN_FIELD),
                label = { Text("Pairing token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = SECRET_KEYBOARD,
            )
            Button(
                onClick = backends::addHub,
                enabled =
                    !backends.busy &&
                        backends.hubAddress.isNotBlank() &&
                        backends.hubToken.isNotBlank(),
            ) { Text(ADD_HUB) }
        }
        Text(HUB_CLIENT_RULE, style = MaterialTheme.typography.bodySmall)
    }
}

/** Running the hub on this PC: on or off, its folder and address, pairing and unpairing. */
@Composable
internal fun HubSection(hub: HubViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(spacing), verticalArrangement = Arrangement.spacedBy(spacing)) {
            Text(HUB_TITLE, style = MaterialTheme.typography.titleMedium)
            Text(
                when (val state = hub.state) {
                    HubState.Off -> "The hub is off."
                    is HubState.Running -> "The hub is on, at ${state.address}."
                    is HubState.Failed -> "The hub is not running: ${state.message}"
                },
            )
            hub.message?.let {
                Message(it) { TextButton(onClick = hub::dismissMessage) { Text("Dismiss") } }
            }
            HubSettingsRow(hub)
            Text(HUB_ADDRESSES, style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = hub.deviceName,
                    onValueChange = { hub.edit(deviceName = it) },
                    modifier = Modifier.weight(1f).testTag(HUB_DEVICE_FIELD),
                    label = { Text("Device to pair, such as Phone") },
                    singleLine = true,
                )
                Button(
                    onClick = hub::pair,
                    enabled = !hub.busy && hub.deviceName.isNotBlank(),
                ) { Text("Pair device") }
            }
            hub.devices.forEach { device ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Paired: ${device.name}", Modifier.weight(1f))
                    TextButton(onClick = { hub.unpair(device) }) { Text("Unpair ${device.name}") }
                }
            }
        }
    }
    hub.pairing?.let { pairing ->
        AlertDialog(
            onDismissRequest = hub::closePairing,
            title = { Text(PAIRED_TITLE) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                    Text(pairedExplanation(pairing.device.name))
                    SelectionContainer { Text(pairing.token) }
                }
            },
            confirmButton = { TextButton(onClick = hub::closePairing) { Text("Done") } },
        )
    }
}

@Composable
private fun HubSettingsRow(hub: HubViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = hub.folder,
            onValueChange = { hub.edit(folder = it) },
            modifier = Modifier.weight(2f).testTag(HUB_FOLDER_FIELD),
            label = { Text("Hub folder") },
            singleLine = true,
        )
        OutlinedTextField(
            value = hub.host,
            onValueChange = { hub.edit(host = it) },
            modifier = Modifier.weight(1f).testTag(HUB_HOST_FIELD),
            label = { Text("Listen on") },
            singleLine = true,
        )
        OutlinedTextField(
            value = hub.port,
            onValueChange = { hub.edit(port = it) },
            modifier = Modifier.weight(1f).testTag(HUB_PORT_FIELD),
            label = { Text("Port") },
            singleLine = true,
        )
        if (hub.state is HubState.Running) {
            Button(onClick = hub::turnOff, enabled = !hub.busy) { Text("Turn hub off") }
        } else {
            Button(onClick = hub::turnOn, enabled = !hub.busy) { Text("Turn hub on") }
        }
    }
}
