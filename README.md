# Bible Study — version 0.2

A personal, fully offline Bible study app for Android tablets, designed around the Samsung Galaxy Tab S9 and S Pen. It works on any Android 10+ tablet.

## Install on your tablet (about 30 minutes the first time)

1. **Install Android Studio** on a Windows, Mac or Linux computer: https://developer.android.com/studio. Accept the defaults; it installs the Android SDK for you.
2. **Open this project.** Unzip `BibleStudy.zip`, start Android Studio, choose **Open**, and select the `BibleStudy` folder. The first sync downloads Gradle and the libraries, which takes 5–10 minutes. If Android Studio offers to upgrade the Android Gradle Plugin, you can say *Not now*. The project builds as it is.
3. **Turn on developer mode on the Tab S9.**
   - Go to *Settings → About tablet → Software information* and tap **Build number** 7 times.
   - Then go to *Settings → Developer options* and turn on **USB debugging**.
4. **Connect the tablet** with a USB-C cable and tap **Allow** on the tablet's "Allow USB debugging?" prompt.
5. In Android Studio, pick your tablet in the device dropdown at the top and press the green **Run ▶** button. The app installs and opens. It stays installed like any other app.

**Build an APK file instead.** Use *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The file appears in `app/build/outputs/apk/debug/`. Copy it to the tablet and open it; Android will ask you to allow installing from that source.

## Building and updating the app (signing key)

Android only installs an update over an installed app if both are signed with the **same key**. This project ships a test signing key in the repository, so every build from any computer or cloud session is signed the same way and installs over the previous one, keeping your notes.

| File | What it is |
| --- | --- |
| `signing/biblestudy-release.jks` | The signing key (alias `biblestudy`) |
| `keystore.properties` | Its location and password, read automatically by the build |

> **This key is public.** It is committed to a public repository on purpose, because the app is only for testing. Anyone could sign an app that Android accepts as an update to yours. Don't use this key for anything you rely on. Before real use, make a new private key (see the last section) and reinstall once.

### Build a signed APK

On a computer with Android Studio or the Android SDK and JDK 17+:

```
./gradlew assembleRelease
```

The signed file appears at `app/build/outputs/apk/release/app-release.apk`. Nothing else to set up: Gradle finds `keystore.properties` on its own. If the build prints "No release signing key found", that file is missing and the APK is signed with a throwaway debug key instead. Don't install that one over your real copy.

In Android Studio you can also use *Build → Generate Signed App Bundle / APK → APK*, choose *Choose existing…*, and pick `signing/biblestudy-release.jks` with the password from `keystore.properties`.

### Install or update on the tablet

1. Copy `app-release.apk` to the tablet (USB cable, cloud drive or email) and tap it in *My Files*.
2. If Android warns about unknown sources, allow it for the app you opened the file from, go back, and tap **Install**.
3. To update, build a new APK the same way, with a higher `versionCode` in `app/build.gradle.kts`, and install it over the old one. Your notes stay.

If Android says the app conflicts with an existing package, the installed copy was signed with a different key. Back up first (*⋮ → Back up my notes…*), uninstall, install the new APK, then restore.

### Check which key an APK uses

```
apksigner verify --print-certs app-release.apk
```

For this key the line should read `CN=Bible Study, O=Personal`, with SHA-256 `70f5af93762f26149c355e7a39cd61f653afa5c17a2f7405ad475fea749efc32`. `CN=Android Debug` means the wrong key was used.

### Cloud builds without the files

If you ever remove the key files from the repository, set these environment variables instead: `BIBLESTUDY_KEYSTORE_BASE64` (the `.jks` file as base64, from `base64 -w0 signing/biblestudy-release.jks`), `BIBLESTUDY_KEYSTORE_PASSWORD`, and optionally `BIBLESTUDY_KEY_ALIAS` (default `biblestudy`).

### Switching to a private key later

```
keytool -genkeypair -storetype PKCS12 -keystore signing/biblestudy-release.jks \
  -alias biblestudy -keyalg RSA -keysize 4096 -validity 36500
```

Then update `keystore.properties` with the new password, add `keystore.properties`, `signing/` and `*.jks` back to `.gitignore`, and remove the old key from git. Because this changes the signing key, you must back up, uninstall and reinstall once. Keep a copy of the new key and its password somewhere safe; if you lose them you can't update the app without uninstalling it.

## New in version 0.2

- **Continuous scrolling.** Keep scrolling past the end of a chapter into the next one (or back into the previous one). The chapter name at the top follows along. A flick keeps the page gliding. The arrows and the book picker still jump straight to the start of a chapter.
- **Lasso.** Pick *Lasso* and draw a loop around ink, highlights or images to select them. Then:
  - drag inside the dashed box with the pen to move the selection;
  - use the bar at the top of the page to recolour, *Copy*, *Move to layer* or *Delete*;
  - tap *Done* or touch the pen outside the box to finish.
  Highlights can be recoloured, moved to a layer or deleted, but they stay on their words. Everything can be undone.
- **S Pen side button.** Hold the button while the pen touches the screen to erase, whatever tool is picked. In *⋮ → Pen button* you can make it the lasso instead, or turn it off.
- **Resizable margins.** Drag the small grip on the line between the text and a margin to make the margin wider or narrower. Widths are remembered separately for landscape and portrait.
- **Smoother pen.** The stroke you are drawing is drawn on its own layer, so each new pen point no longer redraws the whole chapter underneath.

## What's in version 0.1

**Bible and navigation**
- The King James Version is built in, and the app works with no internet connection.
- Move with the previous and next chapter arrows, or tap the book name to open a book and chapter picker.

**Study Layout**
- The text is always laid out the same way. Zooming (pinch, or *Fit width*) only changes the size, so ink never slides off the words it was drawn on.

**Drawing and highlighting**
- The S Pen draws.
- Fingers scroll, pinch to zoom, and tap a verse.
- Palm rejection ignores touches right after the pen is used and lets the pen take over if your palm is already resting on the screen.
- Pen, highlighter and eraser tools, each with colours and three sizes.
- *Snap to words* turns a highlighter swipe into a clean highlight over whole words. Turn it off to highlight freehand.

**Margins**
- The right margin is on by default. The left margin can be switched on.
- Anything written or drawn in a margin is attached to its verse and appears in every Bible version.
- Ink drawn on the words belongs to the version you drew it on.

**Images**
- Put a picture from your gallery into a margin.
- Use the *Select* tool to move or resize it, or to delete it.

**Layers**
- Layers are global: showing, hiding or locking a layer applies on every page.
- You can add, rename, reorder and delete layers.
- New ink goes on the selected layer.

**Other features**
- Undo and redo.
- Autosave: every stroke is saved the moment you lift the pen.
- Tapping a verse with your finger opens a typed note (shared across versions), a bookmark button, and cross-references from OpenBible.info. Tapping a cross-reference jumps to it; with split view on, you can open it in the other panel.
- Search:
  - Several words match verses containing all of them.
  - Put text in quotes to search for an exact phrase.
  - Use OR to match either word, and `lov*` to match word beginnings.
  - Filter by whole Bible, Old Testament, New Testament, or the current book.
  - Typing a reference such as `jn 3:16` or `1 cor 13` offers a "Go to" button.
- Split view shows two passages at once. Panels sit side by side in landscape and stacked in portrait, and the divider can be dragged.
- Light, sepia and dark page themes.
- Back up everything (ink, highlights, images, notes, layers, bookmarks) to a single .zip file, and restore it on this tablet or a new one.

## Known limits in 0.2

- Only the KJV is included. NIV and NLT are waiting on licensing.
- The eraser removes whole strokes; there is no partial erasing.
- The lasso moves and recolours but can't resize or rotate a selection yet.
- Pen rendering still uses standard Android drawing rather than the front-buffered ink engine Samsung Notes uses, so it can lag the pen tip slightly.

## Next milestones

- **0.3:** Import NIV and NLT once licensing is sorted, link the scrolling of split panels, front-buffered (lowest-latency) ink, and lasso resize.
- **0.4:** Strong's numbers and a lexicon (STEPBible data), handwriting search, and sketch pages.

## Credits

- King James Version (1769): public domain. Text from the scrollmapper/bible_databases project (MIT).
- Cross-references: OpenBible.info, CC BY 4.0.
- Font: Gentium Book Plus © SIL International, SIL Open Font License 1.1 (see `licenses/Gentium-OFL.txt`).
