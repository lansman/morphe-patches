/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import java.util.List;

/** Pure quality-selection policy, kept independent of the Android download runtime. */
final class LocalVideoQuality {

    private LocalVideoQuality() {
    }

    static int chooseHeight(List<Integer> heights, int requestedHeight, int fallback) {
        return heights.stream()
                .filter(height -> height <= requestedHeight)
                .max(Integer::compareTo)
                .orElseGet(() -> heights.stream().min(Integer::compareTo).orElse(fallback));
    }

    static int reportedOrFallbackHeight(int reportedHeight, int fallbackHeight) {
        return reportedHeight > 0 ? reportedHeight : fallbackHeight;
    }
}
