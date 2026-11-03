package io.github.sameerkrmishra.jotter.app.sharedui

import kotlinx.coroutines.flow.StateFlow

/**
 * Running the hub on this PC (PLAN.md §6.4, ADR-0015), for the backend screen. The desktop app
 * gives one, built on the `server` module; an app without it shows no hub section.
 */
public interface HubControls {
    /** What the hub is doing now. */
    public val state: StateFlow<HubState>

    /** The settings it last ran with, or the defaults. */
    public val settings: HubSettings

    /**
     * Sets [settings]'s folder up as the hub's store, adds it as a local folder backend so that
     * this PC syncs with it, starts the hub and keeps it on across restarts. Returns why it could
     * not, in plain words, or null.
     */
    public suspend fun turnOn(settings: HubSettings): String?

    /** Stops the hub and keeps it off. Paired devices stay paired. */
    public suspend fun turnOff()

    /**
     * Pairs a device called [deviceName] and returns its token, to be shown once.
     *
     * @throws IllegalStateException with a message for the user if that is not possible.
     */
    public suspend fun pair(deviceName: String): HubPairing

    /** The paired devices. */
    public suspend fun devices(): List<HubDevice>

    /** Unpairs the device [id]: its token stops working at once. */
    public suspend fun unpair(id: String)
}

/**
 * Where the hub keeps its store and where it listens.
 *
 * @property folder The store's folder, an absolute path.
 * @property host Loopback, or this PC's Tailscale address.
 * @property port The TCP port; 0 picks a free one.
 * @property on Whether the user turned it on.
 */
public data class HubSettings(
    val folder: String,
    val host: String,
    val port: Int,
    val on: Boolean = false,
)

/** What the hub is doing. */
public sealed interface HubState {
    /** Not listening. */
    public data object Off : HubState

    /** Listening at [address], `host:port`. */
    public data class Running(
        val address: String,
    ) : HubState

    /** It could not start, or stopped by itself; [message] says why. */
    public data class Failed(
        val message: String,
    ) : HubState
}

/**
 * A device paired with the hub.
 *
 * @property id Its id on the hub.
 * @property name What the user called it.
 */
public data class HubDevice(
    val id: String,
    val name: String,
)

/**
 * A new pairing. [toString] leaves the token out.
 *
 * @property device The device.
 * @property token What the user enters on the device, shown once.
 */
public class HubPairing(
    public val device: HubDevice,
    public val token: String,
) {
    override fun toString(): String = "HubPairing(device=$device)"
}
