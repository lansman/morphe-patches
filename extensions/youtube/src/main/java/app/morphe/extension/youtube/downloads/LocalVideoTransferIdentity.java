/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Prevents partial bytes from one encoding being appended to a newly resolved encoding. */
final class LocalVideoTransferIdentity {

    private LocalVideoTransferIdentity() {
    }

    static String forStream(int itag, String mime, long length, int bitrate,
                            long durationMillis, String url) {
        String resource = url;
        try {
            URI uri = URI.create(url);
            String id = parameter(uri.getRawQuery(), "id");
            String modified = parameter(uri.getRawQuery(), "lmt");
            // A refreshed signature or IP does not change this versioned CDN resource.
            // Without a version and a known length, require the exact original URL.
            if (length > 0 && !id.isEmpty() && !modified.isEmpty()) {
                resource = uri.getRawPath() + '\n' + id + '\n' + modified + '\n'
                        + parameter(uri.getRawQuery(), "xtags");
            }
        } catch (IllegalArgumentException ignored) {
        }
        String description = itag + "\n" + mime + "\n" + length + "\n" + bitrate
                + "\n" + durationMillis + "\n" + resource;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(description.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                int value = digest[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String parameter(String query, String name) {
        if (query == null) return "";
        String found = "";
        for (String part : query.split("&")) {
            if (!part.startsWith(name + "=")) continue;
            if (!found.isEmpty()) return "";
            found = part.substring(name.length() + 1);
        }
        return found;
    }

    static void prepare(File partial, File marker, String identity) throws IOException {
        boolean matches = false;
        if (marker.isFile() && marker.length() == identity.length()) {
            byte[] saved = new byte[identity.length()];
            try (FileInputStream input = new FileInputStream(marker)) {
                int offset = 0;
                while (offset < saved.length) {
                    int count = input.read(saved, offset, saved.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                matches = offset == saved.length && identity.equals(
                        new String(saved, StandardCharsets.US_ASCII));
            }
        }
        if (matches) return;
        if (partial.exists() && !partial.delete()) {
            throw new IOException("Could not discard a different partial stream");
        }
        File temporary = new File(marker.getParentFile(), marker.getName() + ".new");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(identity.getBytes(StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
        if (marker.exists() && !marker.delete()) {
            throw new IOException("Could not replace the partial stream identity");
        }
        if (!temporary.renameTo(marker)) {
            throw new IOException("Could not save the partial stream identity");
        }
    }
}
