# Spike 0001: SQLite FTS5 through SQLDelight on Android and the JVM

- **Date:** 2026-10-22
- **Decides:** [ADR-0002](../adr/0002-sqlite-via-sqldelight.md)
- **Spike code:** branch `spike/fts5-code`, module `spike/fts5` (not merged)

## Questions

1. Does SQLDelight 2.x accept `CREATE VIRTUAL TABLE ... USING fts5` and generate
   usable queries for `MATCH`, `bm25()`, `rank`, `snippet()` and `highlight()`?
   Which dialect is needed?
2. Does the JVM driver (xerial `sqlite-jdbc`) include FTS5?
3. What is the cleanest way to use a bundled SQLite with FTS5 from SQLDelight on
   Android?
4. Does it work on a real Android runtime (not Robolectric, which uses the host
   SQLite)?

## Setup

| Component | Version |
|---|---|
| SQLDelight (Gradle plugin, runtime, drivers) | 2.4.1 (latest stable, 2026-10-06) |
| xerial `sqlite-jdbc` (via `app.cash.sqldelight:sqlite-driver`) | 3.53.4.0 |
| `androidx.sqlite:sqlite-bundled` | 2.7.1 (SQLite 3.50.1) |
| `com.eygraber:sqldelight-androidx-driver` | 0.2.2 (MIT) |
| `com.github.requery:sqlite-android` (JitPack) | 3.49.0 |
| Kotlin / AGP / Gradle | 2.4.21 / 9.4.1 / 9.8.2 (the repo's build) |
| Emulators | `system-images;android-26;default;x86_64` r1, `android-36;default;x86_64` r2, emulator 37.2.12, KVM, headless |

The module uses the real `jotter.kmp-library` convention plugin plus
`app.cash.sqldelight`. The plugin works with the Android-KMP library plugin
and AGP 9 without changes.

The schema is the shape `core/storage` would use: a `note` table and an
external-content FTS5 index `note_fts(title, body, content='note',
content_rowid='id', tokenize='unicode61 remove_diacritics 2')`, kept in sync by
`AFTER INSERT/UPDATE/DELETE` triggers. One shared scenario
(`Fts5Checks.kt`, `runFts5Checks(driver)`) runs on every driver and checks:
diacritic folding (`cafe` finds `café`), `bm25()` with column weights,
`highlight()`, `snippet()`, prefix (`groc*`), phrase and column-filter queries,
`col MATCH ?`, the `rank` column, index updates through the triggers,
`'rebuild'` and `'integrity-check'`, and that malformed `MATCH` input throws.

## Results

### 1. SQLDelight dialect: works, with two codegen bugs and one gotcha

The default dialect (`sqlite-3-18`) already parses FTS5 virtual tables,
`MATCH`, `bm25()`, `snippet()` and `highlight()`. `sqlite-3-38-dialect` and
`sqlite-3-44-dialect` behave the same; all JVM tests pass with 3.44. No extra
module is needed. Typed results are generated, for example
`search(query: String, limit: Long): Query<Search>` with
`bodySnippet: String?` and `score: Double`.

SQLDelight 2.4.1 crashes at generation time in the first two cases. The third
is a correct error, but easy to hit:

| Statement | Failure | Workaround |
|---|---|---|
| `SELECT rowid FROM note_fts ...` or `SELECT rank ...` (unaliased) | `IllegalStateException: Cannot get name for type SqlCreateVirtualTableStmtImpl` | Alias: `rowid AS id`, `rank AS rank` |
| Labeled `INSERT INTO note_fts(note_fts) VALUES ('rebuild')` (FTS5 commands) | `ClassCastException: SqlModuleColumnDefImpl cannot be cast to ColumnDefMixin` | Run via `driver.execute(...)`. The same statement inside a trigger compiles fine |
| Unqualified column in `MATCH` on a join (`WHERE title MATCH ?`) | `Multiple columns found with name title` (a correct error) | Qualify: `note_fts.title MATCH ?` |

The two crashes are small and contained. They should be reported upstream.

### 2. JVM: xerial has FTS5

`JvmFts5Test` (`./gradlew :spike:fts5:jvmTest`), 3 tests, 0 failures:

```
FTS5SPIKE jvm/xerial: Fts5Report(sqliteVersion=3.53.4, fts5CompileOption=1, ...)
FTS5SPIKE jvm/bundled+eygraber: Fts5Report(sqliteVersion=3.50.1, fts5CompileOption=1, ...)
FTS5SPIKE jvm/bundled+own-adapter: Fts5Report(sqliteVersion=3.50.1, fts5CompileOption=1, ...)
```

The same tests also pass with synchronous generation (`generateAsync = false`),
the mode M1 intends: `./gradlew :spike:fts5:jvmTest -Pspike.sync` runs
`JvmSyncFts5Test`, 2 tests (xerial, bundled + own adapter), 0 failures. The
eygraber driver cannot run in this mode.

`sqlite-bundled-jvm` 2.7.1 (from Google Maven, not Maven Central) ships natives
for linux x64, linux arm64, macOS arm64 and windows x64, about 1.8 to 1.9 MB
each uncompressed (3.8 MB jar). So the same SQLite build runs on Android and
the desktop. The desktop jar carries all four unless the build strips the
unused ones.

### 3. Android options

| Option | Result | Maintenance | F-Droid | Size (per ABI) |
|---|---|---|---|---|
| Platform SQLite via `AndroidSqliteDriver` | **Fails** on API 26 and 36: `no such module: fts5` | n/a | n/a | 0 |
| androidx `sqlite-bundled` + `com.eygraber:sqldelight-androidx-driver` | Passes | Bundled: Google, androidx, Apache-2.0. Adapter: one maintainer, 0.x, active (pushed 2026-10-20), Maven Central | Expected to be acceptable (caveat below) | ~1.25 MB `.so`, ~0.63 MB compressed |
| androidx `sqlite-bundled` + our own `SqlDriver` adapter | Passes | Ours. 109 lines in the spike (`ConnectionSqlDriver.kt`) | Expected to be acceptable (same caveat) | same |
| requery `sqlite-android` + `AndroidSqliteDriver(factory = RequerySQLiteOpenHelperFactory())` | Passes (omits `sqlite_compileoption_used`) | Last release 3.49.0 on 2025-05-29. **JitPack only** | Weak: JitPack is not a trusted repository | ~1.84 MB `.so` (arm64) |

There is no official SQLDelight driver for the androidx `SQLiteDriver` API in
2.4.1. The eygraber adapter only accepts an **async** schema
(`SqlSchema<QueryResult.AsyncValue<Unit>>`), so it forces
`generateAsync = true` and suspend calls everywhere. Async-generated code still
runs on synchronous drivers (xerial, our adapter, `AndroidSqliteDriver` via
`Schema.synchronous()`), which the tests confirm.

All options support minSdk 26.

F-Droid caveat: the `sqlite-bundled` AAR contains prebuilt `.so` files that
F-Droid does not rebuild from source. It is expected to be acceptable because
androidx on Google Maven is a repository F-Droid trusts; confirm at the first
F-Droid build.

### 4. Proven on a real Android runtime

`AndroidFts5Test` (`./gradlew :spike:fts5:connectedAndroidDeviceTest`) ran on
headless x86_64 emulators (KVM, `-gpu swiftshader_indirect`), API 26 (Android
8.0) and API 36: 4 tests, 0 failures on each. No physical device was attached
or used. Logcat excerpt (API 26; API 36 is identical apart from the error code
text):

```
API 26: bundled+own-adapter: Fts5Report(sqliteVersion=3.50.1, fts5CompileOption=1, ...)
API 26: bundled+eygraber: Fts5Report(sqliteVersion=3.50.1, fts5CompileOption=1, ...)
API 26: requery: Fts5Report(sqliteVersion=3.49.0, fts5CompileOption=-1, ...)
API 26: framework: Failure(android.database.sqlite.SQLiteException: no such module: fts5 (code 1))
```

### 5. Room KMP

Not needed. SQLDelight on Android is not awkward: only a driver adapter is
missing. Room would not help with FTS5 anyway: its `@Fts3`/`@Fts4` annotations
do not cover FTS5, so the FTS5 table and queries would be raw SQL outside
Room's checks.

## Recommendation

Keep ADR-0002 and accept it, with the driver choice made concrete:

- **One SQLite build everywhere:** androidx `sqlite-bundled` on Android *and*
  the JVM, so search behaves the same on every device.
- **Our own small synchronous `SqlDriver` adapter** over androidx
  `SQLiteConnection` in `core/storage`, tested on the JVM under the coverage
  gate, with synchronous generation (tested above). It avoids a 0.x
  single-maintainer dependency. The eygraber driver is the fallback if we
  need connection pooling, but switching to it means async generation and
  `suspend` at every call site, so it is a migration, not a drop-in swap. The
  ADR states the adapter's M1 scope and the trigger for switching.
- **WAL and `busy_timeout`** on every connection, because the desktop app and
  the CLI can open the same file. Not tested in the spike: its adapter uses
  `BEGIN IMMEDIATE` without `busy_timeout`, so a second writer would get
  `SQLITE_BUSY` at once. M1 adds a two-connection (ideally two-process) test.
- **Dialect:** `app.cash.sqldelight:sqlite-3-44-dialect` (the newest; both
  bundles are SQLite 3.50+).
- xerial `sqlite-jdbc` stays usable for JVM-only tools, but is not the main path.
- Rejected: platform SQLite (no FTS5), requery (stale, JitPack only).

## Risks

- **SQLDelight FTS5 codegen bugs** (table above). Mitigation: aliases and
  driver-level FTS5 commands, written down in `core/storage`'s README. Report
  upstream.
- **Adapter correctness:** the spike adapter has no lock and no statement
  cache. ADR-0002 lists what the M1 adapter must cover and test.
- **SQLite update lag:** the bundled SQLite follows androidx releases (3.50.1
  in 2.7.1, while xerial is at 3.53.4). We must watch androidx releases for
  SQLite security fixes.
- **Size:** about 0.6 MB compressed per ABI. Fine for a notes app. F-Droid
  builds a universal APK with all four ABIs unless the app build configures
  ABI splits. Shipping one ABI per APK needs ABI splits in our build.
- **User input in `MATCH`** is FTS5 query syntax, and a malformed query throws.
  The search layer must quote or escape user text.
