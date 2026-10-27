package android.content;

/** Minimal test-only API. An unexpected use fails rather than returning a fake default. */
public interface SharedPreferences {
    default String getString(String key, String fallback) { throw new UnsupportedOperationException(); }
    default boolean getBoolean(String key, boolean fallback) { throw new UnsupportedOperationException(); }
    default int getInt(String key, int fallback) { throw new UnsupportedOperationException(); }
    Editor edit();

    interface Editor {
        default Editor putString(String key, String value) { throw new UnsupportedOperationException(); }
        default Editor putBoolean(String key, boolean value) { throw new UnsupportedOperationException(); }
        default Editor putInt(String key, int value) { throw new UnsupportedOperationException(); }
        default Editor remove(String key) { throw new UnsupportedOperationException(); }
        void apply();
    }
}
