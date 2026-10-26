/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.downloads.LocalVideoLibrary;

/** Opens the full-screen subscription-independent local video library. */
@SuppressWarnings({"unused", "deprecation"})
public class LocalVideoDownloadsPreference extends Preference {

    public LocalVideoDownloadsPreference(Context context, AttributeSet attrs,
                                         int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        install();
    }

    public LocalVideoDownloadsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        install();
    }

    public LocalVideoDownloadsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        install();
    }

    public LocalVideoDownloadsPreference(Context context) {
        super(context);
        install();
    }

    private void install() {
        setOnPreferenceClickListener(preference -> {
            LocalVideoLibrary.show(preference.getContext());
            return true;
        });
    }
}
