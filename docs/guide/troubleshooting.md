# When something goes wrong

Jotter's messages say what happened and what to do. This page lists them, by where they
appear, with a little more help. Messages are shortened here; `...` stands for a file, an
address or a reason.

If nothing here helps, [send the debug log](debug-log.md).

## "Jotter cannot open your notes"

The desktop app shows this in a window, the Android app on its screen, and the command as a
line starting with `jotter:`. In every case Jotter has not changed your notes file.

| The message starts with | What to do |
|---|---|
| Your notes at ... are in use by another Jotter program | Wait for the other program, such as a `jotter` command, to finish. Try again |
| The disk that holds your notes at ... is full | Free some space. Try again |
| Your notes file ... is damaged | Keep a copy of the file first. Then [restore a backup, or start fresh and let sync bring your notes back](backup.md#a-damaged-notes-file) |
| Jotter may read your notes at ... but not change them | The file or its folder is read-only. Check its permissions, and that the disk is not mounted read-only |
| Jotter cannot open ... | The folder is missing, or you may not use it. Check both |
| The disk could not be read or written at ... | If the notes are on a removable drive, plug it in. Try again |
| Your notes at ... were saved by a newer version of Jotter | Update Jotter |
| ... is not a Jotter notes file | `JOTTER_DB` or `--db` points at another program's file. Check the path |
| Jotter could not update your notes at ... for this version | Nothing was lost. Report it, with the debug log. Meanwhile use the version you had |
| Jotter could not open your notes at ... | Try again. If it keeps happening, report it with the debug log |

## Saving or changing a note fails

The editor says "Your text is kept here but could not be saved.", and the list says what it
could not do. Your text stays on screen. Then one of these:

| It goes on | What to do |
|---|---|
| Another Jotter program is using the notes | Try again. Saving again is safe |
| The disk is full | Free some space. Jotter tries to save again every few seconds |
| The notes file is damaged | Keep a copy of it, then see [a damaged notes file](backup.md#a-damaged-notes-file) |
| Jotter is not allowed to change the notes file | Check the file's and folder's permissions |
| The disk could not be written | If it is a removable drive, plug it in |
| Something unexpected went wrong | Report it with the debug log |

Other editor messages:

- **This note was changed elsewhere.** Another device or window changed it while you typed.
  **Save as new note** keeps your text as a new note. **Discard and load theirs** drops it.
- **This note was deleted forever.** **Save as new note** keeps your text.
- **Your text could not be saved** (when closing the desktop app). **Keep editing** lets you
  copy your text somewhere safe. **Close without saving** loses it.

## Sync

| The message starts with | What to do |
|---|---|
| Not found (... is missing) | Plug in the drive. Nothing syncs until it is back |
| The location is there, but it is not marked as a Jotter store | The drive is probably not plugged in, and you see the empty folder it mounts on. Plug it in. If the hidden `.jotter-store` file was deleted, remove the backend and add it again |
| Cannot be reached: ... | Check the network, and that the server or PC is on. Jotter tries again by itself |
| The server refused the user name or app password | Make a new app password and enter it ([how](sync-status.md#the-status-of-a-backend)) |
| The PC hub refused this device's pairing token | Pair this device again on the PC and enter the new token |
| The PC hub answers, but its folder is not set up | On the PC, turn the hub off and on |
| Jotter could not read this backend's password (or this hub's pairing token): the keyring may be locked, or the password is not stored on this device | Linux. Unlock your keyring (log in to your desktop), or enter it again with **Enter new password**. Normal after a restore on another computer |
| Jotter could not read this backend's password: Jotter cannot reach a keyring | Linux, with no keyring, as over SSH or without `secret-tool`. Run it from your desktop session, or [keep the password in the secrets file](backup.md#on-a-computer-without-a-keyring) |
| This backend's password is not stored on this phone (or this hub's pairing token) | Android, after a restore. Press **Enter new password** and enter it again; for the hub, pair the phone on the PC again first |
| Not fully synced: N files could not be read | Nothing: it is tried again soon |
| Stopped part way | Nothing: the next sync goes on |
| Not synced: ... A newer version of Jotter may be able to use it | Update Jotter |
| Not synced, because of a problem on this device | Read the rest: it is one of the saving problems above |
| Jotter could not sync: ... It tries again | The same |
| Jotter cannot see edits to sync them | The same. Jotter still syncs every few minutes |
| Tailscale is not running on this phone | Turn Tailscale on. Jotter never sends the hub's token outside Tailscale |
| Tailscale's network on this phone is gone, or does not lead to the hub | Check that Tailscale is connected, and that the PC is on your tailnet |

Problems with one file on a backend, such as "File N of device X is damaged", are explained
in [Issues](sync-status.md#issues).

## Adding a backend

| The message starts with | What to do |
|---|---|
| ... is not a Jotter store yet | Normal for the first device. Plug in the drive first if it is one, then confirm (command: add `--new`) |
| There is no folder at ... | Check the path. For a server, create the folder first |
| ... cannot be a backend: it holds this device's notes database | Choose a folder apart from `~/.local/share/jotter` |
| ... is inside the Jotter store ... | Add the outer folder instead |
| Cannot use ...: Plain http would send the password and every note unencrypted | Use the `https://` address |
| Jotter cannot sync through ...: the server replaces a file | That server cannot be used safely. Use another one |
| Jotter cannot reach a keyring | Install `secret-tool` (package `libsecret` or `libsecret-tools`), or log in to a desktop, or keep the password in a file (command: `--password-in-file`) |
| ... is not on your tailnet or this machine | Use the PC's Tailscale address, starting with `100.`, or its Tailscale name |
| The hub refused this pairing token | Pair the device again on the PC, and copy the new token exactly |
| The hub at ... answers, but its folder is not set up | Turn the hub on, on the PC |

## Export database

| The message starts with | What to do |
|---|---|
| ... exists already, so it was left alone | Choose a new name, or add `--replace` (command) |
| That is the notes file Jotter is using | Choose another name or folder for the copy |
| The folder ... does not exist | Choose another place |
| ... is a folder. Choose a file name for the copy | Give a file name, not only a folder |
| Jotter is not allowed to write in ... | Choose a folder you may write to |
| There is not enough free space in ... for the copy | Free some space, or choose another place |
| The disk at ... could not be written | If it is a removable drive, plug it in, then try again |
| Another Jotter program kept the notes busy for too long | Try again |
| The copy could not be written to ... | Try again. If it keeps happening, report it with the debug log |

Your notes are never changed by a failed export, and no half-written copy is left under the
name you chose.

## The PC hub

- **The hub is not running: ...** Read the reason. If another program uses the port, choose
  another **Port**.
- **... Use 127.0.0.1 for this PC only, or this PC's Tailscale address.** **Listen on** must
  be one of those two.

## The debug log

- **The debug log could not be saved there.** Choose another folder, with free space.
- **The debug log could not be put together.** Report the problem without the log.

## The command

- `jotter: id ... is ambiguous` or `no note with id ...`: copy the whole id from
  `jotter list`.
- `jotter: ... exists already; choose a new file name`: `debug-log --output` never
  overwrites a file.
- `jotter: N of M backends did not sync`: the lines above it say which, and why.
- `jotter: something unexpected went wrong, and nothing more was done`: report it, and attach
  the output of `jotter debug-log`.

## Checked

These messages were made on purpose and read back with the command: a file that is not a
database, another program's database, a damaged file, a newer version's file, a read-only
file and folder, a locked file, export to an existing file, the live file, a missing and a
read-only folder and a folder name, missing and empty folders, a refused and a corrected password,
a missing password after a restore (also on the desktop app and the Android emulator), an
unpaired device, an address outside Tailscale, plain
`http`, and an unknown server. The rest are quoted from the program's text.
