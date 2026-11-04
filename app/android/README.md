# app:android

The Android app: the screens of `app:shared-ui` in one Activity, a "Share to" target, and
sync with WebDAV servers and the PC hub in the background
([PLAN.md](../../docs/PLAN.md) §10, M5; [ADR-0018](../../docs/adr/0018-android-app.md)).
Android 8.0 (API 26) and later, targeting Android 16 (API 36).

```
./gradlew :app:android:assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :app:android:connectedDebugAndroidTest
ANDROID_HOME=~/Android/Sdk app/android/scripts/hub-e2e.sh emulator-5554
```

Set `ANDROID_SERIAL`, or Gradle installs and runs the device tests on every device and
emulator `adb devices` lists.

## Release build

`./gradlew :app:android:assembleRelease` gives an unsigned, unshrunk APK,
`build/outputs/apk/release/android-release-unsigned.apk`, which is reproducible: two builds of one
commit are byte-identical ([ADR-0019](../../docs/adr/0019-release-builds.md)).
`scripts/reproducible-build.sh [REV]` builds it twice in fresh copies and compares; CI's
`reproducible` workflow runs that on `main`, on tags and on pull requests that change the build.
The version is `jotter.versionName` and `jotter.versionCode` in the root `gradle.properties`.
Only with `JOTTER_KEYSTORE` (and its password in `JOTTER_KEYSTORE_PASSWORD`) is the APK signed:
[docs/releasing.md](../../docs/releasing.md) has the maintainer's steps, and
[docs/fdroid](../../docs/fdroid/README.md) the F-Droid recipe, store listing and dependency
audit.

**Dependency rule:** `app:shared-ui` and `app:sync`, AndroidX Activity and WorkManager.
Nothing depends on it. It is an application, not a library, so ADR-0006's `linuxX64` rule
does not apply; what another app could share belongs in `app:sync` or `app:shared-ui`.

## What is here

| Type | What it is |
|---|---|
| `JotterApplication` | The process. Holds the one `AppGraph`, made on first use |
| `AppGraph`, `Opened`, `AndroidSync` | The composition root ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)): the database, the sealed secrets, the backend manager, the scheduler and the screens' state, once per process |
| `MainActivity` | `JotterApp` from `app:shared-ui` under the system bars and the keyboard. Back goes back a screen; coming to the screen syncs; leaving it stores the draft and asks WorkManager for a sync |
| `ShareActivity`, `ShareViewModel`, `ShareScreen` | The share target: shared text becomes a new note, once, and the user sees that it was saved or why not |
| `Share`, `ShareOutcome`, `outcomeOf` | What a share becomes, without Android |
| `SyncWork`, `SyncWorker` | WorkManager: every 15 minutes while a backend exists, and once after leaving the screen, with a network. The worker waits for a round of the scheduler |
| `SealedFileSecretStore`, `AesGcmCipher`, `keystoreKey` | Backend secrets, sealed with an AES-256 key in the Android Keystore, in `noBackupFilesDir` |
| `AndroidTailnet`, `tailnetRefusal` | A hub's token goes out only while Tailscale's VPN carries the app, or to loopback |
| `ScreenExport`, `androidDebugLog`, `androidPlatform` | The debug log: `DebugReport` with the app's log, saved through the Activity on screen |

## Design

- **One graph per process.** The Activity, the share target and the worker use the same
  `NoteStore`, `BackendManager` and `SyncScheduler`, so one sync at a time runs on the
  database. The screens' state (`AppViewModel`) lives in the graph, not the Activity, so
  turning the phone keeps the open note and its draft. The app runs in one process.
- **The database** is `jotter.db` in the app's `databases` folder (`getDatabasePath`), opened
  on first use. If it cannot be opened, the Activity shows `CannotOpenNotes` with the reason in
  plain words (`describeOpenFailure`) and the debug log, the share target says the same, and
  the worker does nothing. The file is left as it is, never replaced by a new one.
- **The log** is `noBackupFilesDir/logs/android.log`, which no backup or transfer copies, at
  INFO, rotated at 256 KiB, four files ([ADR-0020](../../docs/adr/0020-local-logging.md)).
  `JotterApplication` installs it, routes OkHttp's `java.util.logging` lines to it and logs
  uncaught failures. "Debug log" on the Sync screen shows the whole debug log, and "Save…"
  hands it to Android's document picker (`ACTION_CREATE_DOCUMENT`): the user picks a folder or
  a cloud drive. Jotter itself sends it nowhere.
- **Threads.** The screens run on the main thread and every database call on
  `Dispatchers.IO`, as on the desktop. The scheduler runs on `Dispatchers.Default`, the sync
  engine and the backends on their own dispatchers: no sync work runs on the main thread,
  which `SyncWorkerTest` checks for every backend call.
- **When it syncs** (ADR-0018): the scheduler's first round on start, a round each time the
  Activity comes to the screen, 5 s after edits stop and on "Sync now" while the process
  runs, and through WorkManager every 15 minutes (network required, only while a backend
  exists) and once after the app leaves the screen or a text is shared. A worker calls
  `SyncScheduler.syncAndWait`, so the backoff, the 30-minute wait after a refused password
  and the outcomes are the scheduler's; the worker always succeeds.
- **Leaving the screen** stores the editor's draft and the review's combined texts
  (`AppViewModel.keep`), as Android may end the process without warning, and Back is the
  screens' own Back (`AppViewModel.back`); on the note list it leaves the app.
- **Backends.** WebDAV and the PC hub, through the Sync screen of `app:shared-ui`. Folders are
  not offered: the screen says why (`FOLDERS_UNAVAILABLE`, ADR-0018).
- **Secrets** (ADR-0016, ADR-0018): one AES-256-GCM key in the Android Keystore, usable whenever
  the phone has been unlocked once since boot, so that background sync works while it is
  locked. Password and token fields ask the keyboard for a password field without
  autocorrection (`app:shared-ui`), so a keyboard neither learns nor syncs them. Each secret gets a fresh IV and its name as
  associated data; the sealed secrets are one JSON file in `noBackupFilesDir`, replaced
  whole through a new file, synced and renamed. A secret the key cannot open reads as none,
  and the user enters it again; a damaged file is left as it is and reported. Nothing logs.
- **Backups** (`res/xml`, `res/xml-v28`): from Android 9, Auto Backup and device transfer copy
  `jotter.db` and its `-wal` only, and a cloud backup only if it is end-to-end encrypted. On
  Android 8, which cannot require that, nothing is backed up. Never the secrets, whose key
  cannot leave the phone anyway.
- **The tailnet.** Before a hub is opened, `AndroidTailnet` asks whether this app's network
  is a VPN holding a Tailscale address (`100.64.0.0/10` or `fd7a:115c:a1e0::/48`), as
  Tailscale's app is. If not, the hub is unreachable with a plain reason and nothing is sent,
  as a mobile carrier may use `100.64.0.0/10` itself. Loopback is always allowed. Otherwise
  the hub client runs on `tailnetEngine` (Ktor's OkHttp engine) with `TailnetSockets`: each
  socket is bound to that VPN with `Network.bindSocket` and connects only to an address its
  routes cover, and the hub's name is looked up on that VPN with `Network.getAllByName`, so a
  VPN that goes away mid-sync makes requests fail rather than leave by the mobile network, and
  a MagicDNS name is never asked of the mobile network's DNS. Another app's VPN on `100.64.0.0/10` passes the check; Android does not say
  whose VPN it is.
- **Sharing.** `ACTION_SEND` of `text/plain` or `text/markdown`. The subject becomes the
  title, on one line and at most 200 characters, unless it repeats the text. A file or image
  alone is refused in plain words; beside text it is left out and named. Text over
  `MAX_NOTE_CHARS` is refused whole. Android refuses a share of about 330,000 characters
  before Jotter sees it (one binder transaction of 1 MB), so that is the practical limit.
  An extra that cannot be unparcelled, such as another app's class, refuses the share
  plainly (on Android 13 and later extras unparcel one by one, so the text is still saved).
  The saved note's id is kept in saved state, so a window that Android brings back after
  ending the process does not save it again. The intent's extras are kept out of that state.
- **Edge to edge.** Android 15 and later draw apps under the system bars; the screens are
  padded by `safeDrawing`, which includes the keyboard.
- **No EmojiCompat.** The manifest removes emoji2's `EmojiCompatInitializer`, which would ask a
  font provider such as Google Play Services for an emoji font (docs/fdroid). Compose draws
  emoji with the system font; one newer than the phone's Android shows as a blank box.

## Tests

Host tests (`src/test`, in `check` and the coverage gate): `SharedTextTest` (what a share
becomes, the cap, titles), `ShareViewModelTest` (saving once, across process death too, and
failures in plain words), `AndroidSyncTest` (the scheduler starts once, coming back syncs,
jobs follow whether a backend exists), `TailnetSocketsTest` (binding, the route check, and
the hub client speaking to a real hub through OkHttp on such sockets), `SealedFileSecretStoreTest` (the sealed file with a software AES
key: round trips, a lost key, a moved secret, damage, failures, concurrent writes) and
`TailscaleTest` (when a token may go out).

Device tests (`src/androidTest`; CI's `android-device` job runs them on API 26 and 36):

| Test | What it shows |
|---|---|
| `MainActivityTest` | A note written in the app is stored; emoji, also one newer than API 26, are edited and shown without EmojiCompat; Back leaves the editor and then the app; turning the phone keeps the draft; leaving the screen stores it; the Sync screen offers WebDAV and the hub, and says why there is no folder; the password and token fields ask for a password keyboard without autocorrection (`EditorInfo.inputType`) |
| `ShareActivityTest` | Shared text becomes one note, also when the phone turns; 110,000 characters are stored whole; an image is refused; text beside a file is kept; an empty share saves nothing; an extra that cannot be unparcelled does not crash the window |
| `SyncWorkerTest` | `SyncWorker` syncs both ways with another device through a folder and returns after its round, with no backend call on the main thread; the periodic job exists only while a backend does; a database that cannot open ends the job quietly |
| `KeystoreSecretsTest` | The sealed store with a real Keystore key; a lost key means entering the secret again |
| `TailnetTest` | Without Tailscale, only loopback gets a token, and adding a hub at a Tailscale address is refused at once; a socket bound with `Network.bindSocket` connects only where its routes lead |
| `HubEndToEndTest` | With `scripts/hub-e2e.sh` only, otherwise skipped: see below |

Each uses `TestGraph`, a graph on a database, secrets file and Keystore key of its own,
which it removes afterwards.

**End to end with a PC.** `scripts/hub-e2e.sh SERIAL` runs `app:sync`'s `HubForAndroidTest`
on this machine: a hub on 127.0.0.1 and the PC syncing with the hub's folder, as the desktop
app does. `adb reverse` gives the emulator the same port on its 127.0.0.1, and
`HubEndToEndTest` on the device adds the hub with a fresh pairing token, syncs through
`SyncWorker`, and writes a note, which the PC then finds. `10.0.2.2` is not used: the hub
client refuses it like any LAN address, and no test exception is made (ADR-0018).

Run on 2026-11-04, on headless emulators with the SDK's default x86_64 images: all device
tests and the end-to-end script passed on API 36 and API 26.

## Not verified

- Saving the debug log through the document picker: the Activity's export is not driven by a
  device test, as the picker is another app's screen.
- A real phone, a real tailnet, Tailscale's Android app, and MagicDNS names: the emulators
  have no VPN, so only the refusal side of the Tailscale check ran on a device, and socket
  binding ran against the emulator's own network, not a VPN that goes away mid-sync.
- WebDAV from a device: the WebDAV client is the same code as on the desktop, and the device
  tests sync through a folder and the hub.
- Auto Backup and device transfer: the rules are written, not exercised.
- Doze, App Standby and a process frozen in the background; when Android actually runs the
  jobs.
- The screens on a phone: the device tests drive them, but nobody has looked at them. The
  shared forms stack below 600 dp; other rows, such as the note list's filters, were not
  checked on a narrow screen.
