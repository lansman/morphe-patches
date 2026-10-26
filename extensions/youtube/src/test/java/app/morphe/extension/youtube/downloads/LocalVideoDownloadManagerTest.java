/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class LocalVideoDownloadManagerTest {

    @Test
    public void requestedQualityUsesHighestAvailableNotAboveTarget() {
        assertEquals(720, LocalVideoQuality.chooseHeight(
                List.of(1080, 720, 480, 360), 720, 720));
        assertEquals(480, LocalVideoQuality.chooseHeight(
                List.of(1080, 480, 360), 720, 720));
    }

    @Test
    public void targetBelowEveryAvailableQualityUsesSmallest() {
        assertEquals(360, LocalVideoQuality.chooseHeight(
                List.of(1080, 720, 360), 144, 720));
    }

    @Test
    public void targetAboveEveryAvailableQualityUsesLargest() {
        assertEquals(1080, LocalVideoQuality.chooseHeight(
                List.of(1080, 720, 360), 2160, 720));
    }

    @Test
    public void playerReportedHeightOverridesNominalItagHeight() {
        assertEquals(240, LocalVideoQuality.reportedOrFallbackHeight(240, 360));
    }

    @Test
    public void itagHeightRemainsFallbackForOlderResponses() {
        assertEquals(360, LocalVideoQuality.reportedOrFallbackHeight(0, 360));
    }
}
