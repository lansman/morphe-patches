# Local video controls

The **Downloaded videos** launcher entry opens the local library inside Morphe. The regular downloads settings entry remains available.

## Download quality

Manual local downloads ask for quality by default. The native quality dialog includes **Remember this quality and do not ask again**. Confirming Download saves the selection; cancelling does not change the policy. When asking is disabled, manual downloads go directly to the queue with the remembered target height.

The library menu **Download quality** can restore asking or change the preferred quality. The same control is available in Morphe player/download settings. The background batch queue uses the preferred height without opening a dialog. If that height is unavailable, the existing resolver selects the best lower quality, or the lowest available quality when every option is higher.

## Playback progress

The library shows a red position bar on each thumbnail and a saved position below partially played videos. Reopening a partial video resumes at the saved frame. Positions and actual played intervals are stored privately, checkpointed about every five seconds, and saved when leaving or closing playback.

The player pauses on background/screen-off lifecycle transitions. Seeking establishes a new sampling anchor; skipped intervals do not count as watched. A file is eligible as watched after at least 95% of distinct media time has actually played. Unknown, malformed and pre-update history is not assumed watched. Tracking starts with playback after this feature is installed.

## Deleting watched files

The top **Delete watched** button shows the current eligible count. It opens a selectable list for confirmation; files can be deselected and an empty selection disables deletion. Cancelling keeps all files. Eligibility is checked again at confirmation.

Deletion affects the selected local files and their preview/metadata/progress. It does not clear the legacy YouTube cache. If removing the MP4 fails, metadata and the preview are retained so the video is not silently hidden.

## Validation

- Focused JVM checks cover quality defaults/persistence/fallback and seek-resistant watched coverage, resume, invalid input and the 95% boundary.
- On-device checks exercised the remember checkbox, cancel/no-change, saved quality policy, restoring asking, precise resume, reaching the end after seeking without a watched flag, and full playback becoming watched.
- Bulk-delete confirmation, cancel, deselection and selected-file deletion were tested with a temporary fixture. Existing completed media and metadata were verified unchanged. Active transfer files are not immutable snapshots and are not used for that comparison.
- Playback tests were muted. Temporary fixture files were removed.

No playback history, private preferences, downloaded media or device identifiers belong in Git.
