# Working on this app

- **Always update the docs with every change.** When a feature is added, changed or removed, update all three in the same commit:
  1. the in-app Help guide (`app/src/main/assets/help/guide.md`), which explains every feature simply;
  2. `README.md` ("New in version …" and known gaps);
  3. the requirements document (artifact).
- Keep the UI uncluttered: no new toolbar buttons without asking; tell the user when a screen is getting crowded.
- Easy to use for lay users: the simplest behaviour is the default; extra power goes behind a setting or a menu, not in the way.
- Each version is built and shipped as one release (version bump, tests, signed APK).
- Book introductions and study content give the traditional view only.
- Run the unit tests (`./gradlew testDebugUnitTest`) before pushing.
