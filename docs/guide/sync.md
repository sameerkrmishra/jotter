# Sync your devices

## What sync is

Every device keeps all your notes and works on its own. To share changes, devices use a
common place that Jotter calls a **backend**. Each device writes its changes there and
reads the others'. There is no Jotter server and no account.

A backend can be:

| Backend | Desktop and command | Android |
|---|---|---|
| A folder, such as a USB stick | Yes | No |
| A WebDAV server, such as Nextcloud, mailbox.org or a NAS | Yes | Yes |
| Your PC as a hub, over Tailscale | Yes | Yes |

A device can use several backends, and passes changes on between them. For example, a
phone that uses both WebDAV and the hub carries notes from one to the other.

Jotter does not encrypt what it writes to a backend. Anyone who can read that folder or
server can read your notes. Choose a place you trust.

**When it syncs.** The desktop app syncs when it starts, 5 seconds after you stop editing,
every 5 minutes, and when you press **Sync now**. The Android app does the same while it is
open, and also about every 15 minutes in the background when there is a network. The
command syncs only when you type `jotter sync`.

All of this is on the **Sync** screen, top right of the main screen.

## A USB stick or another folder (Linux)

1. Plug in the stick. Note where it is mounted, such as `/run/media/you/STICK`.
2. On the **Sync** screen, type that path under **Folder** and press **Add folder**.
3. The first time, Jotter asks **Set this folder up for Jotter?** Check the path, then
   press **Set up and add**. It creates a hidden file `.jotter-store` and a folder `jotter`
   on the stick.
4. On your other computers, add the same folder the same way. Jotter sees it is already
   set up and adds it at once.

With the command, the first computer needs `--new`, the others do not:

```
jotter backends add --new --name "USB stick" /run/media/you/STICK
jotter sync
```

When the stick is unplugged, its status says "Not found". Plug it in and Jotter syncs
within half a minute (the command: run `jotter sync`).

The folder can also be one that another program copies between your computers.

## A WebDAV server

1. In your provider's account settings, make an **app password** just for Jotter. You can
   revoke it later without changing your main password.
2. Make an empty folder for Jotter on the server, for example in its web page.
3. On the **Sync** screen, fill in **WebDAV folder URL** (the folder's full address,
   starting with `https://`), **User name** and **App password**. Press **Add server**.
4. The first time, Jotter asks **Set this server folder up for Jotter?** Press **Set up
   and add**. On your other devices the folder is added at once.

On Android the password is kept on the phone only, sealed with a key in the Android
Keystore, and no backup copies it. On Linux it goes to your desktop's keyring. If there is
none, Jotter asks
**Keep the password in a file?** **Keep it in the file** stores it in
`~/.config/jotter/secrets.json`, which only you can read, in plain text.

With the command (it asks for the app password and does not show what you type):

```
jotter backends add webdav https://dav.example.com/jotter/ --user you --new
```

Leave out `--new` on your other devices. Over SSH or in a cron job there is no keyring:
add `--password-in-file`.

Jotter refuses plain `http://` addresses, except on the same machine, and servers that
overwrite files, such as `rclone serve webdav`.

## Your PC as a hub, over Tailscale

The desktop app can serve its notes to your phone and other computers. They reach it
through [Tailscale](https://tailscale.com), which connects your devices privately.

1. Install Tailscale on the PC and on the phone, and sign in to the same account on
   both.
2. Find the PC's Tailscale address. It starts with `100.`. The Tailscale app shows it, and
   so does `tailscale ip -4` on the PC.
3. On the PC, open **Sync**. Under **This PC as a hub**, set **Listen on** to that address.
   Keep **Port** 47734 and the **Hub folder** unless you need others. Press **Turn hub on**.
   It says "The hub is on, at 100.x.y.z:47734." The PC also starts syncing with the hub
   folder itself.
4. Type a name under **Device to pair**, such as `Phone`, and press **Pair device**. Jotter
   shows a token once. Keep the window open.
5. On the phone, make sure Tailscale is on. Open **Sync**, type the PC's address under
   **PC hub address** and the token under **Pairing token**, and press **Add PC hub**. If
   you changed the port, write the address as `100.x.y.z:PORT`.

From another Linux computer, use the command:

```
jotter backends add peer 100.x.y.z
```

It asks for the token. The hub runs only while the desktop app is open, and starts again
with it. To stop a device, press **Unpair NAME** on the PC; its token stops working at
once.

The hub listens only on the PC's Tailscale address or on the PC itself (`127.0.0.1`), never
on your home or office network.

## Stop syncing with a backend

Press **Remove** on its card, or `jotter backends remove ID` (`jotter backends` lists the
ids). Jotter forgets the backend on this device, and deletes its password. Its files stay
where they are, so other devices can go on using it. Delete them yourself if no device
needs them.

## Checked

USB folder, WebDAV and the hub were run between two Linux accounts, the desktop app and the
Android emulator, with an Apache WebDAV server on the same machine. The hub was reached at
`127.0.0.1`, not over Tailscale: the Tailscale steps, a real provider's app passwords and the
Android background sync were not tested.
