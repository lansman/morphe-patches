/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.VideoView;

import app.morphe.extension.shared.Utils;

/** One in-process player with durable resume and seek-resistant watched coverage. */
public final class LocalVideoPlayer {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long CHECKPOINT_MS = 5000;
    private static Session active;

    private LocalVideoPlayer() { }

    public static void play(Context context, LocalVideo video, Runnable onProgressChanged) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> play(context, video, onProgressChanged));
            return;
        }
        Activity host = activity(context);
        if (host != null && (host.isFinishing() || host.isDestroyed())) return;
        if (active != null) active.close();
        Session session = new Session(context, video, onProgressChanged);
        active = session;
        try {
            session.open();
        } catch (RuntimeException failure) {
            session.close();
            Utils.showToastLong(str("morphe_local_download_play_failed"));
        }
    }

    /** Called on the UI thread before removing the corresponding local file. */
    public static void closeIfPlaying(String videoId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> closeIfPlaying(videoId));
            return;
        }
        if (active != null && active.video.videoId().equals(videoId)) active.close();
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static final class Session {
        private final Context context;
        private final Context appContext;
        private final Activity host;
        private final Application application;
        private final LocalVideo video;
        private final Runnable onProgressChanged;
        private final LocalVideoWatchProgress.Snapshot original;
        private final LocalVideoWatchProgress.Tracker tracker;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Dialog dialog;
        private VideoView view;
        private MediaController controls;
        private boolean prepared, closed, callbacksRegistered, foreground = true;
        private boolean resumePending, completed;
        private long lastCheckpoint = SystemClock.elapsedRealtime();

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                if (closed || !foreground) return;
                capture();
                if (SystemClock.elapsedRealtime() - lastCheckpoint >= CHECKPOINT_MS) checkpoint();
                handler.postDelayed(this, 1000);
            }
        };

        private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityResumed(Activity activity) {
                if (activity != host || closed) return;
                foreground = true;
                restartTicker();
            }
            @Override public void onActivityPaused(Activity activity) {
                if (activity == host) pauseAndSave();
            }
            @Override public void onActivityStopped(Activity activity) {
                if (activity == host) pauseAndSave();
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (activity == host) close();
            }
        };

        Session(Context context, LocalVideo video, Runnable callback) {
            this.context = context;
            appContext = context.getApplicationContext();
            host = activity(context);
            application = appContext instanceof Application ? (Application) appContext : null;
            this.video = video;
            onProgressChanged = callback;
            original = LocalVideoWatchProgress.read(appContext, video.videoId());
            tracker = new LocalVideoWatchProgress.Tracker(original);
        }

        void open() {
            dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
            FrameLayout root = new FrameLayout(context);
            root.setBackgroundColor(Color.BLACK);
            view = new VideoView(context);
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));

            Button close = new Button(context);
            close.setText("×");
            close.setTextSize(26);
            close.setTextColor(Color.WHITE);
            close.setBackgroundColor(Color.argb(120, 0, 0, 0));
            close.setContentDescription(str("morphe_local_download_close"));
            close.setOnClickListener(ignored -> close());
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.END);
            closeParams.setMargins(0, dp(12), dp(12), 0);
            root.addView(close, closeParams);

            controls = new MediaController(context);
            controls.setAnchorView(view);
            view.setMediaController(controls);
            view.setVideoPath(video.videoFile().getAbsolutePath());
            view.setOnPreparedListener(mediaPlayer -> {
                if (closed) return;
                prepared = true;
                mediaPlayer.setScreenOnWhilePlaying(true);
                long duration = view.getDuration();
                long position = original.watched ? 0 : Math.min(original.positionMs, Math.max(0, duration));
                // Reopening an ended but not fully watched clip should restart it normally.
                if (position >= Math.max(0, duration - 1000)) position = 0;
                long now = SystemClock.elapsedRealtime();
                tracker.sample(position, duration, false, now);
                mediaPlayer.setOnSeekCompleteListener(ignored -> {
                    if (closed || !prepared) return;
                    resumePending = false;
                    completed = false;
                    long at = SystemClock.elapsedRealtime();
                    // A seek is a new anchor, never evidence that the skipped interval was watched.
                    tracker.sample(0, 0, false, at);
                    tracker.sample(view.getCurrentPosition(), view.getDuration(), view.isPlaying(), at);
                });
                resumePending = position > 0;
                // VideoView.seekTo(int) snaps to a previous keyframe. Resume at the
                // actual saved frame instead, especially for short/long-GOP videos.
                if (resumePending) mediaPlayer.seekTo(position, android.media.MediaPlayer.SEEK_CLOSEST);
                if (foreground) view.start();
                restartTicker();
                controls.show();
            });
            view.setOnCompletionListener(mediaPlayer -> {
                if (closed || !prepared) return;
                resumePending = false;
                long duration = view.getDuration();
                tracker.sample(duration, duration, false, SystemClock.elapsedRealtime());
                completed = true;
                checkpoint();
                controls.show(0);
            });
            view.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (!closed) {
                    checkpoint();
                    prepared = false;
                    handler.removeCallbacks(ticker);
                    Utils.showToastLong(str("morphe_local_download_play_failed"));
                }
                return true;
            });
            dialog.setContentView(root);
            dialog.setOnDismissListener(ignored -> finish());
            if (application != null && host != null) {
                application.registerActivityLifecycleCallbacks(lifecycle);
                callbacksRegistered = true;
            }
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.BLACK);
                window.setNavigationBarColor(Color.BLACK);
            }
        }

        private int dp(int value) {
            return Math.round(value * context.getResources().getDisplayMetrics().density);
        }

        private void restartTicker() {
            handler.removeCallbacks(ticker);
            if (!closed && foreground) handler.post(ticker);
        }

        private void capture() {
            if (closed || !prepared || resumePending || view == null) return;
            try {
                int duration = view.getDuration();
                if (duration <= 0) return;
                boolean playing = view.isPlaying();
                int position = view.getCurrentPosition();
                if (completed && !playing) position = duration;
                else if (playing) completed = false;
                tracker.sample(position, duration, playing, SystemClock.elapsedRealtime());
            } catch (RuntimeException ignored) {
                // A disappearing Surface/MediaPlayer must not erase the last valid checkpoint.
            }
        }

        private void checkpoint() {
            if (closed || !prepared) return;
            capture();
            LocalVideoWatchProgress.save(appContext, video.videoId(), tracker.snapshot());
            lastCheckpoint = SystemClock.elapsedRealtime();
        }

        private void pauseAndSave() {
            if (closed) return;
            capture();
            if (prepared && view != null) {
                try { if (view.isPlaying()) view.pause(); }
                catch (RuntimeException ignored) { }
                capture();
                LocalVideoWatchProgress.save(appContext, video.videoId(), tracker.snapshot());
                lastCheckpoint = SystemClock.elapsedRealtime();
            }
            foreground = false;
            handler.removeCallbacks(ticker);
        }

        void close() {
            if (closed) return;
            try {
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
                else finish();
            } catch (RuntimeException ignored) {
                finish();
            }
        }

        private void finish() {
            if (closed) return;
            // The view reports zero after stopPlayback; checkpoint before touching its player.
            checkpoint();
            closed = true;
            handler.removeCallbacks(ticker);
            if (callbacksRegistered) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                callbacksRegistered = false;
            }
            if (view != null) {
                try { view.stopPlayback(); }
                catch (RuntimeException ignored) { }
            }
            if (active == this) active = null;
            if (onProgressChanged != null && (host == null || !host.isFinishing() && !host.isDestroyed())) {
                onProgressChanged.run();
            }
        }
    }
}
