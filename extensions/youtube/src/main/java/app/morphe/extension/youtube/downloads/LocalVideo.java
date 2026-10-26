/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import java.io.File;

/** Metadata and files belonging to one subscription-independent local download. */
public record LocalVideo(
        String videoId,
        String title,
        String channel,
        int durationSeconds,
        int height,
        long downloadedAt,
        File videoFile,
        File thumbnailFile
) {
}
