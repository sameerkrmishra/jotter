# 0016. Where backend secrets live on Linux

- **Status:** Proposed
- **Date:** 2026-11-03

## Context

M4 adds backends that need a secret. WebDAV needs an app password, such as one made
for Jotter in mailbox.org's settings. The PC hub (PLAN.md §6.4) needs the secret a
phone and the PC agree on when they are paired. A local folder needs none.

Both the desktop app and the CLI sync, from one database, so both must reach the same
secrets. The CLI may run where there is no desktop session: over SSH, in a cron job,
in a minimal window manager. It may one day be a native binary
([ADR-0006](0006-native-compatible-shared-modules.md)).

The notes database is the wrong place. It is copied into backups, read by `VACUUM INTO`
for export, and a user may hand it over in a bug report. Logs are the same.

What a store on the user's own account can and cannot do, honestly: any program that
runs as the user can read the database, a file, and, once the keyring is unlocked,
the Secret Service too (GNOME Keyring does not ask per application). So no choice here
protects against malware running as the user. What differs is what happens at rest:
a stolen unencrypted disk, a backup, a dotfiles repository, a synced home directory.

## Decision

**Secrets never go into the database, a log, a settings value, a command-line
argument or an environment variable.** The `backend` table holds a backend's kind and
settings only; a secret is named by the backend's id and its `SecretKind`
(`APP_PASSWORD`, `PAIRING_SECRET`). `Secret.toString()` hides the text, so that a log
line or exception message that mentions one by mistake does not give it away; only
`reveal()` returns it. Removing a backend deletes its secrets.

**The code depends on the `SecretStore` interface in `app:sync`**: `read`, `write`,
`delete`, failing with `SecretStoreException`. Tests use an in-memory double.
`NoSecretStore` holds nothing and refuses to store anything. The desktop app and the
CLI give `BackendManager` and the backend factory the Linux stores below, from
`linuxSecretStores`, and the WebDAV and PC hub backends read their secret from them.

**On Linux the default is the Secret Service** (the freedesktop `org.freedesktop.secrets`
D-Bus API), which GNOME Keyring, KWallet (`ksecretd`) and KeePassXC provide. Jotter
reaches it through libsecret's `secret-tool`, with the secret on its standard input
(`store`) or output (`lookup`), never in its arguments, and the attributes
`application=jotter`, `backend=<id>`, `kind=<kind>`. The label names the backend, not the
secret. A locked keyring is unlocked by the desktop's own prompt; without one,
`secret-tool` fails and so does that sync, with the reason on the status screen.
`secret-tool` runs with D-Bus autolaunch disabled (`DBUS_SESSION_BUS_ADDRESS` passed
through only if set, and `DBUS_SESSION_BUS_ADDRESS=disabled:` otherwise), so that
without a session bus it fails at once instead of starting a bus and a keyring daemon
of its own, whose secrets would not be the user's keyring and would be gone after.
It fails closed: no bus means no secret, and the file below only if the user chose it.

**The fallback is a file, and only when the user chooses it.** If no Secret Service
answers (no session bus, no provider, no `secret-tool`), adding a backend that needs a
secret fails with a plain message that offers the file instead: the desktop app's add
dialog has a choice for it, and the CLI an option. The file is
`$XDG_CONFIG_HOME/jotter/secrets.json` (by default `~/.config/jotter/`), in a directory
created with mode 0700. Every version of the file is written to a temporary file in the
same directory that is **created with mode 0600** (`open` with `O_CREAT | O_EXCL` and
mode 0600, or on the JVM `Files.createFile` with the `PosixFilePermissions` attribute,
which passes the mode to that same call), and then renamed into place. It is never
created with wider permissions and narrowed with `chmod` afterwards, as the secret
would be readable in between. Jotter refuses to read it if it is readable by group or
others, or owned by another user. The backend's status says that its secret
is in that file, in plain text.

Android (M5) will keep secrets with a key from the Android Keystore; that is M5's
decision.

**Built for WebDAV (M4).** `SecretServiceStore` and `SecretFileStore` in `app:sync`
(JVM) implement this record; see `app/sync/README.md`. The fallback is offered when
the keyring reports no bus, no provider or no `secret-tool`; a keyring that refuses
otherwise, such as a dismissed unlock prompt, is an error and offers nothing.

**Only a `clear` that succeeds counts as a delete.** `secret-tool clear` deletes only
unlocked items and ends with 1, without a word, when it deleted none, so no item, a
locked item and a dismissed unlock prompt all look the same. `SecretServiceStore`
treats exit 0 as deleted and that silent exit 1 as "the keyring may be locked, or the
item already gone", naming the item, so that removing the backend offers Remove anyway
or `--keep-secret`. The cost: removing a backend whose secret is already gone asks the
same, once. No further call can tell these cases apart for every provider:

- libsecret 0.21.8, the version checked: `secret-tool search --all` without `--unlock`
  does list locked items, without prompting (`tool/secret-tool.c` lines 493 to 523;
  `libsecret/secret-methods.c` line 216 and lines 491 to 513). It ends with 0, prints
  the item's path, label and dates on stdout and "secret is locked" on stderr, and with
  no match ends with 0 and prints nothing. A review confirmed this against libsecret's
  mock Secret Service on a private bus. Its manual page, which says locked items are
  skipped without `--unlock`, is wrong.
- KeePassXC: `Collection::searchItems` (`src/fdosecrets/objects/Collection.cpp`)
  returns no items and no error while its database is locked, so a search would report
  a locked KeePassXC secret as gone.

Not tested against GNOME Keyring, KWallet or KeePassXC themselves, as no real keyring is
available to the tests; the rule above does not depend on how they answer a search.

**This record was a design until a backend needed a secret.** The Secret Service store
and the file were built, with their tests, for WebDAV, the first backend to need them,
and the PC hub uses them too. Before that, only the interface, the test double and
`NoSecretStore` existed, and the apps passed `NoSecretStore`.

## Consequences

### Positive

- On a normal desktop a secret is encrypted at rest with the login password, and
  stays out of backups and dotfile repositories of the home directory.
- Nothing about secrets is in the database, so a database can be shared in a bug
  report or restored on another machine without leaking a password. Restoring it
  elsewhere needs the password entered again, which is what should happen.
- One small interface, so the tests and every backend use the same seam, and the
  store can change without touching a backend.
- `secret-tool` adds no library to the build and works the same from the JVM and from
  a native CLI.

### Negative

- It needs `secret-tool` installed: the `libsecret` package on Arch and Fedora,
  `libsecret-tools` on Debian and Ubuntu, which is not always installed by default.
- Each read starts a process, about 10 to 20 ms. Secrets are read when a backend is
  opened for a sync, never on `jotter add`, so ADR-0006's startup budget is not
  touched.
- Over SSH or in cron there is usually no session bus, so the CLI cannot reach the
  keyring there, and a user who wants that must choose the file.
- The file is plain text. Anyone or anything that can read the user's files, or a
  backup of `~/.config`, has the password. The app password is meant to be revocable
  on its own, which limits the harm.
- Neither protects against a program running as the user.
- The attributes and label, though not the secret, are visible to other processes
  of the user while `secret-tool` runs.

## Alternatives considered

- **The Secret Service through a D-Bus library on the JVM** (dbus-java with JDK 16's
  Unix domain sockets): no external tool, but a sizeable dependency, the Secret
  Service's session encryption to implement, and nothing for a native CLI.
- **libsecret through JNI or Kotlin/Native cinterop:** a native library in every
  build, against the packaging goals.
- **Only a 0600 file:** works everywhere, and is what many command-line tools do
  (`.netrc`, rclone). Rejected as the default because the secret is in plain text
  at rest and in every backup of the home directory, when most desktops have a
  keyring.
- **Only the Secret Service:** leaves headless and SSH users without sync to WebDAV.
- **A file encrypted with a key in the database or next to it:** looks safer and is
  not; whoever can read one can read the other.
- **A passphrase asked on every start:** honest protection at rest, but sync in the
  background then needs the user every time, which local-first sync must not.
- **In the database:** see Context.
