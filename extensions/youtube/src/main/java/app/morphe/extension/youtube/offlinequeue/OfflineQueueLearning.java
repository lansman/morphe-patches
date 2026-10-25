/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.offlinequeue;

import java.util.List;

import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest.PlaylistItem;
import app.morphe.extension.youtube.settings.Settings;

/** Persists and applies the local profile through Morphe's import/export-aware settings store. */
public final class OfflineQueueLearning {

    private OfflineQueueLearning() {
    }

    public static List<PlaylistItem> rank(List<PlaylistItem> items) {
        if (!Settings.OFFLINE_QUEUE_LEARNING.get()) return items;
        return load().rank(items, Settings.OFFLINE_QUEUE_SEED_INTERESTS.get());
    }

    public static void feedback(PlaylistItem item, OfflineQueueProfile.Signal signal) {
        if (!Settings.OFFLINE_QUEUE_LEARNING.get()) return;
        OfflineQueueProfile profile = load();
        profile.apply(item, signal);
        Settings.OFFLINE_QUEUE_LEARNING_DATA.save(profile.toJson());
    }

    public static void reset() {
        Settings.OFFLINE_QUEUE_LEARNING_DATA.resetToDefault();
    }

    private static OfflineQueueProfile load() {
        return OfflineQueueProfile.fromJson(Settings.OFFLINE_QUEUE_LEARNING_DATA.get());
    }
}
