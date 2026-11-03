package io.github.sameerkrmishra.jotter.app.sharedui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal const val HUB_TITLE = "This PC as a hub"
internal const val HUB_FOLDER_FIELD = "hub-folder"
internal const val HUB_HOST_FIELD = "hub-host"
internal const val HUB_PORT_FIELD = "hub-port"
internal const val HUB_DEVICE_FIELD = "hub-device"
internal const val PAIRED_TITLE = "Enter this token on the device"

/** What the hub may listen on, and why, in plain words. */
internal const val HUB_ADDRESSES: String =
    "The hub listens only on this PC (127.0.0.1) or on its Tailscale address, such as " +
        "100.101.102.103. Any other address is refused: the hub speaks plain HTTP, which only " +
        "Tailscale's encrypted tunnel protects. Phones and other computers reach it over Tailscale."

/** What the token is, shown with it once. */
internal fun pairedExplanation(device: String): String =
    "On $device, add this PC's hub as a backend with its address and this token. It is shown " +
        "only now: the hub keeps only a hash of it. Unpair $device here to stop it at once."

/**
 * The hub section of the backend screen: turn the hub on or off, choose its folder and address,
 * pair a device and show its token once, and unpair devices.
 */
@Stable
@Suppress("TooManyFunctions") // One small function per thing the user can do.
internal class HubViewModel(
    private val hub: HubControls,
    parent: CoroutineScope,
) {
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))

    /** What the hub is doing. */
    var state by mutableStateOf(hub.state.value)
        private set

    /** The folder field. */
    var folder by mutableStateOf(hub.settings.folder)
        private set

    /** The address field. */
    var host by mutableStateOf(hub.settings.host)
        private set

    /** The port field. */
    var port by mutableStateOf(hub.settings.port.toString())
        private set

    /** The name of the device to pair. */
    var deviceName by mutableStateOf("")
        private set

    /** The paired devices. */
    var devices by mutableStateOf(emptyList<HubDevice>())
        private set

    /** A new pairing whose token shows until the user closes it. */
    var pairing by mutableStateOf<HubPairing?>(null)
        private set

    /** A notice for the user. */
    var message by mutableStateOf<String?>(null)
        private set

    /** Whether an operation runs. */
    var busy by mutableStateOf(false)
        private set

    init {
        scope.launch { hub.state.collect { state = it } }
        run { devices = hub.devices() }
    }

    /** The user typed in a field. */
    fun edit(
        folder: String = this.folder,
        host: String = this.host,
        port: String = this.port,
        deviceName: String = this.deviceName,
    ) {
        this.folder = folder
        this.host = host
        this.port = port
        this.deviceName = deviceName
    }

    /** Sets the folder up, starts the hub and keeps it on. */
    fun turnOn() {
        val number = port.trim().toIntOrNull()
        if (number == null) {
            message = "The port must be a number, such as ${hub.settings.port}."
            return
        }
        run {
            message = hub.turnOn(HubSettings(folder.trim(), host.trim(), number))
        }
    }

    /** Stops the hub. */
    fun turnOff() {
        run { hub.turnOff() }
    }

    /** Pairs the named device and shows its token once. */
    fun pair() {
        run {
            pairing = hub.pair(deviceName)
            deviceName = ""
            devices = hub.devices()
        }
    }

    /** The user has the token: it is not shown again. */
    fun closePairing() {
        pairing = null
    }

    /** Unpairs [device]. */
    fun unpair(device: HubDevice) {
        run {
            hub.unpair(device.id)
            devices = hub.devices()
            message = "Unpaired ${device.name}. Its token no longer works."
        }
    }

    /** Removes the notice. */
    fun dismissMessage() {
        message = null
    }

    @Suppress("TooGenericExceptionCaught") // Whatever fails, the screen says so and stays.
    private fun run(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = e.message ?: "That did not work."
            } finally {
                busy = false
            }
        }
    }

    /** Stops what this view model runs. */
    fun close() {
        scope.cancel()
    }
}
