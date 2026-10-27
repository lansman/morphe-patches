# Focused local-download checks

Run from any working directory with Python 3.9+ and a JDK 17+ on `PATH`:

```sh
python3 tools/local-download-checks/run.py
```

On Windows, `py tools/local-download-checks/run.py` is also suitable. The runner uses argument lists rather than shell wildcards, locates production sources relative to this directory, compiles with the Java 17 target, and creates/deletes all class files in an external temporary directory. It needs no Android SDK, Gradle, network, credentials or connected phone.

The checks compile the real `LocalVideoWatchProgress`, `LocalVideoDownloadPreferences` and `LocalVideoQuality` classes. Test-only `android.content` stubs provide the small preferences API used by them. The stubs live outside all Android source sets and must never be packaged into an APK. Unexpected API use fails instead of returning a misleading default.

Current coverage is **38 assertions**:

- Progress: 22 assertions for complete short playback, the final completion interval, seeking to the end without actual coverage, repeated playback without double counting, paused seeking, suspension, persistence/resume, the 95% threshold, changed media duration, invalid/unprepared player state, corrupt preferences, deletion cleanup and invalid identifiers.
- Quality policy: 16 assertions for asking by default, explicit remembered selection, restoring the picker, persistence across contexts, atomic preference updates, invalid/mistyped heights or booleans, and available-stream fallback.

These checks protect the policy used to nominate watched videos for deletion; they do not delete files. They do not replace Android UI checks of the confirmation selection, video playback, lifecycle callbacks, transfer integrity or actual file deletion. Only disposable media fixtures should be used for destructive device checks.
