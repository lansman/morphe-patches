/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.offlinequeue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import app.morphe.extension.youtube.patches.utils.requests.GetPlaylistItemsRequest.PlaylistItem;

public class OfflineQueueProfileTest {

    private static PlaylistItem item(String id, String title, String channel) {
        return new PlaylistItem(id, "", title, channel, 1800);
    }

    @Test
    public void coldStartPreservesServerOrder() {
        OfflineQueueProfile profile = new OfflineQueueProfile();
        var source = List.of(item("aaaaaaaaaaa", "One", "A"), item("bbbbbbbbbbb", "Two", "B"));
        assertEquals(source, profile.rank(source, ""));
    }

    @Test
    public void freeFormSeedsWorkWithoutBundledCategories() {
        OfflineQueueProfile profile = new OfflineQueueProfile();
        var source = List.of(
                item("aaaaaaaaaaa", "A cooking lesson", "A"),
                item("bbbbbbbbbbb", "Bridge engineering explained", "B"));
        assertEquals("bbbbbbbbbbb", profile.rank(source, "engineering").get(0).videoId());
    }

    @Test
    public void acceptedAndRejectedTopicsMoveInOppositeDirections() {
        OfflineQueueProfile profile = new OfflineQueueProfile();
        PlaylistItem engineering = item("aaaaaaaaaaa", "Engineering design review", "Lab");
        PlaylistItem crime = item("bbbbbbbbbbb", "True crime documentary", "Cases");
        for (int i = 0; i < 3; i++) profile.apply(engineering, OfflineQueueProfile.Signal.MANUAL_ADD);
        for (int i = 0; i < 3; i++) profile.apply(crime, OfflineQueueProfile.Signal.SHOW_LESS);
        assertEquals(engineering, profile.rank(List.of(crime, engineering), "").get(0));
    }

    @Test
    public void profileRoundTripsAsJson() {
        OfflineQueueProfile profile = new OfflineQueueProfile();
        PlaylistItem item = item("aaaaaaaaaaa", "Technical news", "Lab");
        profile.apply(item, OfflineQueueProfile.Signal.WATCHED);
        OfflineQueueProfile restored = OfflineQueueProfile.fromJson(profile.toJson());
        assertEquals(1, restored.signalCount());
        assertTrue(restored.score(item, List.of()) > 0);
    }
}
