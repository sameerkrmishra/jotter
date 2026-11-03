# app:shared-ui

The screens and view models of the Linux desktop app and, from M5, the Android
app: Compose Multiplatform, for `jvm` and Android. It has no `linuxX64` target
([ADR-0010](../../docs/adr/0010-compose-ui-outside-linuxx64-rule.md)), so logic
that the CLI could want does not belong here.

```kotlin
val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
val sync = SyncControls(manager, scheduler) // app:sync, see app/desktop
scheduler.start(scope, store.notes.observeRevisionMark())
val app = AppViewModel(store.notes, ConflictResolver(store), scope, Dispatchers.IO, sync)
JotterApp(app) // in a window
// When the user closes it:
app.requestExit { exit() } // saves first, and asks if that fails
// After exit:
scope.coroutineContext.job.cancelAndJoin() // before store.close()
```

**Dependency rule:** depends on `core:storage`, `core:sync` and `app:sync`, and on
`core:sync`'s public API alone. Only `app/*` may depend on it. What the CLI shares,
such as the backend list, adding and removing backends and the sync scheduler, is in
`app:sync`.

## What is here

| Type | What it is |
|---|---|
| `AppViewModel` | The main public class: the list, the open editor, `refresh` and `requestExit`. Built by the app's composition root ([ADR-0005](../../docs/adr/0005-manual-dependency-injection.md)) |
| `SyncControls` | The backend manager and scheduler of `app:sync`, for `AppViewModel`. Without it the app shows no backend screen |
| `JotterApp` | The one public composable, which calls `refresh` whenever its window gains focus |
| `CannotOpenNotes` | What an app shows instead if the database could not be opened |
| `NotesViewModel`, `NotesScreen`, `NoteCard` | The grid, pinned first, with search, the active, archive and trash views, the tag filter, and pin, archive, trash, restore and delete forever (which asks first) |
| `EditorViewModel`, `EditorScreen` | The text editor with its Markdown preview, and adding and removing tags |
| `AttentionViewModel` | The notes that sync left something on, from `ConflictResolver`'s lists: open conflicts, hidden heads, text that does not count, and combined texts not yet saved |
| `ReviewViewModel`, `ReviewScreen`, `ReviewParts` | The review of one such note: what happened in plain words, every text in full, and the answers |
| `BackendsViewModel`, `BackendsScreen` | The backend screen: each backend with whether it can be reached now, when it last synced, its issues and where its password is kept; adding a folder or a WebDAV server (asking before setting one up, and offering the password file when no keyring answers); entering a new password after the server refused one; adding the PC hub; removing a backend; "Sync now" |
| `HubControls`, `HubViewModel`, `PeerHubControls` | Running the hub on this PC from the backend screen: on or off, its folder and address (loopback or Tailscale only, said plainly), pairing a device with its token shown once, and unpairing. `PeerHubControls` (JVM) is the `server` module's `PeerHub`; it adds the hub's folder as this PC's own local folder backend and keeps its settings, which hold no secret, in `$XDG_CONFIG_HOME/jotter/hub.properties` |
| `regionsOf` | Where two conflicting versions agree and where they differ, read back from core:sync's marked text |
| `parseMarkdown`, `MarkdownDocument` | The `markdown` package: a note's text as plain blocks and spans, with no Compose in it |
| `MarkdownPreview` | Those blocks on screen, with tappable task checkboxes |

Grid cards still show a note's text as it was typed: parsing every card on scroll is
not worth it for six lines.

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
- **Saving** is the Save button, Ctrl+S, Back, `requestExit`, and an autosave, all
  on the same path. The autosave runs 5 s after typing stops and at the latest
  60 s after the last save while typing goes on. So a crash loses up to 5 s of
  text after typing stopped, and up to a minute of unbroken typing. A failed
  autosave keeps the draft and shows the message like any other save, and tries
  again every 5 s until it works. It waits while a stale or deleted note needs the
  user's decision, because only that can resolve it. Each autosave is a full
  snapshot, kept for good, which is why it is not more frequent: a 20 kB note saved
  at every 10 s pause adds about 7 MB an hour.
  A note that this editor created and the user then emptied goes to the trash on
  Back or on closing. If the user types during that trash write, the note is
  restored. If restoring fails, the note stays in the trash with its text
  recoverable there, and the restore is not retried. An older note that is emptied
  stays, empty. Back and closing save
  until the draft is clean, so that text typed during the save is not lost with the
  editor, and "Saving…" shows meanwhile, up to 5 s if the database is locked. If
  saving fails, Back stays in the editor with the message showing, and closing asks
  whether to keep editing or close without saving. No note exists until it has
  text that is not blank, whatever tags the draft has.
- **Tags** are stored at once, not with the text: `addTag` and `removeTag` apply to
  the current revision and cannot lose text. The note they return does not become
  the editor's base, as it may hold someone else's text, which the next edit must
  still notice. A new note keeps its tags in the editor until its first save, and
  they alone do not create it.
  Invalid input shows a message and stays in the field.
- **Markdown** is JetBrains' `markdown` parser with its GFM flavour, on the injected
  `io` dispatcher, cancelled when the text changes. It supports headings, paragraphs,
  bold, italic, strikethrough, inline code, code blocks, links, lists, quotes, rules and
  task lists. A single line break stays a line break, unlike most Markdown, as notes
  from Keep are written in lines. Tables show as typed in a fixed font.
- **Nothing touches the network or opens by itself.** A link shows its URL after its
  text and does nothing when tapped. Raw HTML and images are plain text.
- **Tasks are changed by source offset.** The parser says where each box is, so
  lookalikes in code and duplicate items cannot be confused. A tap changes the one
  character between the brackets through `EditorViewModel.body`, which is typing as far as
  saves, autosaves and stale edits go. `[X]` counts as ticked, and clearing it and ticking it
  again leaves `[x]`. The offsets belong to the text that was parsed, so a tap on a document
  that is no longer the draft does nothing, and the new text shows a moment later. As the
  parser only knows `\n`, it reads a copy with other line ends replaced, and the offsets
  map back, so a note keeps its `\r\n`.
- **Odd input cannot hurt.** The parser's time grows with the square of the nesting, and
  a 1 MB line of `>` runs it out of memory. So a line that opens over 32 levels makes the
  whole note plain text before the parser sees it, and text nested deeper over several
  lines shows as typed. If the parser fails, the tree is too deep to walk or the parse runs
  out of memory, the note is plain text too. The last is a net, not a limit: the parse's
  memory is free again once it has unwound, but until then another thread can run out too.
- **The preview is one lazy list of rows**, whatever the nesting, so a 1 MB checklist costs
  only what is on screen. Blocks over 4 kB are split over several rows, because laying out
  1 MB in one `Text` stops the UI thread for 0.7 s.

  | Text | Characters | Parsing (median of 5, warmed up, JVM 21, Linux desktop) |
  |---|---|---|
  | A note of 100 lines | 5 k | 2 ms |
  | 1 MB, one paragraph | 1 M | 39 ms |
  | 1 MB of short paragraphs | 1 M | 460 ms |
  | 1 MB checklist | 1 M | 936 ms |
  | 1 MB table | 1 M | 1.6 s |

- **What sync leaves for the user.** `ConflictResolver` lists open conflicts,
  hidden heads (edits a note does not show yet) and text that does not count, and
  core:sync's README says the UI must show all three. So a note with any of them is
  marked on its card ("Needs attention: conflicting edits") and in the editor, and the
  "Needs attention" view, beside Notes, Archive and Trash, lists every one, also a note
  that has left the list because none of its text counts. Pins, archiving, tags and a
  deletion beside an edit merge by themselves (PLAN.md §5), so they show nothing.
  The review of a note shows, in plain words without sync's terms:

  | What core:sync reports | What the user sees | What they can do |
  |---|---|---|
  | An open conflict | Both versions in full, and where they differ in two colours | Keep the shown version, keep the other, keep both as separate notes, or combine by hand from core:sync's text with conflict markers |
  | A hidden head, `MISSING_ANCESTOR` | The shown version and the waiting edit, and that earlier changes have not arrived | The same four, through `settleHidden`, or wait for the next sync |
  | A hidden head, `OPEN_CONFLICT` | The waiting edit's text, which merges once the conflict is resolved | Nothing: `settleHidden` refuses it |
  | Text that does not count: `BAD_HISTORY`, `MALFORMED`, `ID_COLLISION` | The text, and why sync does not use it: another device's clock or software was wrong, it arrived damaged, or two versions of one change arrived | Save it as a new note, once: it then leaves the list. Or discard it, after a question that says it is only removed from this device's list |
  | A combined text whose conflict was answered elsewhere | The text, and that what it combined was dealt with | Save it as a new note, or discard it after a question |

  Each answer is one call to `ConflictResolver`, which writes one revision; the
  editor has no undo, so neither has this. Every text stays on screen until that
  call has worked. An answer names the version it was made on, and core:sync refuses
  it if the note shows another one by then, for example after an edit in the editor:
  a combined text then stays, marked, with the version now shown above it, and the
  next save is made on that version. Opening the review from the editor saves the
  editor's draft first, so that the review shows it.
- **Combined texts are kept** by core:sync in the local database (`combined_draft`), not
  synced: at once when combining starts, 1 s after typing pauses, on Back and on
  closing the app. They come back when the note is reviewed again, after a restart too,
  and the note stays under "Needs attention" meanwhile. They go when their answer is
  written, saved as a note or discarded. If keeping one fails, Back stays and closing
  the app asks first. A segment that could not be read is not about one note, so it
  shows on the backend screen, as an issue of that backend.
- **Conflict regions.** `ConflictDetails` gives the merged text with conflict
  markers, which core:merge makes for a person and not to be parsed. `regionsOf` reads
  it back all the same, as each line between markers comes from one version: if
  neither version has a line that looks like a marker, every marker line is one. If one
  does, the review shows the marked text as it is, and both versions in full as always.
  The marker labels are core:sync's, and `ReviewScreenTest` fails if they change.
- **The backend screen** ("Sync" in the header, PLAN.md §6.5). Adding a folder that
  is not a Jotter store yet asks first, and says what setting it up does and why a
  drive must be plugged in ([ADR-0012](../../docs/adr/0012-local-folder-publishing.md));
  one another device set up is simply added, and then syncs at once. Removing asks
  first and says that the files stay, where, and that other devices can go on syncing
  there. Each card says, in `app:sync`'s plain words, whether the backend can be
  reached when the screen last asked (on opening and after each sync), when it last
  synced ("Last synced 5 minutes ago", or "Never synced"), why the last try failed if it
  did, and the issues of the last sync, such as a damaged file. A missing folder or an
  empty mount point reads as such, never as empty or synced, beside the time of the
  last success. "Sync now" syncs every backend, also one waiting after a failure.
  Every failure, such as a locked database, shows as a message, and so does what
  keeps the scheduler from syncing (`SyncScheduler.problem`). A backend that the
  screen finds reachable again after a failed try syncs at once. The screen's
  coroutines are a child of the app's scope, which closing it leaves running.
- **Failures in the grid** show as a message: a locked database, a note deleted
  elsewhere, a failed read, and any other failed write. Each is kept until the next successful write
  or until dismissed. A failed read keeps what the list showed.

## Tests

All tests are in `jvmTest`, on a real store in a temporary directory.
`NotesViewModelTest` and the `Editor*Test` classes run everything on one test
dispatcher, on virtual time; `JotterAppTest` runs the real screens with `runComposeUiTest`, which
needs no display, and real IO threads, including the stale edit. `MarkdownParserTest` and
`TaskToggleTest` need no Compose, and the latter has the property test of the toggle;
`MarkdownPreviewTest` and `JotterPreviewTest` run the renderer and the editor's preview
with `runComposeUiTest`. `ReviewViewModelTest`
and `ReviewScreenTest` make every state with core:sync itself: `SyncWorld` syncs the app's
store with a second real database through `InMemoryBlobStore`, holds a file back from the
app's listing to make an edit wait, and writes a faulty device's segment by hand for text
that does not count. Nothing of the sync engine is mocked.
`twoDevicesConvergeOnWhatTheUserCombined` answers a conflict on screen and syncs, and both
devices then hold the same note and no conflict. `BackendsScreenTest` and
`TwoAppsEndToEndTest` use `SyncedDevice`, the desktop app's real sync stack (engine, backend
manager, scheduler) on a database of its own, with real dispatchers and real folders.
`TwoAppsEndToEndTest` shows two apps side by side: on the screen, one sets up a folder, the
other adds it, one writes a note that reaches the other, both edit the same line, and the
conflict appears under "Needs attention" on both and in the review. The tests for a
locked database hold a real lock, so each waits out the 5 s busy timeout. Android
host tests are disabled, as in `core:storage`.
