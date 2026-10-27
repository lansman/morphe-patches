/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.downloads.LocalVideoLibrary;

/** Opens the same quality policy used by the local library menu. */
@SuppressWarnings({"unused", "deprecation"})
public final class LocalVideoQualityPreference extends Preference {
    public LocalVideoQualityPreference(Context c) { super(c); install(); }
    public LocalVideoQualityPreference(Context c, AttributeSet a) { super(c, a); install(); }
    public LocalVideoQualityPreference(Context c, AttributeSet a, int s) { super(c, a, s); install(); }
    public LocalVideoQualityPreference(Context c, AttributeSet a, int s, int r) { super(c, a, s, r); install(); }
    private void install() {
        setOnPreferenceClickListener(p -> {
            LocalVideoLibrary.showDownloadQualitySettings(p.getContext());
            return true;
        });
    }
}
