# Jotter user guide

Jotter keeps your notes on your own devices: a Linux PC, an Android phone, and the
`jotter` command in a terminal. Each device has its own copy of all your notes and works
without the internet. Devices share changes through storage you choose.

1. [Install Jotter](install.md)
2. [Write notes](notes.md): Markdown, tasks, tags, search, archive and trash
3. [Import from Google Keep, export to Markdown](import-export.md)
4. [The `jotter` command](cli.md)
5. [Sync your devices](sync.md): a USB stick, a WebDAV server, or your PC as a hub
6. [What sync tells you](sync-status.md): status, issues and conflicts
7. [The debug log](debug-log.md): what it holds and how to send it
8. [Back up and restore](backup.md)
9. [When something goes wrong](troubleshooting.md): every error message and what to do

## Where Jotter keeps things on Linux

| What | Where |
|---|---|
| Your notes | `~/.local/share/jotter/jotter.db`, with `jotter.db-wal` and `jotter.db-shm` beside it while Jotter runs |
| Passwords and pairing tokens | Your desktop's keyring, or `~/.config/jotter/secrets.json` if you chose a file. Never in the notes file |
| The PC hub's settings | `~/.config/jotter/hub.properties` and `~/.config/jotter/hub-devices.json` |
| The PC hub's folder | `~/.local/share/jotter-hub`, unless you chose another |
| Logs | `~/.local/state/jotter/logs/` |
| A speed-up cache | `~/.cache/jotter/`. Safe to delete |

If you set `XDG_DATA_HOME`, `XDG_CONFIG_HOME` or `XDG_STATE_HOME`, Jotter uses those
folders instead of `~/.local/share`, `~/.config` and `~/.local/state`. If you set
`JOTTER_DB`, your notes are in that file.

On Android, your notes live inside the app's private storage. Other apps, and you, cannot
reach that file without special tools.

## How this guide was checked

Each step was run on 2026-11-04 with Jotter 0.1.0, in empty test accounts: the AppImage
and the command-line zip from a test release build, and the command, the desktop app (on a
private display) and the Android app (on an Android 16 emulator) built from the main
branch. A few steps could not be run there; each page says which.
