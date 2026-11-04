# Back up and restore

Sync already keeps a copy of your notes on every device and backend. A backup also protects
you against mistakes that sync would copy everywhere, such as a note deleted forever.

## What to back up

| Platform | Back up | How |
|---|---|---|
| Linux | The notes file, `~/.local/share/jotter/jotter.db` (see [where Jotter keeps things](README.md#where-jotter-keeps-things-on-linux)) | **Export database**, or copy the file with Jotter closed |
| Android | The notes file, which only Jotter can reach | **Export database**, sync, and Android's own backup |

That one file holds all your notes, their history, the trash, your tags, and which backends
the device syncs with.

**Passwords and pairing tokens are not in it.** They are in your keyring, or in
`~/.config/jotter/secrets.json` if you chose that, and on Android in a place no backup
copies. After restoring a copy you enter them again (see below), so you need not back them
up.

## Make a backup: Export database

Export database writes a checked copy of the notes file, also while Jotter runs and syncs.

**Desktop:** **File** → **Export database…**, or **Export database…** at the foot of the
**Sync** screen. Press **Choose where…**, pick a folder and a file name (Jotter suggests
`jotter-notes.db`), and press **Save**. Jotter says "Exported your notes to ... Passwords and
pairing tokens are not in it: after restoring it, enter them again."

**Android:** **Sync** → **Export database…** at the foot of the screen → **Choose where…**.
Android's file picker opens: choose a place, such as **Downloads** or a cloud drive, and
press **Save**. Copy the file off the phone if you want it safe from losing the phone.
The Android app cannot load a copy back yet. On a computer you can read it with
`jotter --db ~/Downloads/jotter-notes.db list`, or restore it there as below.

**Command:**

```
mkdir -p ~/Backups
jotter export-database ~/Backups/jotter-2026-11-04.db
```

The command refuses to overwrite a file unless you add `--replace`.

To see what a copy holds: `jotter --db ~/Backups/jotter-2026-11-04.db list`.

## Or: copy the file with Jotter closed

1. Close the desktop app, and wait for any `jotter` command to finish.
2. Check that only `jotter.db` is left:

   ```
   ls ~/.local/share/jotter/
   ```

   If `jotter.db-wal` is still there (after a crash, for example), run `jotter list` once
   and look again. Jotter tidies it up when it closes. Never copy `jotter.db` alone while
   that file is there: recent changes may be in it.
3. Copy the file:

   ```
   mkdir -p ~/Backups
   cp ~/.local/share/jotter/jotter.db ~/Backups/jotter-2026-11-04.db
   ```

## Restore on the same computer

1. Close the desktop app.
2. Move the current files aside. Do not delete them yet:

   ```
   cd ~/.local/share/jotter
   mkdir -p ~/jotter-old
   mv jotter.db* ~/jotter-old/
   ```

   This also moves `jotter.db-wal` and `jotter.db-shm`. They belong to the old file and must
   never sit next to a restored one.
3. Copy the backup into place, then start Jotter:

   ```
   cp ~/Backups/jotter-2026-11-04.db ~/.local/share/jotter/jotter.db
   ```

## What sync does after a restore

A restored device is still the same device to Jotter. At its next sync it reads its own
files on each backend again, as well as everyone else's. So notes you wrote after the
backup come back, as long as a backend still holds them. Nothing else is needed.

## Restore on a new computer, or move to one

1. [Install Jotter](install.md) on the new computer, but do not start it yet.
2. Copy your exported copy (or the old computer's `jotter.db`, with Jotter closed there)
   into place on the new one:

   ```
   mkdir -p ~/.local/share/jotter
   cp jotter-2026-11-04.db ~/.local/share/jotter/jotter.db
   ```

3. Start Jotter and open **Sync**. Your backends are listed:
   - A **WebDAV server** says "Jotter could not read this backend's password: the keyring
     may be locked, or the password is not stored on this device, as after restoring the
     notes on another computer. Unlock your keyring, or enter the password again." A **PC
     hub** says the same of "this hub's pairing token". Press **Enter new password** and
     enter the app password, or a new token after pairing this computer again on the PC
     (command: `jotter backends password ID`). The new computer needs a keyring for this
     if the old one used one.
   - A **USB folder** syncs if it is at the same path. If not, remove it and add it again
     with the new path. That copies your notes there again, which is harmless.

If you keep using the old computer too, both go on syncing without trouble.

## A damaged notes file

If Jotter says your notes file is damaged, it has not changed it. Then:

1. Keep a copy: `cp ~/.local/share/jotter/jotter.db ~/jotter-damaged.db`.
2. Either restore a backup as above, or start fresh and let sync bring your notes back:
   move `jotter.db*` aside as in step 2 above, start Jotter, and add your backends again.
   They are already set up, so Jotter adds them without asking, and the first sync brings
   back every note they hold.

## A damaged file on a backend

A file on a backend can decay, for example on an old USB stick. Every device then reports
"File N of device X is damaged and was not read." Its changes are still on the device that
wrote them, and on any device that read the file before it decayed. On **one such
device**:

1. Remove the backend: **Remove** on its card, or `jotter backends remove ID`.
2. Add it again: the same folder or server, without `--new`.
3. Sync.

That device writes all its notes there again, in new files, and the others read them at
their next sync. The issue stays listed, because the damaged file stays: Jotter never
deletes files on a backend. If no device holds those changes any more, they are lost.

## Android

**Android's own backup** (Google's backup, or the copy when you set up a new phone from an
old one) includes Jotter's notes file and nothing else: not your passwords or tokens, and
not the app's settings. Google's cloud backup includes it only when the backup is encrypted
with your screen lock. On Android 8.0 and 8.1, Jotter is left out of backups altogether.

After Android restores Jotter, your notes are there. Open **Sync**: a WebDAV server says
"This backend's password is not stored on this phone, as after restoring your notes from a
backup or another device, which never hold passwords. Enter the password again." The PC hub
says the same of its pairing token, and asks you to pair this phone on the PC again. Press
**Enter new password** and enter the app password, or the new token. Then sync brings in
what changed since the backup.

**Moving to a new phone.** The simplest way is sync:

1. On the old phone, open **Sync**, press **Sync now**, and check "Last synced just now".
2. On the new phone, install Jotter and add the same WebDAV server, or pair the new phone on
   the PC and add the PC hub. All your notes arrive at the first sync.

## The PC hub

The hub's folder (`~/.local/share/jotter-hub` by default) is a backend like any other: your
devices hold everything in it. If you lose it, press **Turn hub off** and **Turn hub on**:
Jotter sets the folder up again, and every device writes its notes there again at its next
sync. Pairings are kept in `~/.config/jotter/hub-devices.json`; if you lose that file too,
pair your devices again and enter the new tokens.

## Checked

Export database on all three: with the command (also while another program held the file
open and had just written to it), from the desktop's **File** menu, and on the Android
emulator through the file picker; the phone's copy was then read on Linux. On Linux with the
command: copying with Jotter closed, a restore on the same account (a
note written after the backup came back at the next sync), a restore into a new account (the
WebDAV password had to be entered again), starting fresh after a damaged file, and recovering
a damaged file on a USB folder. On the desktop app: deleting the hub's folder and turning
the hub off and on (the phone synced again with its old token). On the Android emulator: a
backup through Android's test backup service, which held only `jotter.db` and
`jotter.db-wal`, held nothing when the service said it was not encrypted, and after
reinstalling brought back the notes but not the passwords. Not tested: Google's real
backup, moving to a new phone through Android's own transfer, Android 8, and losing
`hub-devices.json`.
