# What sync tells you

## The status of a backend

The **Sync** screen has a card for each backend. It shows the name and place, where its
password is kept, whether it can be reached now, when it last synced ("Last synced 5
minutes ago", or "Never synced."), why the last try failed if it did, and any issues.
`jotter backends status` prints the same.

`jotter sync` prints one line per backend, with one of these words:

| Word | What it means | What to do |
|---|---|---|
| `synced` | All done | Nothing |
| `incomplete` | Some files could not be read, so some changes have not arrived | Nothing: Jotter tries again soon |
| `unreachable` | The folder, server or hub could not be reached | Read the sentence after it. Plug in the drive, check the network, or start the PC's app. Jotter tries again by itself |
| `unauthorized` | The server refused the app password, or the hub refused the token | See below |
| `interrupted` | The sync stopped part way | Nothing: what was done is kept, and the next sync goes on |
| `incompatible` | The backend was set up by a newer Jotter | Update Jotter |
| `unsupported` | This version cannot use that kind of backend | Update Jotter |
| `failed` | Something went wrong on this device | Read the sentence after it, and [troubleshooting](troubleshooting.md) |

A backend that fails is tried again after 30 seconds, then less and less often, up to every
30 minutes. **Sync now** always tries at once.

**A refused password.** Make a new app password on the server. Then press **Enter new
password** on the card, type it, and press **Check and keep**. With the command:
`jotter backends password ID`. Jotter checks it with the server before it keeps it.

**A refused token** ("it was unpaired on the PC"). On the PC, pair this device again.
Then press **Enter new password** on the card and enter the new token, or use
`jotter backends password ID`.

## Issues

An issue is a problem with one file on the backend. It does not stop the rest.

| The issue says | What it means | What to do |
|---|---|---|
| File N of device X is damaged and was not read | A file on the backend is broken | [Recover it](backup.md#a-damaged-file-on-a-backend) from a device that still has those changes |
| ... was written by a newer version of Jotter | Another device has a newer Jotter | Update Jotter here |
| ... could not be read. It is tried again next time | A read failed, perhaps the network | Nothing |
| ... is missing from the backend | A file was deleted or lost | Notes that depend on it wait under **Needs attention**. A device that has it puts it back when it syncs |
| A change is too large to sync | One note is too big | Make the note shorter, or split it |
| A change from another device is dated too far in the future | That device's clock is wrong | Fix the clock on that device |

The damaged-file issue stays listed as long as the file is there, even after you
recovered its changes. Jotter never deletes files on a backend.

## Conflicts

If two devices change the same lines of a note before they sync, Jotter cannot know which
you want. It keeps both. The note's card says "Needs attention: conflicting edits", and a
**Needs attention** view appears beside **Trash**.

Press **Review**. Jotter shows both versions and where they differ. Then choose:

- **Keep the shown version** or **Keep the other version**.
- **Keep both as separate notes**.
- **Combine by hand**: a text box holds both versions between lines starting with
  `<<<<<<<`, `=======` and `>>>>>>>`. Edit it into what you want, remove those lines, and
  press **Save combined text**. Jotter keeps your half-done text if you leave.

Your answer syncs to the other devices, and the note stops needing attention everywhere.
Changes to different lines, tags, pins, archive and trash are merged by themselves and
never ask.

**Other things under Needs attention:**

- **An edit is waiting**: an edit arrived before the changes it builds on. The same four
  choices work, or wait for the next sync.
- **An edit waits for the conflict**: answer the conflict first.
- **Text set aside**: text from another device that Jotter does not use, because that
  device's clock or software was wrong, or the text arrived damaged. **Save as a new note**
  keeps it, or **Discard** removes it from this device's list.

The command lists these with `jotter conflicts`, and `jotter conflicts ID` prints every
version of a note. Answering is done in the app.

## Checked

A conflict was made by editing one line on the desktop app and on the Android emulator
while apart, then combined by hand on the desktop; the phone then showed the combined text.
Unplugged and empty folders, a refused WebDAV password and an unpaired hub were made and
fixed with the command. "An edit is waiting" and "Text set aside" were not made for this
guide.
