# app:shared-ui

The screens and view models of the Linux desktop app and, from M5, the Android
app: Compose Multiplatform, for `jvm` and Android. It has no `linuxX64` target
([ADR-0010](../../docs/adr/0010-compose-ui-outside-linuxx64-rule.md)), so logic
that the CLI could want does not belong here.

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
val app = AppViewModel(store.notes, scope, Dispatchers.IO)
JotterApp(app) // in a window
// When the user closes it:
app.requestExit { exit() } // saves first, and asks if that fails
// After exit:
scope.coroutineContext.job.cancelAndJoin() // before store.close()
```

**Dependency rule:** depends on `core:storage`. Only `app/*` may depend on it.

## What is here

| Type | What it is |
|---|---|
| `AppViewModel` | The one public class: the list, the open editor, `refresh` and `requestExit`. Built by the app's composition root ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)) |
| `JotterApp` | The one public composable, which calls `refresh` whenever its window gains focus |
| `NotesViewModel`, `NotesScreen`, `NoteCard` | The grid, pinned first, with search, the active, archive and trash views, the tag filter, and pin, archive, trash, restore and delete forever (which asks first) |
| `EditorViewModel`, `EditorScreen` | The plain text editor |

Tags cannot be added or removed here yet, and Markdown preview comes later.

## Design

- **Snapshot state, not flows, in the view models' API.** A text field must see
  its new value in the same frame, or fast typing loses characters, and a value
  that passes through a coroutine arrives later. So what the screens read is
  Compose state, written on the UI thread. It also makes the view models `@Stable`.
  No lifecycle library: `scope` belongs to the app, and an editor's observation
  is a child of it that ends when the editor closes.
- **Threads.** Every `NoteService` call is on the injected `io` dispatcher.
- **Refresh.** `NoteService`'s flows do not see another process's writes, so
  `refresh` makes the view models subscribe again, which reads the database.
- **Search** starts at two characters after trimming, because one letter is the
  slowest query (`core:storage`). It has no flow, so it uses `observeList` for the
  same filter as its signal, which reads the list once more per keystroke. It runs
  in the current view and tag filter. Below two characters the list shows.
- **The draft belongs to the user.** The editor follows the stored note only
  while the draft has no changes. An edit is based on the revision the draft
  started from. If the text changed elsewhere, `StaleEditException` leaves the
  draft in place with a message and two choices: save it as a new note with the
  same tags, or drop it for the stored text. A note deleted forever works alike.
  `StorageBusyException` keeps the draft too, and a second save simply retries.
  Any other failure, such as a full or read-only disk, also keeps it and shows
  SQLite's reason; nothing may escape into a scope where it would end the app.
  Text typed while a save runs stays in the draft.
- **Saving** is the Save button, Ctrl+S, Back, `requestExit`, and an autosave 1 s
  after typing stops, all on the same path, so a crash loses about that second. A
  failed autosave keeps the draft and shows the message like any other save, and
  the next pause in typing tries again. It waits while a stale or deleted note
  needs the user's decision, because only that can resolve it. Each autosave is a
  revision, so a note gets one per pause. A note emptied after its first
  autosave stays, empty. Back and closing save
  until the draft is clean, so that text typed during the save is not lost with the
  editor, and "Saving…" shows meanwhile, up to 5 s if the database is locked. If
  saving fails, Back stays in the editor with the message showing, and closing asks
  whether to keep editing or close without saving. A new note that never had text
  is not created.
- **Failures in the grid** show as a message: a locked database, a note deleted
  elsewhere, a failed read, and any other failed write. Each is kept until the next successful write
  or until dismissed. A failed read keeps what the list showed.

## Tests

All tests are in `jvmTest`, on a real store in a temporary directory.
`NotesViewModelTest` and `EditorViewModelTest` run everything on one test
dispatcher; `JotterAppTest` runs the real screens with `runComposeUiTest`, which
needs no display, and real IO threads, including the stale edit. The tests for a
locked database hold a real lock, so each waits out the 5 s busy timeout. Android
host tests are disabled, as in `core:storage`.
