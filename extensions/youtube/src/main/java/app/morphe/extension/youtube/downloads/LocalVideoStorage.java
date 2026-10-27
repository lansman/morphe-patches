/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.os.Environment;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** App-private storage that YouTube cannot invalidate with an entitlement refresh. */
public final class LocalVideoStorage {

    private static final String DIRECTORY_NAME = "MorpheOffline";

    private LocalVideoStorage() {
    }

    public static File directory(Context context) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), DIRECTORY_NAME);
    }

    public static File videoFile(Context context, String videoId) {
        return new File(directory(context), videoId + ".mp4");
    }

    public static File thumbnailFile(Context context, String videoId) {
        return new File(directory(context), videoId + ".jpg");
    }

    public static boolean isSaved(Context context, String videoId) {
        File movie = videoFile(context, videoId);
        if (!movie.isFile() || movie.length() == 0) return false;
        File metadataFile = new File(directory(context), videoId + ".json");
        if (!metadataFile.isFile()) return false;
        LocalVideo metadata = read(context, metadataFile);
        // A process death between MP4 publication and metadata publication is retryable.
        return metadata != null && videoId.equals(metadata.videoId());
    }

    public static void saveMetadata(Context context, String videoId, String title, String channel,
                                    int durationSeconds, int height) throws Exception {
        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create local video directory");
        }

        JSONObject json = new JSONObject()
                .put("videoId", videoId)
                .put("title", title)
                .put("channel", channel)
                .put("durationSeconds", durationSeconds)
                .put("height", height)
                .put("downloadedAt", System.currentTimeMillis());
        File target = new File(directory, videoId + ".json");
        File temporary = new File(directory, videoId + ".json.part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(json.toString().getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        if (target.exists() && !target.delete()) {
            throw new IllegalStateException("Could not replace local video metadata");
        }
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("Could not finish local video metadata");
        }
    }

    public static List<LocalVideo> list(Context context) {
        List<LocalVideo> videos = new ArrayList<>();
        File directory = directory(context);
        File[] metadataFiles = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (metadataFiles == null) return videos;

        for (File metadataFile : metadataFiles) {
            LocalVideo video = read(context, metadataFile);
            if (video != null && video.videoFile().isFile() && video.videoFile().length() > 0) {
                videos.add(video);
            }
        }
        videos.sort(Comparator.comparingLong(LocalVideo::downloadedAt).reversed());
        return videos;
    }

    @Nullable
    private static LocalVideo read(Context context, File metadataFile) {
        try {
            byte[] bytes;
            try (FileInputStream input = new FileInputStream(metadataFile)) {
                bytes = new byte[(int) metadataFile.length()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                if (offset != bytes.length) throw new IllegalStateException("Incomplete metadata");
            }
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String videoId = json.getString("videoId");
            return new LocalVideo(
                    videoId,
                    json.optString("title", videoId),
                    json.optString("channel", ""),
                    json.optInt("durationSeconds", 0),
                    json.optInt("height", 0),
                    json.optLong("downloadedAt", metadataFile.lastModified()),
                    videoFile(context, videoId),
                    thumbnailFile(context, videoId));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read local video metadata: " + metadataFile, ex);
            return null;
        }
    }

    public static boolean delete(Context context, LocalVideo video) {
        File directory = directory(context);
        boolean deleted = deleteIfPresent(video.videoFile());
        // Keep metadata and preview if the media could not be removed.
        if (!deleted) return false;
        LocalVideoWatchProgress.clear(context, video.videoId());
        deleted &= deleteIfPresent(video.thumbnailFile());
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".json"));
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".video.part"));
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".audio.part"));
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".mux.part"));
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".video.identity"));
        deleted &= deleteIfPresent(new File(directory, video.videoId() + ".audio.identity"));
        return deleted;
    }

    private static boolean deleteIfPresent(File file) {
        return !file.exists() || file.delete();
    }
}
