# Local download migration

The Downloads patch adds a separate **Downloaded videos** launcher entry. Its library menu can pin a home-screen shortcut. The existing settings entry remains available. Reopening the launcher refreshes the list without creating another player dialog.

The optional **Prepare old downloads for transfer** action is a preparation tool. It reads the app-owned native downloads key in memory, verifies/decrypts the legacy Exo cache index, and stages decrypted streams and typed metadata in the app-private external `MorpheNativeMigration` folder. It never exports the AES key or deletes the originals. Preparing files alone does not import videos.

For a controlled migration, copy the complete generated staging folder to a private location outside this repository, then run with Python 3.11+ and FFmpeg/ffprobe on PATH:

```sh
python3 probe_staged.py /private/path/to/staging
python3 mux_staged.py /private/path/to/staging
```

The tools verify staged hashes and declared lengths, copy audio/video into MP4 without re-encoding, decode samples at the beginning/middle/end, and create a preview plus the local library JSON. Inspect `migration-results-private.json` and import only `verified` results. Publish MP4 and preview before JSON; verify transferred hashes. Preserve existing finished files and partial downloads. Runtime data, filenames, titles, channels and cache keys remain private and must not be committed.

After import, test the actual Android player and seeking before removing staging or old cache. Decoder support depends on the device: a successful desktop decode alone is insufficient. Do not uninstall or clear Morphe during migration: that removes app-private data and its native cache key.

Validated locally with a v2 encrypted legacy index, 24 AV1/AAC streams and 12 complete MP4 videos; Android 16 VideoView playback and seeking were exercised. Unsupported/incomplete groups must remain explicit failures rather than being advertised as imported.
