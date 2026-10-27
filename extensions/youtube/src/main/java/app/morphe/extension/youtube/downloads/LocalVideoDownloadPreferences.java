/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.content.SharedPreferences;

/** App-private quality policy shared by the manual picker and background queue. */
public final class LocalVideoDownloadPreferences {

    public static final int DEFAULT_HEIGHT = 720;

    private static final String FILE = "morphe_local_video_downloads";
    private static final String ASK = "ask_quality";
    private static final String HEIGHT = "preferred_height";
    private static final int MAX_HEIGHT = 4320;

    private LocalVideoDownloadPreferences() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Asking is the default; it can only be disabled by an explicit saved choice. */
    public static boolean shouldAsk(Context context) {
        try {
            return preferences(context).getBoolean(ASK, true);
        } catch (ClassCastException invalidValue) {
            return true;
        }
    }

    /** A requested maximum height, not a promise that the source offers this quality. */
    public static int preferredHeight(Context context) {
        try {
            return validHeight(preferences(context).getInt(HEIGHT, DEFAULT_HEIGHT));
        } catch (ClassCastException invalidValue) {
            return DEFAULT_HEIGHT;
        }
    }

    /** Saves both fields atomically. The caller only invokes this after confirmation. */
    public static void save(Context context, int height, boolean ask) {
        preferences(context).edit()
                .putInt(HEIGHT, validHeight(height))
                .putBoolean(ASK, ask)
                .apply();
    }

    private static int validHeight(int height) {
        return height > 0 && height <= MAX_HEIGHT ? height : DEFAULT_HEIGHT;
    }
}
