# Install Jotter

Every release is on the [Releases page](https://github.com/sameerkrmishra/jotter/releases).
It has three files you might want, and a list of checksums:

| File | What it is |
|---|---|
| `Jotter-X.Y.Z-x86_64.AppImage` | The Linux desktop app, with the `jotter` command inside. Needs nothing else |
| `jotter-cli-X.Y.Z.zip` | The `jotter` command alone. Needs Java 21 or newer. Starts faster |
| `jotter-X.Y.Z.apk` | The Android app, for Android 8.0 and newer |
| `SHA256SUMS` | Checksums of the files above |

`X.Y.Z` is the version number, such as `1.0.0`.

## Check the download (optional)

Put `SHA256SUMS` in the same folder as the file you downloaded, then run:

```
sha256sum -c --ignore-missing SHA256SUMS
```

Each file you have should say `OK`.

## Linux desktop: the AppImage

1. Make a folder for it and move the file there:

   ```
   mkdir -p ~/Applications
   mv ~/Downloads/Jotter-1.0.0-x86_64.AppImage ~/Applications/
   ```

2. Allow it to run:

   ```
   chmod +x ~/Applications/Jotter-1.0.0-x86_64.AppImage
   ```

3. Start it by double-clicking it in your file manager, or:

   ```
   ~/Applications/Jotter-1.0.0-x86_64.AppImage
   ```

To put Jotter in your app menu, use a tool such as Gear Lever or AppImageLauncher.
(Not tested for this guide.)

**The `jotter` command from the AppImage.** Make a link named `jotter` that points at
the AppImage:

```
mkdir -p ~/.local/bin
ln -s ~/Applications/Jotter-1.0.0-x86_64.AppImage ~/.local/bin/jotter
jotter list
```

Started under the name `jotter`, the AppImage acts as the command, not the app. You can
also run the command without a link: `~/Applications/Jotter-1.0.0-x86_64.AppImage cli list`.

If your terminal says `jotter: command not found`, `~/.local/bin` is not on your `PATH`.
Many systems add it when you log in again.

**Updating.** Download the new AppImage and point the link at it:

```
ln -sf ~/Applications/Jotter-1.1.0-x86_64.AppImage ~/.local/bin/jotter
```

**If it does not start.** AppImages need FUSE. Without it, start it with
`--appimage-extract-and-run` after its name.

**Passwords.** To keep server passwords in your desktop's keyring, Jotter needs the
`secret-tool` program: the package `libsecret` on Arch and Fedora, `libsecret-tools` on
Debian and Ubuntu. Without it, Jotter offers to keep them in a file instead.

## Linux: the command alone (zip)

Use this if you have Java 21 or newer (`java -version` tells you) and want the command to
start quickly. The zip unpacks into a folder named `jotter`:

```
mkdir -p ~/Applications ~/.local/bin
cd ~/Applications
unzip ~/Downloads/jotter-cli-1.0.0.zip
ln -s ~/Applications/jotter/bin/jotter ~/.local/bin/jotter
jotter --help
```

The command and the desktop app use the same notes file, so you can use both.

## Android

1. On your phone, open the Releases page and download `jotter-X.Y.Z.apk`.
2. Open the downloaded file. Android asks you to allow your browser or file manager to
   install apps. Allow it, then tap **Install**.
3. Open Jotter from your app list.

Jotter will also be on F-Droid later, which keeps it up to date for you.

Not tested for this guide: installing from a phone's browser or file manager. The app
itself was installed on an emulator with Android's developer tools, and no signed APK
existed yet.
