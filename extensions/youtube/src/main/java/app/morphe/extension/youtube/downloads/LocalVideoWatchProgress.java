/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** App-private resume position and conservative, seek-resistant watched coverage. */
public final class LocalVideoWatchProgress {
    private static final String PREFERENCES = "morphe_local_video_watch_progress";
    private static final long MAX_DURATION_MS = 7L * 24 * 60 * 60 * 1000;
    private static final long MAX_SAMPLE_GAP_MS = 2500;
    private static final int MAX_INTERVALS = 2048;
    private static final int MAX_SERIALIZED_LENGTH = 96 * 1024;

    private LocalVideoWatchProgress() { }

    public static final class Snapshot {
        public final long positionMs;
        public final long durationMs;
        public final long coveredMs;
        /** Last playback position; seeking may change this without changing watched coverage. */
        public final int progressPercent;
        public final int watchedPercent;
        /** At least 95% of distinct media time was actually played. */
        public final boolean watched;
        private final List<Range> ranges;

        private Snapshot(long positionMs, long durationMs, List<Range> source) {
            this.durationMs = validDuration(durationMs);
            this.positionMs = clamp(positionMs, this.durationMs);
            ranges = normalize(source, this.durationMs);
            long covered = 0;
            for (Range range : ranges) covered += range.end - range.start;
            coveredMs = Math.min(covered, this.durationMs);
            watched = this.durationMs > 0 && coveredMs * 100 >= this.durationMs * 95;
            watchedPercent = percentage(coveredMs, this.durationMs);
            progressPercent = watched ? 100 : percentage(this.positionMs, this.durationMs);
        }

        public static Snapshot empty() {
            return new Snapshot(0, 0, new ArrayList<>());
        }
    }

    /** Sample on the player thread about once per second; persist checkpoints separately. */
    public static final class Tracker {
        private long positionMs, durationMs;
        private List<Range> ranges;
        private long previousPosition = -1, previousElapsed = -1;
        private boolean previousPlaying;

        public Tracker(Snapshot initial) {
            Snapshot snapshot = initial == null ? Snapshot.empty() : initial;
            positionMs = snapshot.positionMs;
            durationMs = snapshot.durationMs;
            ranges = new ArrayList<>(snapshot.ranges);
        }

        public synchronized void sample(long position, long duration, boolean playing, long elapsedRealtimeMs) {
            long actualDuration = validDuration(duration);
            if (actualDuration == 0) {
                // VideoView reports an invalid duration before preparation/after failure.
                // Do not overwrite a valid saved resume position with its temporary zero.
                previousPosition = -1;
                previousElapsed = -1;
                previousPlaying = false;
                return;
            }
            if (durationMs > 0 && !sameDuration(durationMs, actualDuration)) {
                // A replacement file must not inherit the old file's watched flag.
                ranges.clear();
                previousPosition = -1;
                previousElapsed = -1;
            }
            if (actualDuration > 0) durationMs = actualDuration;
            long currentPosition = clamp(position, durationMs);
            long elapsed = previousElapsed < 0 ? -1 : elapsedRealtimeMs - previousElapsed;
            long advanced = previousPosition < 0 ? -1 : currentPosition - previousPosition;
            // Allow modest clock/decoder granularity but do not infer viewing across seeks,
            // pause-only movement, backward movement, app suspension or missing samples.
            if (durationMs > 0 && (previousPlaying || playing)
                    && elapsed > 0 && elapsed <= MAX_SAMPLE_GAP_MS
                    && advanced > 0 && advanced <= MAX_SAMPLE_GAP_MS
                    && advanced <= elapsed * 3 / 2 + 200) {
                ranges.add(new Range(previousPosition, currentPosition));
                ranges = normalize(ranges, durationMs);
            }
            positionMs = currentPosition;
            previousPosition = currentPosition;
            previousElapsed = elapsedRealtimeMs;
            previousPlaying = playing;
        }

        public synchronized Snapshot snapshot() {
            return new Snapshot(positionMs, durationMs, ranges);
        }
    }

    public static synchronized Snapshot read(Context context, String videoId) {
        if (!validId(videoId)) return Snapshot.empty();
        try {
            return decode(preferences(context).getString(videoId, null));
        } catch (RuntimeException ignored) {
            // Invalid or older private preferences must never nominate a file for deletion.
            return Snapshot.empty();
        }
    }

    /** No per-second disk I/O: the caller checkpoints about every five seconds and on exit. */
    public static synchronized void save(Context context, String videoId, Snapshot snapshot) {
        if (!validId(videoId) || snapshot == null) return;
        SharedPreferences preferences = preferences(context);
        Snapshot previous;
        try { previous = decode(preferences.getString(videoId, null)); }
        catch (RuntimeException ignored) { previous = Snapshot.empty(); }
        List<Range> combined = new ArrayList<>(snapshot.ranges);
        if (sameDuration(previous.durationMs, snapshot.durationMs)) combined.addAll(previous.ranges);
        Snapshot merged = new Snapshot(snapshot.positionMs, snapshot.durationMs, combined);
        preferences.edit().putString(videoId, encode(merged)).apply();
    }

    public static synchronized void clear(Context context, String videoId) {
        if (validId(videoId)) preferences(context).edit().remove(videoId).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean validId(String videoId) {
        return videoId != null && videoId.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static long validDuration(long duration) {
        return duration > 0 && duration <= MAX_DURATION_MS ? duration : 0;
    }

    private static long clamp(long position, long duration) {
        return duration > 0 ? Math.max(0, Math.min(position, duration)) : 0;
    }

    private static boolean sameDuration(long first, long second) {
        return first > 0 && second > 0
                && Math.abs(first - second) <= Math.max(250, Math.max(first, second) / 200);
    }

    private static int percentage(long part, long whole) {
        return whole > 0 ? (int) Math.min(100, Math.max(0, part * 100 / whole)) : 0;
    }

    private static final class Range {
        final long start, end;
        Range(long start, long end) { this.start = start; this.end = end; }
    }

    private static List<Range> normalize(List<Range> source, long duration) {
        ArrayList<Range> sorted = new ArrayList<>();
        if (duration <= 0) return sorted;
        for (Range range : source) {
            long start = clamp(range.start, duration), end = clamp(range.end, duration);
            if (start < end) sorted.add(new Range(start, end));
        }
        sorted.sort(Comparator.comparingLong(range -> range.start));
        ArrayList<Range> merged = new ArrayList<>();
        for (Range current : sorted) {
            if (merged.isEmpty()) { merged.add(current); continue; }
            Range last = merged.get(merged.size() - 1);
            if (current.start <= last.end) {
                merged.set(merged.size() - 1, new Range(last.start, Math.max(last.end, current.end)));
            } else merged.add(current);
        }
        if (merged.size() > MAX_INTERVALS) {
            // Discard the shortest fragments instead of joining unwatched gaps.
            merged.sort((a, b) -> Long.compare(b.end - b.start, a.end - a.start));
            merged.subList(MAX_INTERVALS, merged.size()).clear();
            merged.sort(Comparator.comparingLong(range -> range.start));
        }
        return merged;
    }

    private static String encode(Snapshot snapshot) {
        StringBuilder result = new StringBuilder("1|").append(snapshot.positionMs)
                .append('|').append(snapshot.durationMs).append('|');
        for (Range range : snapshot.ranges) result.append(range.start).append(':').append(range.end).append(',');
        return result.toString();
    }

    private static Snapshot decode(String value) {
        if (value == null || value.length() > MAX_SERIALIZED_LENGTH) return Snapshot.empty();
        try {
            String[] fields = value.split("\\|", -1);
            if (fields.length != 4 || !"1".equals(fields[0])) return Snapshot.empty();
            long position = Long.parseLong(fields[1]), duration = Long.parseLong(fields[2]);
            if (validDuration(duration) == 0 || position < 0 || position > duration) return Snapshot.empty();
            String[] pieces = fields[3].split(",", -1);
            if (pieces.length > MAX_INTERVALS + 1) return Snapshot.empty();
            ArrayList<Range> ranges = new ArrayList<>();
            for (String piece : pieces) {
                if (piece.isEmpty()) continue;
                String[] ends = piece.split(":", -1);
                if (ends.length != 2) return Snapshot.empty();
                long start = Long.parseLong(ends[0]), end = Long.parseLong(ends[1]);
                if (start < 0 || end <= start || end > duration) return Snapshot.empty();
                ranges.add(new Range(start, end));
            }
            return new Snapshot(position, duration, ranges);
        } catch (RuntimeException ignored) {
            return Snapshot.empty();
        }
    }
}
