/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Utils;

/** Full-screen local library and an in-process offline player. */
public final class LocalVideoLibrary {

    private static final int BACKGROUND = Color.rgb(15, 15, 15);
    private static final int SURFACE = Color.rgb(35, 35, 35);
    private static final int PRIMARY = Color.WHITE;
    private static final int SECONDARY = Color.rgb(185, 185, 185);
    private static final String LIST_TAG = "morphe.local.downloads.list";

    private LocalVideoLibrary() {
    }

    public static void show(Context context) {
        show(context, null);
    }

    /** A launcher host can finish itself when the library is closed. */
    public static Dialog show(Context context, Runnable onClosed) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);

        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(context, 8), dp(context, 8), dp(context, 12), dp(context, 8));
        Button close = new Button(context);
        close.setText("‹");
        close.setTextSize(28);
        close.setTextColor(PRIMARY);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(str("morphe_local_download_close"));
        close.setOnClickListener(view -> dialog.dismiss());
        toolbar.addView(close, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));

        TextView title = text(context, str("morphe_local_downloads_title"), 22, PRIMARY);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        toolbar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button menu = new Button(context);
        menu.setText("⋮");
        menu.setTextSize(28);
        menu.setTextColor(PRIMARY);
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setContentDescription(str("morphe_local_downloads_actions"));
        menu.setOnClickListener(view -> {
            PopupMenu popup = new PopupMenu(context, menu);
            popup.getMenu().add(str("morphe_local_downloads_pin")).setOnMenuItemClickListener(item -> {
                pinShortcut(context);
                return true;
            });
            popup.getMenu().add(str("morphe_local_downloads_migrate")).setOnMenuItemClickListener(item -> {
                prepareMigration(context);
                return true;
            });
            popup.show();
        });
        toolbar.addView(menu, new LinearLayout.LayoutParams(dp(context, 56), dp(context, 56)));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setTag(LIST_TAG);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        populate(context, list);
        dialog.setContentView(root);
        if (onClosed != null) dialog.setOnDismissListener(ignored -> onClosed.run());
        dialog.setOnShowListener(ignored -> matchParent(dialog));
        dialog.show();
        matchParent(dialog);
        return dialog;
    }

    /** Refresh the existing library without stacking dialogs over an active player. */
    public static void refresh(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        View list = window.getDecorView().findViewWithTag(LIST_TAG);
        if (list instanceof LinearLayout) populate(dialog.getContext(), (LinearLayout) list);
    }

    private static void pinShortcut(Context context) {
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) {
            Utils.showToastLong(str("morphe_local_downloads_pin_unavailable"));
            return;
        }
        Intent intent = new Intent(context, LocalDownloadsActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        ShortcutInfo.Builder info = new ShortcutInfo.Builder(context, "morphe.local.downloads")
                .setShortLabel(str("morphe_local_downloads_launcher_title"))
                .setActivity(new ComponentName(context, LocalDownloadsActivity.class))
                .setIntent(intent);
        int icon = context.getResources().getIdentifier("morphe_local_downloads_launcher",
                "drawable", context.getPackageName());
        if (icon != 0) info.setIcon(Icon.createWithResource(context, icon));
        if (!manager.requestPinShortcut(info.build(), null)) {
            Utils.showToastLong(str("morphe_local_downloads_pin_unavailable"));
        }
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

    private static void prepareMigration(Context context) {
        new AlertDialog.Builder(context)
                .setTitle(str("morphe_local_downloads_migrate"))
                .setMessage(str("morphe_local_downloads_migrate_description"))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_local_downloads_migrate_start"), (confirm, which) -> {
                    ProgressDialog progress = new ProgressDialog(context);
                    progress.setTitle(str("morphe_local_downloads_migrate"));
                    progress.setMessage(str("morphe_local_downloads_migrate_running"));
                    progress.setIndeterminate(true);
                    progress.setCancelable(false);
                    progress.show();
                    Utils.runOnBackgroundThread(() -> {
                        String message;
                        try {
                            LocalNativeMigration.Result result = LocalNativeMigration.stage(context.getApplicationContext());
                            LocalNativeMetadata.export(context.getApplicationContext(), result.directory);
                            message = str("morphe_local_downloads_migrate_ready", result.decodedGroups, result.skippedGroups);
                        } catch (Exception error) {
                            // Exception messages may contain paths; keep UI output bounded.
                            message = str("morphe_local_downloads_migrate_failed") + " (" + error.getClass().getSimpleName() + ")";
                        }
                        String finalMessage = message;
                        Utils.runOnMainThread(() -> {
                            Activity host = activity(context);
                            if (host != null && (host.isFinishing() || host.isDestroyed())) return;
                            progress.dismiss();
                            new AlertDialog.Builder(context).setTitle(str("morphe_local_downloads_migrate"))
                                    .setMessage(finalMessage).setPositiveButton(android.R.string.ok, null).show();
                        });
                    });
                }).show();
    }

    private static void populate(Context context, LinearLayout list) {
        list.removeAllViews();
        List<LocalVideo> videos = LocalVideoStorage.list(context);
        if (videos.isEmpty()) {
            TextView empty = text(context, str("morphe_local_downloads_empty"), 17, SECONDARY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(context, 24), dp(context, 120), dp(context, 24), dp(context, 24));
            list.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        long total = videos.stream().mapToLong(video -> video.videoFile().length()).sum();
        TextView summary = text(context,
                str("morphe_local_downloads_summary", videos.size(), megabytes(total)), 15, SECONDARY);
        summary.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 14));
        list.addView(summary);

        for (LocalVideo video : videos) list.addView(row(context, list, video));
    }

    private static View row(Context context, LinearLayout list, LocalVideo video) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 10), dp(context, 10), dp(context, 6), dp(context, 10));
        row.setBackgroundColor(SURFACE);
        row.setOnClickListener(view -> play(context, video));

        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (video.thumbnailFile().isFile()) {
            thumbnail.setImageBitmap(BitmapFactory.decodeFile(video.thumbnailFile().getAbsolutePath()));
        } else {
            thumbnail.setBackgroundColor(Color.DKGRAY);
        }
        row.addView(thumbnail, new LinearLayout.LayoutParams(dp(context, 168), dp(context, 95)));

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(context, 14), 0, dp(context, 8), 0);
        TextView title = text(context, video.title(), 16, PRIMARY);
        title.setMaxLines(2);
        details.addView(title);
        String quality = video.height() > 0 ? video.height() + "p" : "";
        String subtitle = video.channel();
        if (!quality.isEmpty()) subtitle += (subtitle.isEmpty() ? "" : " · ") + quality;
        subtitle += (subtitle.isEmpty() ? "" : " · ") + formatDuration(video.durationSeconds());
        subtitle += " · " + megabytes(video.videoFile().length()) + " MB";
        TextView metadata = text(context, subtitle, 13, SECONDARY);
        metadata.setMaxLines(2);
        details.addView(metadata);
        row.addView(details, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button delete = new Button(context);
        delete.setText("×");
        delete.setTextSize(26);
        delete.setTextColor(SECONDARY);
        delete.setBackgroundColor(Color.TRANSPARENT);
        delete.setContentDescription(str("morphe_local_download_delete"));
        delete.setOnClickListener(view -> new AlertDialog.Builder(context)
                .setTitle(str("morphe_local_download_delete_title"))
                .setMessage(video.title())
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_local_download_delete"), (dialog, which) -> {
                    if (!LocalVideoStorage.delete(context, video)) {
                        Utils.showToastLong(str("morphe_local_download_delete_failed"));
                    }
                    populate(context, list);
                })
                .show());
        row.addView(delete, new LinearLayout.LayoutParams(dp(context, 52), dp(context, 64)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(context, 10));
        row.setLayoutParams(params);
        return row;
    }

    private static void play(Context context, LocalVideo video) {
        Dialog player = new Dialog(context, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        VideoView view = new VideoView(context);
        root.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));

        Button close = new Button(context);
        close.setText("×");
        close.setTextSize(26);
        close.setTextColor(PRIMARY);
        close.setBackgroundColor(Color.argb(120, 0, 0, 0));
        close.setContentDescription(str("morphe_local_download_close"));
        close.setOnClickListener(ignored -> player.dismiss());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dp(context, 56), dp(context, 56), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(context, 12), dp(context, 12), 0);
        root.addView(close, closeParams);

        MediaController controls = new MediaController(context);
        controls.setAnchorView(view);
        view.setMediaController(controls);
        view.setVideoPath(video.videoFile().getAbsolutePath());
        view.setOnPreparedListener(mediaPlayer -> {
            mediaPlayer.setScreenOnWhilePlaying(true);
            view.start();
            controls.show();
        });
        view.setOnErrorListener((mediaPlayer, what, extra) -> {
            Utils.showToastLong(str("morphe_local_download_play_failed"));
            return true;
        });

        player.setContentView(root);
        player.setOnDismissListener(ignored -> view.stopPlayback());
        player.show();
        matchParent(player);
    }

    private static TextView text(Context context, String value, int sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private static String formatDuration(int seconds) {
        if (seconds <= 0) return "–:––";
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remaining = seconds % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
                : String.format(Locale.ROOT, "%d:%02d", minutes, remaining);
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024d * 1024d));
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void matchParent(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(BACKGROUND);
            window.setNavigationBarColor(BACKGROUND);
        }
    }
}
