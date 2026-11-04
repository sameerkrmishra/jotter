# Back up and restore

Sync already keeps a copy of your notes on every device and backend. A backup also protects
you against mistakes that sync would copy everywhere, such as a note deleted forever.

## What to back up

| Platform | Back up | Where |
|---|---|---|
| Linux | The notes file `jotter.db` | `~/.local/share/jotter/` (see [where Jotter keeps things](README.md#where-jotter-keeps-things-on-linux)) |
| Android | Nothing by hand: you cannot reach the file. Use sync, and Android's own backup | Below |

That one file holds all your notes, their history, your tags and the list of backends. While
Jotter runs, recent changes may also sit in `jotter.db-wal` next to it.

**Passwords and pairing tokens are not in the notes file.** They are in your keyring, or in
`~/.config/jotter/secrets.json` if you chose that, and on Android in a place no backup
copies. After a restore on another device you enter them again, so you need not back them
up.

## Make a backup on Linux

**The simple way: with Jotter closed.**

1. Close the desktop app, and wait for any `jotter` command to finish.
2. Check that only `jotter.db` is left:

   ```
   ls ~/.local/share/jotter/
   ```

   If `jotter.db-wal` is still there (after a crash, for example), run `jotter list` once
   and look again. Jotter tidies it up when it closes.
3. Copy the file, with the date in its name:

   ```
   mkdir -p ~/Backups
   cp ~/.local/share/jotter/jotter.db ~/Backups/jotter-2026-11-04.db
   ```

**While Jotter runs.** Copying `jotter.db` alone can miss recent changes. Use SQLite's own
copy instead. It needs the `sqlite3` program (package `sqlite` or `sqlite3`). The target
file must not exist yet:

```
sqlite3 ~/.local/share/jotter/jotter.db "VACUUM INTO '$HOME/Backups/jotter-live.db'"
```

**Check the backup.** Both ways, make sure the copy opens and holds your notes:

```
sqlite3 ~/Backups/jotter-live.db "PRAGMA integrity_check"
jotter --db ~/Backups/jotter-live.db list
```

The first must print `ok`. The second lists the notes in the backup.

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
2. Copy your backup (or the old computer's `jotter.db`, with Jotter closed there) into
   place on the new one:

   ```
   mkdir -p ~/.local/share/jotter
   cp jotter-2026-11-04.db ~/.local/share/jotter/jotter.db
   ```

3. Start Jotter and open **Sync**. Your backends are listed:
   - A **WebDAV server** says Jotter "could not read this backend's password", and a **PC
     hub** that it "could not read this hub's pairing token". Make a new app password, or
     pair this computer again on the PC, and enter it with **Enter new password** (command:
     `jotter backends password ID`).
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

After Android restores Jotter, your notes are there. Open **Sync**: each backend says Jotter
could not read its password or pairing token. Press **Enter new password** and enter an
app password, or a new token after pairing the phone again on the PC. Then sync brings in
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

On Linux with the command: both backup ways, the checks, a restore on the same account (a
note written after the backup came back at the next sync), a restore into a new account (the
WebDAV password had to be entered again), starting fresh after a damaged file, and recovering
a damaged file on a USB folder. On the desktop app: deleting the hub's folder and turning
the hub off and on (the phone synced again with its old token). On the Android emulator: a
backup through Android's test backup service, which held only `jotter.db` and
`jotter.db-wal`, held nothing when the service said it was not encrypted, and after
reinstalling brought back the notes but not the passwords. Not tested: Google's real
backup, moving to a new phone through Android's own transfer, Android 8, and losing
`hub-devices.json`.
