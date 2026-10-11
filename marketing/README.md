# Ink & Word launch material

Everything for the public launch: the landing page, the 72-second launch film, and the script that
makes the app screenshots with sketches in the margins. None of it is part of the app or its build.

Never use the word "AI" in launch material. The commentary is the *Ink & Word Commentary*
("Summarised from popular commentators") and the devotionals are *Ink & Word Reflections*
("Summarised from popular devotionals").

## landing-page/

A single-page site (`index.html`, images in `img/`). It is written as a claude.ai Artifact page, so
it has no `<!doctype>`, `<html>` or `<head>` of its own; wrap it in those before hosting it anywhere
else. The film section plays `inkandword-film.mp4`, which isn't committed (it is a build output, see
below).

Still placeholders: the sign-up form (it saves nothing yet), the contact address, the privacy policy
link, and the Google Play button ("Coming soon to Google Play").

## film/

The launch film as a [HyperFrames](https://github.com/heygen-com/hyperframes) project: HTML scenes
rendered to MP4. `BRIEF.md` and `STORYBOARD.md` describe it; `index.html` is the whole film (seven
scenes on one GSAP timeline); `assets/` holds the screenshots, fonts, logo and music.

Render it (needs Node 22+ and FFmpeg):

```bash
cd marketing/film
npx hyperframes@0.8.146 render --quality high --output renders/video.mp4
```

In a cloud session without Chrome, point it at Playwright's headless shell first:
`export HYPERFRAMES_BROWSER_PATH=$(ls -d /opt/pw-browsers/chromium_headless_shell-*/*/)headless_shell`.

The web copy for the landing page is a smaller encode of that render:

```bash
ffmpeg -i renders/video.mp4 -vf scale=1280:720 -c:v libx264 -preset slow -crf 26 \
  -pix_fmt yuv420p -movflags +faststart -c:a aac -b:a 128k ../landing-page/inkandword-film.mp4
```

Music: "Heartwarming" by Kevin MacLeod (incompetech.com), licensed under CC BY 4.0. The licence
needs the credit, which is on the film's end card and under the video on the landing page.

## screenshot-script/

`MarketingShots.kt` drives the real app (Robolectric, like the feature tests) and draws the margin
sketches with the app's own pen: John 3 (in stages, for the film), Psalm 23 and Isaiah 53. It is kept
out of `app/src/test` so the normal test run doesn't include it. To run it:

```bash
cp marketing/screenshot-script/MarketingShots.kt app/src/test/java/com/biblestudy/app/
./gradlew testDebugUnitTest --tests "com.biblestudy.app.MarketingShots"
rm app/src/test/java/com/biblestudy/app/MarketingShots.kt
```

The PNGs land in `app/build/marketing/`. Lettering is drawn in single strokes at least about 34 px
tall: in finger-drawing mode a stroke shorter than the touch slop counts as a tap and becomes a dot.
The other screenshots come from the feature tests (`app/build/screenshots/`).
