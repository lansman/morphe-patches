/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;

/** Foreground worker that keeps downloads alive while the screen is locked. */
public final class LocalVideoDownloadService extends Service {

    public static final String EXTRA_VIDEO_ID = "videoId";
    public static final String EXTRA_HEIGHT = "height";

    private static final String CHANNEL = "morphe_video_downloads";
    private static final int FOREGROUND_NOTIFICATION_ID = 0x4d5650;
    private static final long PROGRESS_INTERVAL_MILLISECONDS = 1000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile long lastProgressNotification;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(FOREGROUND_NOTIFICATION_ID,
                notification("YouTube", str("morphe_local_download_waiting"), -1, true));
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String videoId = intent.getStringExtra(EXTRA_VIDEO_ID);
        int height = intent.getIntExtra(EXTRA_HEIGHT, LocalVideoDownloadManager.DEFAULT_HEIGHT);
        if (videoId == null || videoId.isBlank()) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        executor.submit(() -> {
            try {
                LocalVideoDownloadManager.download(this, videoId, height,
                        (title, text, progress, ongoing) -> notifyProgress(
                                videoId, title, text, progress, ongoing));
            } catch (Exception ex) {
                Logger.printException(() -> "Local video download failed: " + videoId, ex);
                notifyProgress(videoId, "YouTube", str("morphe_local_download_failed"), 0, false);
            } finally {
                LocalVideoDownloadManager.finished(videoId);
                stopSelfResult(startId);
            }
        });
        return START_REDELIVER_INTENT;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private void createChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL,
                    str("morphe_local_download_notification_channel"),
                    NotificationManager.IMPORTANCE_LOW));
        }
    }

    @SuppressLint("NotificationPermission")
    private void notifyProgress(String videoId, String title, String text,
                                int progress, boolean ongoing) {
        long now = System.currentTimeMillis();
        if (ongoing && progress >= 0
                && now - lastProgressNotification < PROGRESS_INTERVAL_MILLISECONDS) {
            return;
        }
        lastProgressNotification = now;
        Notification notification = notification(title, text, progress, ongoing);
        if (ongoing) {
            // Updating the foreground notification prevents Android from treating the service as idle.
            startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        } else {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(videoId.hashCode(), notification);
        }
    }

    private Notification notification(String title, String text, int progress, boolean ongoing) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ongoing
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null || title.isBlank() ? "YouTube" : title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing);
        if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
        return builder.build();
    }
}
