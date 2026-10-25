/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.offlinequeue.OfflineQueueLearning;

@SuppressWarnings({"unused", "deprecation"})
public class OfflineQueueResetPreference extends Preference {
    public OfflineQueueResetPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installClickListener();
    }

    public OfflineQueueResetPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installClickListener();
    }

    public OfflineQueueResetPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installClickListener();
    }

    public OfflineQueueResetPreference(Context context) {
        super(context);
        installClickListener();
    }

    private void installClickListener() {
        setOnPreferenceClickListener(preference -> {
            new AlertDialog.Builder(preference.getContext())
                    .setTitle(str("morphe_offline_queue_reset_learning_title"))
                    .setMessage(str("morphe_offline_queue_reset_learning_confirm"))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        OfflineQueueLearning.reset();
                        Utils.showToastShort(str("morphe_offline_queue_reset_learning_done"));
                    })
                    .show();
            return true;
        });
    }
}
