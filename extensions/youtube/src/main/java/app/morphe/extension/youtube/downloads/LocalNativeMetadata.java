/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.VideoDetails;

/** Worker-only, read-only extraction of the metadata needed to import native downloads. */
public final class LocalNativeMetadata {
    private static final int MAX_ROWS = 5000;
    private static final int MAX_DATABASES = 128;
    // Stay below the platform CursorWindow limit, even for unusual player responses.
    private static final int MAX_PROTO_BYTES = 1024 * 1024;
    private static final byte[] SQLITE_HEADER = "SQLite format 3\000".getBytes(StandardCharsets.US_ASCII);

    private LocalNativeMetadata() { }

    /** Writes no database, key, token, URL or raw protobuf to the metadata exports. */
    public static synchronized void export(Context context, File stageDirectory) throws Exception {
        Context app = context.getApplicationContext();
        requirePrivateStage(app, stageDirectory);
        Export videos = new Export();
        Export streams = new Export();
        List<File> databases = databaseFiles(app);
        if (databases.size() >= MAX_DATABASES) { videos.truncated = true; streams.truncated = true; }
        for (int ordinal = 0; ordinal < databases.size(); ordinal++) {
            File file = databases.get(ordinal);
            videos.databasesInspected++;
            streams.databasesInspected++;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS)) {
                if (hasTable(db, "videosV2")) {
                    videos.matchingTables++;
                    try { readVideos(db, ordinal, videos); }
                    catch (Exception ignored) { videos.tableFailures++; }
                }
                if (hasTable(db, "streams")) {
                    streams.matchingTables++;
                    try { readStreams(db, ordinal, streams); }
                    catch (Exception ignored) { streams.tableFailures++; }
                }
            } catch (Exception ignored) {
                videos.databaseFailures++;
                streams.databaseFailures++;
            }
        }
        atomicWrite(stageDirectory, "videos-private.json", videos.json());
        atomicWrite(stageDirectory, "streams-private.json", streams.json());
    }

    private static void requirePrivateStage(Context context, File directory) throws IOException {
        File external = context.getExternalFilesDir(null);
        if (external == null || !directory.getCanonicalPath().startsWith(
                external.getCanonicalPath() + File.separator)) {
            throw new IOException("Native metadata stage must be app-private");
        }
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Native metadata stage unavailable");
        }
    }

    private static List<File> databaseFiles(Context context) throws IOException {
        Set<File> candidates = new LinkedHashSet<>();
        File root = context.getDatabasePath(".").getCanonicalFile();
        String[] names = context.databaseList();
        if (names != null) {
            Arrays.sort(names);
            for (String name : names) collectDatabases(context.getDatabasePath(name), root, 0, candidates);
        }
        return new ArrayList<>(candidates);
    }

    private static void collectDatabases(File file, File root, int depth, Set<File> result) throws IOException {
        if (result.size() >= MAX_DATABASES || depth > 4) return;
        File canonical = file.getCanonicalFile();
        if (!canonical.getPath().startsWith(root.getPath() + File.separator)) return;
        if (canonical.isDirectory()) {
            File[] children = canonical.listFiles();
            if (children == null) return;
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File child : children) collectDatabases(child, root, depth + 1, result);
            return;
        }
        if (!canonical.isFile() || canonical.length() < SQLITE_HEADER.length) return;
        String name = canonical.getName();
        if (name.endsWith("-wal") || name.endsWith("-shm") || name.endsWith("-journal")) return;
        byte[] header = new byte[SQLITE_HEADER.length];
        try (FileInputStream input = new FileInputStream(canonical)) {
            int offset = 0;
            while (offset < header.length) {
                int count = input.read(header, offset, header.length - offset);
                if (count < 0) return;
                offset += count;
            }
        } catch (IOException ignored) { return; }
        if (Arrays.equals(SQLITE_HEADER, header)) result.add(canonical);
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", new String[]{table})) {
            return cursor.moveToFirst();
        }
    }

    private static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> result = new LinkedHashSet<>();
        // table is always one of the two fixed literals above.
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(\"" + table + "\")", null)) {
            int name = cursor.getColumnIndexOrThrow("name");
            while (cursor.moveToNext()) result.add(cursor.getString(name));
        }
        return result;
    }

    private static String projection(Set<String> columns, List<String> allowed, String proto) {
        List<String> expressions = new ArrayList<>();
        for (String column : allowed) if (columns.contains(column)) expressions.add("\"" + column + "\"");
        if (columns.contains(proto)) {
            expressions.add("length(\"" + proto + "\") AS _proto_length");
            expressions.add("CASE WHEN length(\"" + proto + "\") <= " + MAX_PROTO_BYTES
                    + " THEN \"" + proto + "\" ELSE NULL END AS _proto");
        }
        return String.join(",", expressions);
    }

    private static void readVideos(SQLiteDatabase db, int source, Export out) throws Exception {
        Set<String> fields = columns(db, "videosV2");
        if (!fields.contains("id")) throw new IOException("Native video ID column unavailable");
        List<String> allowed = Arrays.asList("id", "deleted", "media_status", "saved_timestamp", "preferred_stream_quality");
        String select = projection(fields, allowed, "player_response_proto");
        int remaining = MAX_ROWS - out.rows.length();
        try (Cursor cursor = db.rawQuery("SELECT " + select + " FROM videosV2 LIMIT " + (remaining + 1), null)) {
            while (cursor.moveToNext()) {
                if (out.rows.length() >= MAX_ROWS) { out.truncated = true; break; }
                JSONObject row = new JSONObject().put("sourceDatabase", source);
                String id = videoId(cursor, "id");
                if (id != null) row.put("videoId", id);
                copyLong(cursor, row, "deleted", "deleted");
                copyLong(cursor, row, "media_status", "mediaStatus");
                copyLong(cursor, row, "saved_timestamp", "savedTimestamp");
                copyLong(cursor, row, "preferred_stream_quality", "preferredStreamQuality");
                byte[] proto = proto(cursor, row);
                if (proto != null) {
                    try {
                        PlayerResponse response = PlayerResponse.parseFrom(proto);
                        if (!response.hasVideoDetails()) row.put("parseStatus", "missing_video_details");
                        else {
                            VideoDetails details = response.getVideoDetails();
                            String detailsId = validVideoId(details.getVideoId()) ? details.getVideoId() : null;
                            if (id != null && detailsId != null && !id.equals(detailsId)) {
                                row.put("parseStatus", "video_id_mismatch");
                            } else {
                                if (id == null && detailsId != null) row.put("videoId", detailsId);
                                putText(row, "title", details.getTitle(), 4096);
                                putText(row, "channel", details.getOwnerChannelName(), 512);
                                if (details.getLengthSeconds() > 0) row.put("durationSeconds", details.getLengthSeconds());
                                row.put("parseStatus", "parsed");
                            }
                        }
                    } catch (Exception ignored) { row.put("parseStatus", "proto_invalid"); }
                    finally { Arrays.fill(proto, (byte) 0); }
                }
                out.rows.put(row);
            }
        }
    }

    private static void readStreams(SQLiteDatabase db, int source, Export out) throws Exception {
        Set<String> fields = columns(db, "streams");
        if (!fields.contains("video_id") || !fields.contains("itag")) {
            throw new IOException("Native stream identity columns unavailable");
        }
        List<String> allowed = Arrays.asList("video_id", "itag", "duration_millis", "audio_only", "bytes_total", "bytes_transferred");
        String select = projection(fields, allowed, "format_stream_proto");
        int remaining = MAX_ROWS - out.rows.length();
        try (Cursor cursor = db.rawQuery("SELECT " + select + " FROM streams LIMIT " + (remaining + 1), null)) {
            while (cursor.moveToNext()) {
                if (out.rows.length() >= MAX_ROWS) { out.truncated = true; break; }
                JSONObject row = new JSONObject().put("sourceDatabase", source);
                String id = videoId(cursor, "video_id");
                if (id != null) row.put("videoId", id);
                copyLong(cursor, row, "itag", "itag");
                copyLong(cursor, row, "duration_millis", "durationMillis");
                copyLong(cursor, row, "bytes_total", "bytesTotal");
                copyLong(cursor, row, "bytes_transferred", "bytesTransferred");
                int audio = cursor.getColumnIndex("audio_only");
                if (audio >= 0 && !cursor.isNull(audio) && cursor.getType(audio) == Cursor.FIELD_TYPE_INTEGER) {
                    long value = cursor.getLong(audio);
                    if (value == 0 || value == 1) row.put("audioOnly", value == 1);
                }
                byte[] proto = proto(cursor, row);
                if (proto != null) {
                    try {
                        Format format = Format.parseFrom(proto);
                        if (format.getItag() > 0) row.put("formatItag", format.getItag());
                        if (format.getWidth() > 0) row.put("width", format.getWidth());
                        if (format.getHeight() > 0) row.put("height", format.getHeight());
                        if (format.getContentLength() > 0) row.put("contentLength", format.getContentLength());
                        if (format.getBitrate() > 0) row.put("bitrate", format.getBitrate());
                        if (format.getApproxDurationMs() > 0) row.put("formatDurationMillis", format.getApproxDurationMs());
                        if (format.getAudioChannels() > 0) row.put("audioChannels", format.getAudioChannels());
                        if (format.getAudioSampleRate() > 0) row.put("audioSampleRate", format.getAudioSampleRate());
                        String mime = format.getMimeType();
                        // MIME/codecs only: arbitrary strings, especially URLs, are not exported.
                        if (mime.matches("(?:audio|video)/[A-Za-z0-9.+_-]+(?:;\\s*codecs=\"[A-Za-z0-9., _-]{1,160}\")?")) {
                            putText(row, "mimeType", mime, 256);
                        }
                        row.put("parseStatus", "parsed");
                    } catch (Exception ignored) { row.put("parseStatus", "proto_invalid"); }
                    finally { Arrays.fill(proto, (byte) 0); }
                }
                out.rows.put(row);
            }
        }
    }

    private static byte[] proto(Cursor cursor, JSONObject row) throws Exception {
        int sizeColumn = cursor.getColumnIndex("_proto_length");
        int dataColumn = cursor.getColumnIndex("_proto");
        if (sizeColumn < 0 || dataColumn < 0 || cursor.isNull(sizeColumn)) {
            row.put("parseStatus", "absent"); return null;
        }
        long size = cursor.getLong(sizeColumn);
        if (size < 1 || size > MAX_PROTO_BYTES || cursor.isNull(dataColumn)) {
            row.put("parseStatus", size > MAX_PROTO_BYTES ? "skipped_large" : "absent"); return null;
        }
        byte[] data = cursor.getBlob(dataColumn);
        if (data == null || data.length != size) { row.put("parseStatus", "blob_size_mismatch"); return null; }
        return data;
    }

    private static void copyLong(Cursor cursor, JSONObject row, String column, String field) throws Exception {
        int index = cursor.getColumnIndex(column);
        if (index >= 0 && !cursor.isNull(index) && cursor.getType(index) == Cursor.FIELD_TYPE_INTEGER) {
            row.put(field, cursor.getLong(index));
        }
    }

    private static String videoId(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) return null;
        String value = cursor.getString(index);
        return validVideoId(value) ? value : null;
    }

    private static boolean validVideoId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    private static void putText(JSONObject row, String field, String value, int limit) throws Exception {
        if (value != null && !value.isEmpty()) row.put(field, value.substring(0, Math.min(value.length(), limit)));
    }

    private static void atomicWrite(File directory, String name, JSONObject object) throws Exception {
        File temporary = File.createTempFile(name + "-", ".part", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(object.toString().getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            Files.move(temporary.toPath(), new File(directory, name).toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    private static final class Export {
        final JSONArray rows = new JSONArray();
        int databasesInspected, databaseFailures, matchingTables, tableFailures;
        boolean truncated;
        JSONObject json() throws Exception {
            String status = matchingTables == 0 ? "not_found"
                    : databaseFailures > 0 || tableFailures > 0 || truncated ? "partial" : "ok";
            return new JSONObject().put("schemaVersion", 1).put("status", status)
                    .put("rows", rows).put("databasesInspected", databasesInspected)
                    .put("databaseFailures", databaseFailures).put("matchingTables", matchingTables)
                    .put("tableFailures", tableFailures).put("truncated", truncated)
                    .put("rowLimit", MAX_ROWS).put("databaseLimit", MAX_DATABASES).put("protoByteLimit", MAX_PROTO_BYTES);
        }
    }
}
