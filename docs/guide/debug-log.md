# The debug log

When something goes wrong, the debug log helps the developers find out why. Jotter never
sends it anywhere: you save it to a file and attach it to a report yourself.

## What is in it

The versions of Jotter, Java or Android, each backend's kind, last sync and issues, and
the newest lines of Jotter's logs. It holds no note text, title, tag, password or token.
Server addresses are cut down to the server's name and port, and your home folder shows as
`~`.

It can still show the paths of folders you sync with, such as a USB stick. Jotter shows
you the whole log before you save it: read it, and remove anything you do not want to
share.

## Save it

**Desktop:** **Sync** → **Debug log** → **Save to a file…**. Choose a folder and a new file
name, and press **Save**. Jotter never overwrites a file. If your notes cannot be opened at
all, the window that says so has **Show debug log**.

**Android:** **Sync** → **Debug log** → **Save…**. Android's file picker opens. Choose a
place, such as **Downloads**, and press **Save**. Jotter says "Saved the debug log. Attach
that file to your issue."

**Command:**

```
jotter debug-log --output ~/jotter-debug.txt
```

It writes a new file and refuses to overwrite one. `jotter debug-log` alone prints it. It
works even when your notes cannot be opened.

## Send it

1. Open a new issue on
   [github.com/sameerkrmishra/jotter/issues](https://github.com/sameerkrmishra/jotter/issues).
2. Say what you did, what you expected, and what happened. Copy any message exactly.
3. Attach the debug log file.

## More detail

To log more for one run of the command or the desktop app, start it with
`JOTTER_LOG_LEVEL=debug`, for example `JOTTER_LOG_LEVEL=debug jotter sync`. Then save the
debug log again.

On Linux the logs themselves are in `~/.local/state/jotter/logs/` (`cli.log` and
`desktop.log`). Each is kept small: older lines are dropped by themselves.

## Checked

All three ways to save the log were run: the command, the desktop app's file dialog, and
Android's file picker on the emulator.
