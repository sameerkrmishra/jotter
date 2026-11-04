# Write notes

The desktop app and the Android app look and work the same. The main screen has a
**Search** box, **New note**, **Sync**, and the views **Notes**, **Archive** and **Trash**.
The first time, the list says "Nothing here."

## Write a note

1. Tap or click **New note**.
2. Type a **Title** if you want one, and your text under **Note**.
3. Go **Back**. Your note is saved.

Jotter also saves by itself 5 seconds after you stop typing. On the desktop, Ctrl+S or
**Save** saves at once. When the note is saved, **Save** is greyed out.

If you empty a new note and go back, it goes to the trash.

On Android you can also share text from another app: choose **Share**, then **Save to
Jotter**. Jotter says "Saved as a new note in Jotter." It takes text only, not pictures or
files.

## Markdown and tasks

Notes are plain text. Jotter shows Markdown when you tap **Preview**, and **Edit** goes
back. It understands headings (`# Title`), **bold** (`**bold**`), *italic* (`*italic*`),
lists, quotes, code, links and task lists. One line break stays a line break.

A task list is a list of lines like these:

```
- [ ] eggs
- [x] bread
```

In **Preview**, tap a box to tick it or untick it. That changes the `[ ]` or `[x]` in the
text and saves like any edit. Links show their address but do not open.

## Tags

In a note, type a tag under **New tag** and tap **Add tag**. Tap a tag with `×` to remove
it. Tags show on the cards, and as buttons above the list, such as `home (2)`: tap one to
see only notes with that tag.

## Search

Type at least two letters in **Search**. Jotter shows notes that contain all your words,
and the last word may be the start of a longer word, so `gro` finds "Groceries". Search
looks in the view you are in (Notes, Archive or Trash) and the tag you picked.

## Pin, archive and trash

Each card has buttons:

- **Pin** keeps a note at the top. **Unpin** undoes it.
- **Archive** moves a note out of the way into the **Archive** view. There, **Restore**
  brings it back.
- **Trash** moves it into the **Trash** view. There, **Restore** brings it back, and
  **Delete forever** removes it after asking "Delete this note forever?". This cannot be
  undone.

Jotter never empties the trash by itself.

## Checked

On the desktop app (main branch, 2026-11-04) and on an Android 16 emulator: writing a
note, a title, a tag, ticking a task in Preview, archive, restore, trash, and sharing text
from another app. On a very narrow phone screen (320 dp wide), **Add tag** was squeezed
to the edge and the tags of the open note did not show; adding worked.
