/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class LocalVideoTransferIdentityTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private static String identity(int itag, long length, String url) {
        return LocalVideoTransferIdentity.forStream(itag, "video/mp4; codecs=avc1", length,
                100000, 19000, url);
    }

    @Test
    public void refreshedSignatureKeepsTheSameVersionedResource() {
        assertEquals(identity(134, 5000,
                        "https://one.googlevideo.com/videoplayback?id=abc&lmt=123&ip=one&sig=old"),
                identity(134, 5000,
                        "https://two.googlevideo.com/videoplayback?id=abc&lmt=123&ip=two&sig=new"));
    }

    @Test
    public void changedEncodingOrResourceCannotResumeOldBytes() {
        String original = identity(134, 5000,
                "https://cdn/videoplayback?id=abc&lmt=123");
        assertNotEquals(original, identity(135, 5000,
                "https://cdn/videoplayback?id=abc&lmt=123"));
        assertNotEquals(original, identity(134, 6000,
                "https://cdn/videoplayback?id=abc&lmt=123"));
        assertNotEquals(original, identity(134, 5000,
                "https://cdn/videoplayback?id=abc&lmt=124"));
        assertNotEquals(original, identity(134, 5000,
                "https://cdn/videoplayback?id=another&lmt=123"));
    }

    @Test
    public void unversionedOrUnknownLengthRequiresTheExactUrl() {
        assertNotEquals(identity(134, 5000, "https://cdn/videoplayback?id=abc&sig=old"),
                identity(134, 5000, "https://cdn/videoplayback?id=abc&sig=new"));
        assertNotEquals(identity(134, 0, "https://cdn/videoplayback?id=abc&lmt=123&sig=old"),
                identity(134, 0, "https://cdn/videoplayback?id=abc&lmt=123&sig=new"));
    }

    @Test
    public void matchingMarkerRetainsPartialBytesAndMismatchDiscardsThem() throws Exception {
        File partial = new File(temporary.getRoot(), "movie.part");
        File marker = new File(temporary.getRoot(), "movie.identity");
        String original = identity(134, 5000, "https://cdn/videoplayback?id=abc&lmt=123");
        LocalVideoTransferIdentity.prepare(partial, marker, original);
        Files.write(partial.toPath(), new byte[]{1, 2, 3});
        LocalVideoTransferIdentity.prepare(partial, marker, original);
        assertEquals(3, partial.length());

        String replacement = identity(135, 6000, "https://cdn/videoplayback?id=abc&lmt=124");
        LocalVideoTransferIdentity.prepare(partial, marker, replacement);
        assertFalse(partial.exists());
        assertEquals(replacement, new String(Files.readAllBytes(marker.toPath()),
                StandardCharsets.US_ASCII));
    }

    @Test
    public void legacyPartialWithoutMarkerIsDiscarded() throws Exception {
        File partial = temporary.newFile("legacy.part");
        Files.write(partial.toPath(), new byte[]{1, 2, 3});
        File marker = new File(temporary.getRoot(), "legacy.identity");
        LocalVideoTransferIdentity.prepare(partial, marker,
                identity(134, 5000, "https://cdn/videoplayback?id=abc&lmt=123"));
        assertFalse(partial.exists());
    }
}
