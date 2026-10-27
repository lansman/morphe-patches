package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises persisted policy and actual format fallback without a player or network. */
public final class QualityPreferencesCheck {
    private static int checks;

    private static final class Prefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        int writes;

        @Override public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        @Override public int getInt(String key, int fallback) {
            return values.containsKey(key) ? (Integer) values.get(key) : fallback;
        }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public Editor putInt(String key, int value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public void apply() {
                    values.putAll(pending);
                    writes++;
                }
            };
        }
    }

    private static final class TestContext extends Context {
        private final Prefs prefs;
        TestContext(Prefs prefs) { this.prefs = prefs; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            if (!name.equals("morphe_local_video_downloads") || mode != MODE_PRIVATE) {
                throw new AssertionError("Quality preferences must remain app-private");
            }
            return prefs;
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        Prefs prefs = new Prefs();
        TestContext context = new TestContext(prefs);
        check(LocalVideoDownloadPreferences.shouldAsk(context), "A fresh installation asks for quality");
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 720, "Fresh fallback is 720p");
        check(prefs.writes == 0, "Reading defaults never persists a choice");

        LocalVideoDownloadPreferences.save(context, 144, false);
        check(!LocalVideoDownloadPreferences.shouldAsk(context), "An explicit remembered choice skips the picker");
        check(LocalVideoDownloadPreferences.preferredHeight(new TestContext(prefs)) == 144, "Choice survives a new Context");
        check(prefs.writes == 1, "Remembered height and picker policy are stored together");

        LocalVideoDownloadPreferences.save(context, 240, true);
        check(LocalVideoDownloadPreferences.shouldAsk(context), "The user can restore asking for quality");
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 240, "Restoring asking retains the chosen height");
        LocalVideoDownloadPreferences.save(context, 0, false);
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 720, "A non-positive height falls back safely");
        LocalVideoDownloadPreferences.save(context, 9999, true);
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 720, "An excessive height falls back safely");

        prefs.values.put("preferred_height", "broken");
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 720, "A mistyped height does not break downloads");
        prefs.values.put("ask_quality", "broken");
        check(LocalVideoDownloadPreferences.shouldAsk(context), "A damaged preference cannot silently suppress the picker");
        LocalVideoDownloadPreferences.save(context, 4320, false);
        check(LocalVideoDownloadPreferences.preferredHeight(context) == 4320, "A supported upper bound is retained");

        check(LocalVideoQuality.chooseHeight(List.of(1080, 480, 360), 720, 720) == 480,
                "An unavailable target chooses the best lower stream");
        check(LocalVideoQuality.chooseHeight(List.of(240, 144), 144, 720) == 144,
                "An exact low-quality choice is respected");
        check(LocalVideoQuality.chooseHeight(List.of(360, 240), 144, 720) == 240,
                "If every stream is higher, the smallest available stream is used");
        System.out.println("Quality preference/fallback checks passed: " + checks + " assertions");
    }
}
