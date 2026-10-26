/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.ThumbnailEntry;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Resolves, downloads and muxes ordinary media files independently of YouTube Premium. */
public final class LocalVideoDownloadManager {

    public static final int DEFAULT_HEIGHT = 720;

    private static final String USER_AGENT =
            "com.google.android.youtube/21.42.243 (Linux; U; Android 16) gzip";
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 15_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 45_000;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private static final int THUMBNAIL_TIMEOUT_MILLISECONDS = 5_000;
    private static final int MAX_THUMBNAIL_BYTES = 2 * 1024 * 1024;
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();

    private static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    /** Height of the MP4/AVC adaptive formats YouTube currently returns. */
    private static final Map<Integer, Integer> ITAG_HEIGHTS = Map.ofEntries(
            Map.entry(160, 144),
            Map.entry(133, 240),
            Map.entry(134, 360),
            Map.entry(135, 480),
            Map.entry(136, 720),
            Map.entry(298, 720),
            Map.entry(137, 1080),
            Map.entry(299, 1080),
            Map.entry(264, 1440),
            Map.entry(266, 2160)
    );

    private LocalVideoDownloadManager() {
    }

    public interface ProgressListener {
        void update(String title, String text, int progress, boolean ongoing);
    }

    /** Resolves available qualities and then starts a foreground download chosen by the user. */
    public static void showQualityPicker(@Nullable Activity activity, String videoId) {
        if (activity == null || !validId(videoId)) {
            Utils.showToastShort(str("morphe_local_download_unavailable"));
            return;
        }
        if (LocalVideoStorage.isSaved(activity, videoId)) {
            Utils.showToastShort(str("morphe_local_download_already_saved"));
            return;
        }

        Utils.showToastShort(str("morphe_local_download_resolving"));
        Utils.runOnBackgroundThread(() -> {
            ResolvedMedia media = resolve(videoId, DEFAULT_HEIGHT);
            Utils.runOnMainThread(() -> {
                if (media == null || media.availableHeights().isEmpty()) {
                    Utils.showToastLong(str("morphe_local_download_unavailable"));
                    return;
                }
                List<Integer> heights = media.availableHeights();
                CharSequence[] labels = new CharSequence[heights.size()];
                for (int i = 0; i < heights.size(); i++) labels[i] = heights.get(i) + "p";
                int checked = Math.max(0, heights.indexOf(media.height()));
                final int[] selected = {checked};
                new AlertDialog.Builder(activity)
                        .setTitle(str("morphe_local_download_quality_title"))
                        .setSingleChoiceItems(labels, checked,
                                (dialog, which) -> selected[0] = which)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(str("morphe_local_download_start"), (dialog, which) ->
                                enqueue(activity, videoId, heights.get(selected[0])))
                        .show();
            });
        });
    }

    /** Used by the batch queue; it intentionally needs no visible activity or unlocked screen. */
    public static boolean enqueueDefault(Context context, String videoId) {
        return enqueue(context, videoId, DEFAULT_HEIGHT);
    }

    private static boolean enqueue(Context context, String videoId, int height) {
        if (!validId(videoId)) return false;
        if (LocalVideoStorage.isSaved(context, videoId)) {
            Utils.showToastShort(str("morphe_local_download_already_saved"));
            return false;
        }
        if (!QUEUED.add(videoId)) {
            Utils.showToastShort(str("morphe_local_download_already_queued"));
            return false;
        }

        try {
            Intent intent = new Intent(context, LocalVideoDownloadService.class)
                    .putExtra(LocalVideoDownloadService.EXTRA_VIDEO_ID, videoId)
                    .putExtra(LocalVideoDownloadService.EXTRA_HEIGHT, height);
            context.startForegroundService(intent);
            Utils.showToastShort(str("morphe_local_download_queued"));
            return true;
        } catch (Exception ex) {
            QUEUED.remove(videoId);
            Logger.printException(() -> "Could not start local video download: " + videoId, ex);
            Utils.showToastLong(str("morphe_local_download_failed"));
            return false;
        }
    }

    static void finished(String videoId) {
        QUEUED.remove(videoId);
    }

    static void download(Context context, String videoId, int requestedHeight,
                         ProgressListener progress) throws Exception {
        if (LocalVideoStorage.isSaved(context, videoId)) {
            progress.update(videoId, str("morphe_local_download_already_saved"), 100, false);
            return;
        }

        progress.update("YouTube", str("morphe_local_download_resolving"), -1, true);
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ResolvedMedia media = resolve(videoId, requestedHeight);
                if (media == null) throw new IllegalStateException("No downloadable MP4 streams");
                downloadResolved(context, media, progress);
                return;
            } catch (Exception ex) {
                lastFailure = ex;
                int retry = attempt;
                Logger.printDebug(() -> "Local video download attempt " + retry
                        + " failed for " + videoId, ex);
                if (attempt < MAX_ATTEMPTS) {
                    progress.update(videoId, str("morphe_local_download_retrying"), -1, true);
                }
            }
        }
        throw lastFailure == null ? new IllegalStateException("Download failed") : lastFailure;
    }

    private static void downloadResolved(Context context, ResolvedMedia media,
                                         ProgressListener progress) throws Exception {
        File directory = LocalVideoStorage.directory(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create local video directory");
        }

        String id = media.videoId();
        File videoPart = new File(directory, id + ".video.part");
        File audioPart = new File(directory, id + ".audio.part");
        File muxPart = new File(directory, id + ".mux.part");
        File destination = LocalVideoStorage.videoFile(context, id);
        deleteIfPresent(muxPart);

        long videoLength = media.video().getContentLength();
        long audioLength = media.audio().getContentLength();
        long totalLength = Math.max(1, videoLength) + Math.max(1, audioLength);
        File videoIdentity = new File(directory, id + ".video.identity");
        File audioIdentity = new File(directory, id + ".audio.identity");
        LocalVideoTransferIdentity.prepare(videoPart, videoIdentity, streamIdentity(media.video()));
        LocalVideoTransferIdentity.prepare(audioPart, audioIdentity, streamIdentity(media.audio()));

        downloadFile(media.video().getUrl(), videoPart, videoLength, downloaded -> {
            int value = videoLength > 0 && audioLength > 0
                    ? (int) Math.min(85, downloaded * 95 / totalLength)
                    : -1;
            progress.update(media.title(), str("morphe_local_download_video"), value, true);
        });
        downloadFile(media.audio().getUrl(), audioPart, audioLength, downloaded -> {
            int value = videoLength > 0 && audioLength > 0
                    ? (int) Math.min(95, (videoLength + downloaded) * 95 / totalLength)
                    : -1;
            progress.update(media.title(), str("morphe_local_download_audio"), value, true);
        });

        progress.update(media.title(), str("morphe_local_download_finishing"), 97, true);
        mux(videoPart, audioPart, muxPart);
        if (destination.exists() && !destination.delete()) {
            throw new IllegalStateException("Could not replace local video");
        }
        if (!muxPart.renameTo(destination)) {
            throw new IllegalStateException("Could not finish local video");
        }

        LocalVideoStorage.saveMetadata(context, id, media.title(), media.channel(),
                media.durationSeconds(), media.height());
        deleteIfPresent(videoPart);
        deleteIfPresent(audioPart);
        deleteIfPresent(videoIdentity);
        deleteIfPresent(audioIdentity);
        // An optional thumbnail must never prevent the completed movie from appearing.
        downloadThumbnail(context, media);
        progress.update(media.title(), str("morphe_local_download_complete"), 100, false);
    }

    private static String streamIdentity(Format format) {
        return LocalVideoTransferIdentity.forStream(format.getItag(), format.getMimeType(),
                format.getContentLength(), format.getBitrate(), format.getApproxDurationMs(),
                format.getUrl());
    }

    private interface ByteProgress {
        void update(long bytes);
    }

    /** Sequential range transfer resumes a partial file after a new signed URL is resolved. */
    private static void downloadFile(String url, File destination, long expectedLength,
                                     ByteProgress progress) throws Exception {
        long position = destination.isFile() ? destination.length() : 0;
        if (expectedLength > 0 && position > expectedLength) {
            deleteIfPresent(destination);
            position = 0;
        }
        if (expectedLength > 0 && position == expectedLength) return;

        int stalled = 0;
        while (expectedLength <= 0 || position < expectedLength) {
            long before = position;
            HttpURLConnection connection = openMediaConnection(url, position);
            int code = connection.getResponseCode();
            if (position > 0 && code == HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                deleteIfPresent(destination);
                position = 0;
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                connection.disconnect();
                throw new IllegalStateException("Media request returned HTTP " + code);
            }

            if (expectedLength <= 0) expectedLength = totalLength(connection, position);
            try (InputStream input = connection.getInputStream();
                 RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
                output.seek(position);
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) continue;
                    output.write(buffer, 0, read);
                    position += read;
                    progress.update(position);
                }
            } finally {
                connection.disconnect();
            }

            if (expectedLength <= 0) return;
            stalled = position > before ? 0 : stalled + 1;
            if (stalled >= 2) throw new IllegalStateException("Media transfer stalled");
        }
        if (expectedLength > 0 && destination.length() != expectedLength) {
            throw new IllegalStateException("Incomplete media file");
        }
    }

    private static HttpURLConnection openMediaConnection(String url, long start) throws Exception {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://www.youtube.com/");
        connection.setRequestProperty("Range", "bytes=" + start + "-");
        return connection;
    }

    private static long totalLength(HttpURLConnection connection, long start) {
        String range = connection.getHeaderField("Content-Range");
        if (range != null) {
            int slash = range.lastIndexOf('/');
            if (slash >= 0) {
                try {
                    return Long.parseLong(range.substring(slash + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        long contentLength = connection.getContentLengthLong();
        return contentLength > 0 ? start + contentLength : -1;
    }

    private static void mux(File videoFile, File audioFile, File destination) throws Exception {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            audio.setDataSource(audioFile.getAbsolutePath());
            int videoInput = findTrack(video, "video/");
            int audioInput = findTrack(audio, "audio/");
            if (videoInput < 0 || audioInput < 0) {
                throw new IllegalStateException("Downloaded media track is missing");
            }
            video.selectTrack(videoInput);
            audio.selectTrack(audioInput);

            muxer = new MediaMuxer(destination.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOutput = muxer.addTrack(video.getTrackFormat(videoInput));
            int audioOutput = muxer.addTrack(audio.getTrackFormat(audioInput));
            muxer.start();
            muxerStarted = true;

            ByteBuffer buffer = ByteBuffer.allocate(8 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean videoDone = false;
            boolean audioDone = false;
            while (!videoDone || !audioDone) {
                long videoTime = videoDone ? Long.MAX_VALUE : video.getSampleTime();
                long audioTime = audioDone ? Long.MAX_VALUE : audio.getSampleTime();
                if (videoTime < 0) {
                    videoDone = true;
                    continue;
                }
                if (audioTime < 0) {
                    audioDone = true;
                    continue;
                }

                boolean takeVideo = videoTime <= audioTime;
                MediaExtractor extractor = takeVideo ? video : audio;
                int outputTrack = takeVideo ? videoOutput : audioOutput;
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    if (takeVideo) videoDone = true; else audioDone = true;
                    continue;
                }
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(outputTrack, buffer, info);
                extractor.advance();
            }
            // stop() writes the MP4 index; its failure must not publish a broken movie.
            muxer.stop();
            muxerStarted = false;
        } finally {
            video.release();
            audio.release();
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (Exception ignored) {
                    }
                }
                muxer.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    private static void downloadThumbnail(Context context, ResolvedMedia media) {
        String url = largestThumbnail(media.details());
        if (url == null || url.isBlank()) {
            url = "https://i.ytimg.com/vi/" + media.videoId() + "/hqdefault.jpg";
        }
        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setConnectTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(THUMBNAIL_TIMEOUT_MILLISECONDS);
            long deadline = System.nanoTime() + 10_000_000_000L;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (System.nanoTime() > deadline
                            || bytes.size() + read > MAX_THUMBNAIL_BYTES) return;
                    bytes.write(buffer, 0, read);
                }
            }
            byte[] image = bytes.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
            if (bitmap == null) return;
            try (FileOutputStream output = new FileOutputStream(
                    LocalVideoStorage.thumbnailFile(context, media.videoId()))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not save local video thumbnail", ex);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    @Nullable
    private static String largestThumbnail(VideoDetails details) {
        String url = null;
        int width = 0;
        for (ThumbnailEntry entry : details.getThumbnail().getThumbnailsList()) {
            if (entry.getWidth() >= width && !entry.getUrl().isBlank()) {
                width = entry.getWidth();
                url = entry.getUrl();
            }
        }
        return url;
    }

    @Nullable
    private static ResolvedMedia resolve(String videoId, int requestedHeight) {
        try {
            StreamingDataRequest.StreamData data = StreamingDataRequest.fetchRequestForDownload(
                    videoId, DOWNLOAD_CLIENTS, DOWNLOAD_CLIENTS.get(0)).getStream();
            if (data == null) return null;
            PlayerResponse response = PlayerResponse.parseFrom(data.streamingData());
            List<Format> videos = response.getStreamingData().getAdaptiveFormatsList().stream()
                    .filter(LocalVideoDownloadManager::isMuxableVideo)
                    .toList();
            Format audio = response.getStreamingData().getAdaptiveFormatsList().stream()
                    .filter(LocalVideoDownloadManager::isMuxableAudio)
                    .max(Comparator.comparingInt(Format::getBitrate))
                    .orElse(null);
            if (videos.isEmpty() || audio == null) return null;

            LinkedHashMap<Integer, Format> bestByHeight = new LinkedHashMap<>();
            videos.stream()
                    .sorted(Comparator.comparingInt(LocalVideoDownloadManager::heightOf).reversed()
                            .thenComparing(Comparator.comparingInt(Format::getBitrate).reversed()))
                    .forEach(format -> bestByHeight.putIfAbsent(heightOf(format), format));
            List<Integer> heights = new ArrayList<>(bestByHeight.keySet());
            heights.removeIf(height -> height <= 0);
            heights.sort(Comparator.reverseOrder());
            if (heights.isEmpty()) return null;

            int selectedHeight = LocalVideoQuality.chooseHeight(
                    heights, requestedHeight, DEFAULT_HEIGHT);
            Format video = bestByHeight.get(selectedHeight);
            VideoDetails details = response.getVideoDetails();
            String title = details.getTitle().isBlank() ? videoId : details.getTitle();
            return new ResolvedMedia(videoId, title, details.getOwnerChannelName(),
                    details.getLengthSeconds(), selectedHeight, video, audio, heights, details);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not resolve local video streams: " + videoId, ex);
            return null;
        }
    }

    private static int heightOf(Format format) {
        return LocalVideoQuality.reportedOrFallbackHeight(
                format.getHeight(), ITAG_HEIGHTS.getOrDefault(format.getItag(), 0));
    }

    private static boolean isMuxableVideo(Format format) {
        String mime = format.getMimeType();
        return !format.getUrl().isBlank()
                && mime.startsWith("video/mp4")
                && (mime.contains("avc1") || format.getHeight() > 0
                || ITAG_HEIGHTS.containsKey(format.getItag()));
    }

    private static boolean isMuxableAudio(Format format) {
        return !format.getUrl().isBlank() && format.getMimeType().startsWith("audio/mp4");
    }

    private static boolean validId(@Nullable String videoId) {
        return videoId != null && VIDEO_ID.matcher(videoId).matches();
    }

    private static void deleteIfPresent(File file) throws Exception {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Could not remove temporary file: " + file.getName());
        }
    }

    private record ResolvedMedia(
            String videoId,
            String title,
            String channel,
            int durationSeconds,
            int height,
            Format video,
            Format audio,
            List<Integer> availableHeights,
            VideoDetails details
    ) {
    }
}
