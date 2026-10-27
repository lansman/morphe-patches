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
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Arrays;

import app.morphe.extension.shared.Utils;

/** Full-screen local library and an in-process offline player. */
public final class LocalVideoLibrary {

    private static final int BACKGROUND = Color.rgb(15, 15, 15);
    private static final int SURFACE = Color.rgb(35, 35, 35);
    private static final int PRIMARY = Color.WHITE;
    private static final int SECONDARY = Color.rgb(185, 185, 185);
    private static final String LIST_TAG = "morphe.local.downloads.list";
    private static final String DELETE_WATCHED_TAG = "morphe.local.downloads.delete.watched";

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
            popup.getMenu().add(str("morphe_local_download_quality_settings_title")).setOnMenuItemClickListener(item -> {
                showDownloadQualitySettings(context);
                return true;
            });
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

        Button deleteWatched = new Button(context);
        deleteWatched.setTag(DELETE_WATCHED_TAG);
        deleteWatched.setAllCaps(false);
        deleteWatched.setTextSize(16);
        deleteWatched.setTextColor(PRIMARY);
        deleteWatched.setBackgroundColor(SURFACE);
        deleteWatched.setMinHeight(dp(context, 48));
        android.graphics.drawable.Drawable trash = context.getDrawable(android.R.drawable.ic_menu_delete).mutate();
        trash.setTint(PRIMARY);
        trash.setBounds(0, 0, dp(context, 24), dp(context, 24));
        deleteWatched.setCompoundDrawablesRelative(trash, null, null, null);
        deleteWatched.setCompoundDrawablePadding(dp(context, 8));
        deleteWatched.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        deleteWatched.setTooltipText(str("morphe_local_download_progress_note"));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        deleteParams.setMargins(dp(context, 12), 0, dp(context, 12), dp(context, 8));
        root.addView(deleteWatched, deleteParams);

        ScrollView scroll = new ScrollView(context);
        LinearLayout list = new LinearLayout(context);
        list.setTag(LIST_TAG);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 24));
        scroll.addView(list, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        deleteWatched.setOnClickListener(view -> confirmDeleteWatched(context, list));

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

    /** Also available from Morphe download settings, without opening a video. */
    public static void showDownloadQualitySettings(Context context) {
        int[] heights = {2160, 1440, 1080, 720, 480, 360, 240, 144};
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        CheckBox ask = new CheckBox(context);
        ask.setText(str("morphe_local_download_ask_quality"));
        ask.setChecked(LocalVideoDownloadPreferences.shouldAsk(context));
        ask.setMinHeight(dp(context, 48));
        body.addView(ask);
        TextView qualityLabel = new TextView(context);
        qualityLabel.setText(str("morphe_local_download_preferred_quality"));
        qualityLabel.setTextSize(16);
        body.addView(qualityLabel);
        Spinner quality = new Spinner(context);
        String[] labels = new String[heights.length];
        int selected = 3;
        for (int i = 0; i < heights.length; i++) {
            labels[i] = heights[i] + "p";
            if (heights[i] == LocalVideoDownloadPreferences.preferredHeight(context)) selected = i;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        quality.setAdapter(adapter);
        quality.setSelection(selected);
        quality.setMinimumHeight(dp(context, 48));
        body.addView(quality, new LinearLayout.LayoutParams(-1, -2));
        TextView explanation = new TextView(context);
        explanation.setText(str("morphe_local_download_quality_fallback"));
        explanation.setTextSize(14);
        explanation.setPadding(0, dp(context, 8), 0, 0);
        body.addView(explanation);
        new AlertDialog.Builder(context)
                .setTitle(str("morphe_local_download_quality_settings_title"))
                .setView(body).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        LocalVideoDownloadPreferences.save(context,
                                heights[quality.getSelectedItemPosition()], ask.isChecked()))
                .show();
    }

    private static void confirmDeleteWatched(Context context, LinearLayout list) {
        List<LocalVideo> watched = new ArrayList<>();
        for (LocalVideo video : LocalVideoStorage.list(context)) {
            if (LocalVideoWatchProgress.read(context, video.videoId()).watched) watched.add(video);
        }
        if (watched.isEmpty()) {
            Utils.showToastLong(str("morphe_local_download_no_watched"));
            return;
        }
        CharSequence[] titles = new CharSequence[watched.size()];
        boolean[] selected = new boolean[watched.size()];
        Arrays.fill(selected, true);
        for (int i = 0; i < titles.length; i++) titles[i] = watched.get(i).title();
        AlertDialog confirm = new AlertDialog.Builder(context)
                .setTitle(str("morphe_local_download_delete_watched_title"))
                .setMultiChoiceItems(titles, selected, (dialog, which, checked) -> {
                    selected[which] = checked;
                    updateDeleteSelection((AlertDialog) dialog, selected);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(str("morphe_local_download_delete"), (dialog, which) -> {
                    int deleted = 0, failed = 0;
                    for (int i = 0; i < selected.length; i++) {
                        if (!selected[i]) continue;
                        LocalVideo video = watched.get(i);
                        // Recheck the snapshot at confirmation; unknown progress is kept.
                        if (!LocalVideoWatchProgress.read(context, video.videoId()).watched) continue;
                        LocalVideoPlayer.closeIfPlaying(video.videoId());
                        if (LocalVideoStorage.delete(context, video)) deleted++;
                        else failed++;
                    }
                    populate(context, list);
                    Utils.showToastLong(str("morphe_local_download_delete_result", deleted, failed));
                }).create();
        confirm.setOnShowListener(ignored -> updateDeleteSelection(confirm, selected));
        confirm.show();
    }

    private static void updateDeleteSelection(AlertDialog dialog, boolean[] selected) {
        int count = 0;
        for (boolean value : selected) if (value) count++;
        Button delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        delete.setText(str("morphe_local_download_delete_selected", count));
        delete.setEnabled(count > 0);
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
        int watched = 0;
        for (LocalVideo video : videos) {
            if (LocalVideoWatchProgress.read(context, video.videoId()).watched) watched++;
        }
        View bulk = list.getRootView().findViewWithTag(DELETE_WATCHED_TAG);
        if (bulk instanceof Button) {
            ((Button) bulk).setText(str("morphe_local_download_delete_watched", watched));
            bulk.setEnabled(watched > 0);
            bulk.setAlpha(watched > 0 ? 1f : .45f);
        }
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
        TextView progressNote = text(context, str("morphe_local_download_progress_note"), 13, SECONDARY);
        progressNote.setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 10));
        list.addView(progressNote);

        for (LocalVideo video : videos) list.addView(row(context, list, video));
    }

    private static View row(Context context, LinearLayout list, LocalVideo video) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 10), dp(context, 10), dp(context, 6), dp(context, 10));
        row.setBackgroundColor(SURFACE);
        row.setOnClickListener(view -> LocalVideoPlayer.play(context, video, () -> populate(context, list)));

        ImageView thumbnail = new ImageView(context);
        thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (video.thumbnailFile().isFile()) {
            thumbnail.setImageBitmap(BitmapFactory.decodeFile(video.thumbnailFile().getAbsolutePath()));
        } else {
            thumbnail.setBackgroundColor(Color.DKGRAY);
        }
        LocalVideoWatchProgress.Snapshot progress = LocalVideoWatchProgress.read(context, video.videoId());
        FrameLayout preview = new FrameLayout(context);
        preview.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(progress.progressPercent);
        bar.setProgressTintList(ColorStateList.valueOf(Color.rgb(244, 67, 54)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(85, 85, 85)));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        preview.addView(bar, new FrameLayout.LayoutParams(-1, dp(context, 4), Gravity.BOTTOM));
        row.addView(preview, new LinearLayout.LayoutParams(dp(context, 168), dp(context, 95)));

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
        if (progress.watched || progress.positionMs > 0) {
            String label = progress.watched ? str("morphe_local_download_watched")
                    : str("morphe_local_download_resume_position", formatDuration((int) (progress.positionMs / 1000)),
                            formatDuration((int) (progress.durationMs / 1000)));
            details.addView(text(context, label, 13, progress.watched ? Color.rgb(135, 242, 191) : SECONDARY));
        }
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
                    LocalVideoPlayer.closeIfPlaying(video.videoId());
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
