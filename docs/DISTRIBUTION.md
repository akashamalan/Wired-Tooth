# Shipping Luma

How to turn the source into something you can hand to a person who has never
installed a developer tool.

The app is **Luma**. The repository, projects and namespaces stay `WiredTooth`
— see `WiredTooth.Windows/Brand.cs` for why.

---

## Windows

### Build the .exe

```
dotnet publish WiredTooth.Windows\WiredTooth.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true -o dist
```

Then rename `dist\WiredTooth.Windows.exe` to `dist\Luma.exe`. Renaming a
self-contained single-file build is safe — everything it needs is inside it.

**Result: one 72 MB file.** That is the whole app.

### Why 72 MB, and the smaller option

`--self-contained true` bundles the entire .NET runtime and WPF inside the
executable, so it runs on a machine that has never had .NET installed. That is
what makes it sendable to a stranger, and the 72 MB is what it costs.

The alternative is framework-dependent:

```
dotnet publish WiredTooth.Windows\WiredTooth.Windows.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true -o dist-small
```

About 2 MB, but the recipient must first install the **.NET 10 Desktop
Runtime**, and if they don't, the app dies on launch with a dialog most people
cannot act on. Use the 72 MB one for anybody who isn't you.

### Sending it

There is no installer, and for a single self-contained .exe there doesn't need
to be. Put `Luma.exe` anywhere and double-click it.

- **WhatsApp, Telegram, Google Drive, a USB stick** — all fine.
- **Email will reject it.** Gmail and Outlook block `.exe` attachments outright,
  including inside a plain `.zip`. Use a Drive/Dropbox link instead.

### What the recipient will see

**SmartScreen will block it on first run.** The build is unsigned, so Windows
shows "Windows protected your PC" and only offers a *Don't run* button. The
real one is hidden behind **More info → Run anyway**. Tell people this in
advance, because it looks exactly like a virus warning.

The only way to remove that is an **Authenticode code-signing certificate**
(roughly $200–400/year from a CA, and an EV certificate to clear SmartScreen
reputation immediately). Worth it if this ever ships publicly; not worth it to
show a friend.

**Windows Firewall will prompt** the first time it listens. They must click
Allow, on **Private networks**. If they miss it, Settings → *If it isn't
working* → Run checks names the problem and prints the exact `netsh` command to
fix it.

### If you later want a real installer

An `.exe` that puts Luma in Program Files, adds a Start menu entry and an
uninstaller needs a packaging tool — **Inno Setup** (free, one script) is the
usual answer, or **MSIX** if you want Store-style install/uninstall. Neither
changes the app; they wrap the file above. Not needed to try it out.

---

## Android

### Build the APK

```
cd AndroidReceiver
.\gradlew.bat :app:assembleDebug
```

Output: `AndroidReceiver\app\build\outputs\apk\debug\app-debug.apk`, about
6.8 MB. A copy is placed at `dist\Luma.apk`.

### Installing it

**By cable:**

```
adb install -r dist\Luma.apk
```

**Without a cable:** send the `.apk` to the phone (Drive, WhatsApp, email —
Android APKs are not blocked the way `.exe` is), open it from the Files app, and
approve **Install unknown apps** for whichever app you opened it from. Android
asks once per source app.

### Debug vs release, and why this one is a debug build

This APK is signed with the **debug keystore**, which is generated
automatically and identical on every developer machine. That is fine for trying
it out and is why it can be built with no setup.

It is **not** fine for distribution:

- Google Play refuses debug-signed uploads.
- A debug key is not yours in any meaningful sense, so you cannot prove an
  update came from you.
- Once people have installed a build signed with one key, an update signed with
  a different key **will not install** — they have to uninstall first, losing
  their saved devices.

A release build needs a keystore you create and keep:

```
keytool -genkey -v -keystore luma-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias luma
```

Then wire `signingConfigs` into `app/build.gradle.kts` and run
`.\gradlew.bat :app:assembleRelease`. **Back the keystore up somewhere you will
still have it in five years.** Lose it and you can never update the app for
anyone who installed it — there is no recovery, by design.

Do that when you are ready to publish. Until then the debug APK is the right
tool.

---

## iOS

Not covered here. iOS has no sideloadable equivalent of an APK: distribution
means TestFlight or the App Store, both of which need a paid Apple Developer
account and a Mac. Building to your own device from Xcode works today and is
what the existing app does.

---

## What each artifact is

| File | Size | Needs | Who it's for |
|---|---|---|---|
| `dist/Luma.exe` | 72 MB | Nothing | Anyone on Windows 10/11 x64 |
| `dist/Luma.apk` | 6.8 MB | Android 8.0+ | Trying it out, not publishing |
| iOS build | — | Xcode + Mac | You |
