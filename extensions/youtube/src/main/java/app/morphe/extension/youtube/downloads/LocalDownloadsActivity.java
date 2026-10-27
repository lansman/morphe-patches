/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.downloads;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;

import app.morphe.extension.shared.Utils;

/** Launcher entry into the existing app-private library; no network or autoplay. */
public final class LocalDownloadsActivity extends Activity {
    private Dialog library;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // This entry can start the process before YouTube's normal Activity hook.
        if (!Utils.isContextSet()) Utils.setContext(getApplicationContext());
        openLibrary();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Bringing an existing player to the foreground must not stack dialogs.
        if (library == null || !library.isShowing()) openLibrary();
        else LocalVideoLibrary.refresh(library);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (library != null && library.isShowing()) LocalVideoLibrary.refresh(library);
    }

    private void openLibrary() {
        library = LocalVideoLibrary.show(this, this::finish);
    }

    private void closeLibrary() {
        if (library == null) return;
        library.setOnDismissListener(null);
        library.dismiss();
        library = null;
    }

    @Override
    protected void onDestroy() {
        closeLibrary();
        super.onDestroy();
    }
}
