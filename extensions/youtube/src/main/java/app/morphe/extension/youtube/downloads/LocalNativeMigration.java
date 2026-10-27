/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.youtube.downloads;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Worker-only, local staging of the app's legacy encrypted offline cache.
 *
 * <p>This does not import or publish playable videos. Staged streams must still
 * pass media/container/completeness validation before muxing and importing.
 * Native files are never changed or removed, and the AES key is never written
 * to disk, a report or a log. Index/cache metadata remains under this app's
 * private external-files directory, not Downloads or shared storage.</p>
 */
public final class LocalNativeMigration {
    private static final String KEY_NAME = "downloads_encryption_key";
    private static final String STAGING_NAME = "MorpheNativeMigration";
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_GROUPS = 4096;
    private static final int MAX_CACHE_DEPTH = 6;
    private static final int MAX_CACHE_NODES = 262144;
    private static final int MAX_METADATA_ENTRIES = 1024;
    private static final int MAX_METADATA_VALUE_BYTES = 1024 * 1024;
    private static final int BUFFER_BYTES = 128 * 1024;
    private static final long DISK_RESERVE_BYTES = 64L * 1024 * 1024;
    private static final Pattern SEGMENT = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.v3\\.exo");

    private LocalNativeMigration() { }

    /** Counts transformed staging files, not verified or imported videos. */
    public static final class Result {
        public final File directory;
        public final int decodedGroups;
        public final int skippedGroups;
        public final long decodedBytes;

        private Result(File directory, int decodedGroups, int skippedGroups, long decodedBytes) {
            this.directory = directory;
            this.decodedGroups = decodedGroups;
            this.skippedGroups = skippedGroups;
            this.decodedBytes = decodedBytes;
        }
    }

    /** Call only from a worker thread. A failed attempt leaves the native cache intact. */
    public static synchronized Result stage(Context context) throws Exception {
        Context app = context.getApplicationContext();
        File external = app.getExternalFilesDir(null);
        if (external == null || !external.isDirectory()) throw failure("EXTERNAL_FILES_UNAVAILABLE");
        File offline = new File(external, "offline").getCanonicalFile();
        if (!external.getCanonicalFile().equals(offline.getParentFile())) throw failure("NATIVE_CACHE_PATH_INVALID");
        if (!offline.isDirectory()) throw failure("NATIVE_CACHE_MISSING");
        File indexFile = findIndex(offline);
        // Native YouTube uses offline/<profile>/streams/<numeric>/ for segments.
        // Never combine IDs belonging to different index/cache directories.
        File cacheRoot = indexFile.getCanonicalFile().getParentFile();
        requireChild(indexFile, cacheRoot);
        long indexLength = indexFile.length(), indexTime = indexFile.lastModified();
        byte[] sourceIndex = readBounded(indexFile, MAX_INDEX_BYTES);
        if (indexFile.length() != indexLength || indexFile.lastModified() != indexTime)
            throw failure("INDEX_CHANGED");

        KeyIndex selected = findKeyAndIndex(app, sourceIndex);
        try {
            TreeMap<Integer, Group> groups = collectGroups(cacheRoot);
            if (groups.isEmpty()) throw failure("NATIVE_SEGMENTS_MISSING");
            String sourceFingerprint = hex(digest(sourceIndex));
            File stagingParent = new File(external, STAGING_NAME);
            if (!external.getCanonicalFile().equals(stagingParent.getCanonicalFile().getParentFile()))
                throw failure("STAGING_PATH_INVALID");
            File directory = new File(stagingParent, sourceFingerprint.substring(0, 16));
            if ((!directory.isDirectory() && !directory.mkdirs()) ||
                    !directory.getCanonicalFile().getParentFile().equals(stagingParent.getCanonicalFile()))
                throw failure("STAGING_UNAVAILABLE");

            long needed = 0;
            for (Group group : groups.values()) {
                if (!group.contiguous || !selected.index.entries.containsKey(group.id)) continue;
                Entry entry = selected.index.entries.get(group.id);
                String fingerprint = groupFingerprint(group, entry);
                if (!reusable(directory, group, fingerprint)) needed = Math.addExact(needed, group.bytes);
            }
            if (directory.getUsableSpace() < Math.addExact(needed, DISK_RESERVE_BYTES))
                throw failure("INSUFFICIENT_STAGING_SPACE");

            writeAtomic(new File(directory, "index-decrypted-private.bin"), selected.plainIndex);
            JSONArray rows = new JSONArray();
            JSONObject manifest = new JSONObject()
                    .put("format", "morphe-native-staging-v1")
                    .put("indexVersion", 2)
                    .put("indexChecksumVerified", true)
                    .put("sourceIndexFingerprint", sourceFingerprint)
                    .put("mediaValidationPending", true)
                    .put("originalsChanged", false)
                    .put("groups", rows);
            File manifestFile = new File(directory, "manifest-private.json");
            writeAtomic(manifestFile, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            int decoded = 0, skipped = 0;
            long decodedBytes = 0;
            for (Group group : groups.values()) {
                interrupted();
                Entry entry = selected.index.entries.get(group.id);
                JSONObject row = new JSONObject().put("id", group.id)
                        .put("segmentCount", group.segments.size()).put("sourceBytes", group.bytes).put("stagedBytes", 0)
                        .put("firstOffset", group.segments.firstKey())
                        .put("contiguous", group.contiguous);
                rows.put(row);
                if (entry != null) {
                    row.put("cacheKey", entry.key).put("metadata", metadataJson(entry.metadata))
                            .put("declaredContentLength", contentLength(entry.metadata));
                }
                String reason = !group.contiguous ? "GAP_OR_OVERLAP" : entry == null ? "INDEX_ENTRY_MISSING" : null;
                if (reason == null) {
                    String fingerprint = groupFingerprint(group, entry);
                    row.put("sourceFingerprint", fingerprint);
                    try {
                        File output = new File(directory, group.id + ".stream");
                        if (!reusable(directory, group, fingerprint)) {
                            String plainHash = decryptGroup(group, entry.key, selected.key, output);
                            JSONObject done = new JSONObject().put("sourceFingerprint", fingerprint)
                                    .put("size", group.bytes).put("sha256", plainHash);
                            writeAtomic(new File(directory, group.id + ".done-private.json"),
                                    done.toString().getBytes(StandardCharsets.UTF_8));
                        }
                        JSONObject done = new JSONObject(new String(readBounded(
                                new File(directory, group.id + ".done-private.json"), 4096), StandardCharsets.UTF_8));
                        row.put("file", output.getName()).put("sha256", done.getString("sha256"))
                                .put("stagedBytes", group.bytes).put("status", "staged");
                        decoded++;
                        decodedBytes = Math.addExact(decodedBytes, group.bytes);
                    } catch (InterruptedException e) {
                        throw e;
                    } catch (Exception ignored) {
                        reason = "STAGING_OR_SOURCE_FAILED";
                    }
                }
                if (reason != null) {
                    row.put("status", "skipped").put("skipReason", reason);
                    skipped++;
                }
                manifest.put("decodedGroups", decoded).put("skippedGroups", skipped)
                        .put("decodedBytes", decodedBytes).put("stageComplete", false);
                writeAtomic(manifestFile, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            if (indexFile.length() != indexLength || indexFile.lastModified() != indexTime ||
                    !MessageDigest.isEqual(digest(sourceIndex), digest(readBounded(indexFile, MAX_INDEX_BYTES))))
                throw failure("INDEX_CHANGED");
            manifest.put("stageComplete", true);
            writeAtomic(manifestFile, manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            return new Result(directory, decoded, skipped, decodedBytes);
        } finally {
            Arrays.fill(selected.key, (byte) 0);
            Arrays.fill(selected.plainIndex, (byte) 0);
        }
    }

    private static File findIndex(File offline) throws IOException {
        File selected = null;
        for (File file : walkFiles(offline)) {
            if (!file.getName().endsWith(".exi")) continue;
            if (selected != null) throw failure("NATIVE_INDEX_AMBIGUOUS");
            selected = file;
        }
        if (selected == null) throw failure("NATIVE_INDEX_MISSING");
        return selected;
    }

    private static KeyIndex findKeyAndIndex(Context app, byte[] source) throws Exception {
        File prefs = new File(app.getApplicationInfo().dataDir, "shared_prefs").getCanonicalFile();
        File[] files = prefs.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null || files.length > 4096) throw failure("NATIVE_KEY_UNAVAILABLE");
        for (File file : files) {
            interrupted();
            if (!file.isFile() || !prefs.equals(file.getCanonicalFile().getParentFile())) continue;
            String name = file.getName().substring(0, file.getName().length() - 4);
            byte[] key = null, plain = null;
            boolean selected = false;
            try {
                String encoded = app.getSharedPreferences(name, Context.MODE_PRIVATE).getString(KEY_NAME, null);
                if (encoded == null || encoded.length() > 128) continue;
                key = Base64.decode(encoded, Base64.DEFAULT);
                // The native legacy index reader explicitly requires a 16-byte key.
                if (key.length != 16) continue;
                plain = decryptIndex(source, key);
                Index index = parseIndex(plain);
                selected = true;
                return new KeyIndex(key, plain, index);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception ignored) {
                // Another preference file can contain an obsolete key. Never log its value.
            } finally {
                if (!selected) {
                    if (key != null) Arrays.fill(key, (byte) 0);
                    if (plain != null) Arrays.fill(plain, (byte) 0);
                }
            }
        }
        throw failure("NATIVE_KEY_OR_INDEX_INVALID");
    }

    private static byte[] decryptIndex(byte[] source, byte[] key) throws Exception {
        if (source.length < 8) throw failure("NATIVE_INDEX_TRUNCATED");
        ByteBuffer header = ByteBuffer.wrap(source);
        int version = header.getInt(), flags = header.getInt();
        if (version != 2 || (flags & ~1) != 0) throw failure("NATIVE_INDEX_VERSION_UNSUPPORTED");
        if ((flags & 1) == 0) return Arrays.copyOfRange(source, 8, source.length);
        if (source.length < 40 || (source.length - 24) % 16 != 0) throw failure("NATIVE_INDEX_TRUNCATED");
        byte[] iv = Arrays.copyOfRange(source, 8, 24);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(source, 24, source.length - 24);
    }

    private static Index parseIndex(byte[] plain) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
        int count = bounded(in.readInt(), 0, MAX_GROUPS);
        TreeMap<Integer, Entry> entries = new TreeMap<>();
        int checksum = 0;
        for (int i = 0; i < count; i++) {
            int id = in.readInt();
            String key = in.readUTF();
            if (id < 0 || key.isEmpty() || entries.containsKey(id)) throw failure("NATIVE_INDEX_ENTRY_INVALID");
            int metadataCount = bounded(in.readInt(), 0, MAX_METADATA_ENTRIES);
            Map<String, byte[]> metadata = new LinkedHashMap<>();
            int metadataHash = 0;
            for (int m = 0; m < metadataCount; m++) {
                String name = in.readUTF();
                int size = bounded(in.readInt(), 0, MAX_METADATA_VALUE_BYTES);
                if (name.isEmpty() || metadata.containsKey(name) || size > in.available())
                    throw failure("NATIVE_INDEX_METADATA_INVALID");
                byte[] value = new byte[size];
                in.readFully(value);
                metadata.put(name, value);
                metadataHash += name.hashCode() ^ Arrays.hashCode(value);
            }
            checksum += (id * 31 + key.hashCode()) * 31 + metadataHash;
            entries.put(id, new Entry(key, metadata));
        }
        if (in.readInt() != checksum || in.read() != -1) throw failure("NATIVE_INDEX_CHECKSUM_INVALID");
        return new Index(entries);
    }

    private static TreeMap<Integer, Group> collectGroups(File cacheRoot) throws Exception {
        List<File> files = walkFiles(cacheRoot);
        TreeMap<Integer, Group> groups = new TreeMap<>();
        for (File file : files) {
            Matcher match = SEGMENT.matcher(file.getName());
            if (!match.matches() || !file.isFile()) continue;
            int id;
            long position;
            try {
                id = Integer.parseInt(match.group(1));
                position = Long.parseLong(match.group(2));
            } catch (NumberFormatException ignored) { continue; }
            Group group = groups.get(id);
            if (group == null) {
                if (groups.size() >= MAX_GROUPS) throw failure("NATIVE_GROUP_LIMIT");
                group = new Group(id);
                groups.put(id, group);
            }
            Segment segment = new Segment(file, position, file.length(), file.lastModified());
            if (segment.size <= 0 || group.segments.put(position, segment) != null) group.contiguous = false;
        }
        for (Group group : groups.values()) {
            long next = 0;
            for (Segment segment : group.segments.values()) {
                if (segment.position != next) group.contiguous = false;
                next = Math.addExact(segment.position, segment.size);
                group.bytes = Math.addExact(group.bytes, segment.size);
            }
        }
        return groups;
    }

    /** Bounded discovery rejects symlinks, unreadable branches and path escapes. */
    private static List<File> walkFiles(File directory) throws IOException {
        File root = directory.getCanonicalFile();
        if (!root.isDirectory()) throw failure("NATIVE_CACHE_DIRECTORY_MISSING");
        String prefix = root.getPath() + File.separator;
        ArrayDeque<WalkDirectory> pending = new ArrayDeque<>();
        List<File> files = new ArrayList<>();
        pending.add(new WalkDirectory(root, 0));
        int seen = 0;
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw failure("NATIVE_STAGING_CANCELLED");
            WalkDirectory current = pending.removeFirst();
            File[] children = current.file.listFiles();
            if (children == null) throw failure("NATIVE_CACHE_DIRECTORY_UNREADABLE");
            if (children.length > MAX_CACHE_NODES - seen) throw failure("NATIVE_CACHE_NODE_LIMIT");
            seen += children.length;
            for (File child : children) {
                if (current.depth + 1 > MAX_CACHE_DEPTH) throw failure("NATIVE_CACHE_DEPTH_LIMIT");
                File canonical = child.getCanonicalFile();
                File expected = new File(current.file, child.getName());
                if (!canonical.equals(expected) || !canonical.getPath().startsWith(prefix))
                    throw failure("NATIVE_CACHE_PATH_INVALID");
                if (canonical.isDirectory()) pending.add(new WalkDirectory(canonical, current.depth + 1));
                else if (canonical.isFile()) files.add(canonical);
            }
        }
        return files;
    }

    private static String decryptGroup(Group group, String cacheKey, byte[] key, File output) throws Exception {
        File temporary = new File(output.getParentFile(), output.getName() + ".part");
        requireChild(output, output.getParentFile());
        requireChild(temporary, output.getParentFile());
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            for (Segment segment : group.segments.values()) {
                interrupted();
                if (!segment.unchanged()) throw failure("NATIVE_SOURCE_CHANGED");
                Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
                byte[] iv = ByteBuffer.allocate(16).putLong(cacheNonce(cacheKey)).putLong(segment.position / 16).array();
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
                int skip = (int) (segment.position % 16);
                int discard = skip;
                if (skip != 0) {
                    byte[] advance = cipher.update(new byte[skip]);
                    if (advance != null) discard -= Math.min(discard, advance.length);
                }
                long read = 0;
                try (FileInputStream in = new FileInputStream(segment.file)) {
                    while (read < segment.size) {
                        interrupted();
                        int n = in.read(buffer, 0, (int) Math.min(buffer.length, segment.size - read));
                        if (n < 0) throw failure("NATIVE_SOURCE_TRUNCATED");
                        if (n == 0) continue;
                        byte[] bytes = cipher.update(buffer, 0, n);
                        if (bytes != null && bytes.length != 0) {
                            int drop = Math.min(discard, bytes.length);
                            discard -= drop;
                            out.write(bytes, drop, bytes.length - drop);
                            hash.update(bytes, drop, bytes.length - drop);
                        }
                        read += n;
                    }
                    if (in.read() != -1) throw failure("NATIVE_SOURCE_CHANGED");
                }
                byte[] tail = cipher.doFinal();
                if (tail.length != 0) {
                    int drop = Math.min(discard, tail.length);
                    discard -= drop;
                    out.write(tail, drop, tail.length - drop);
                    hash.update(tail, drop, tail.length - drop);
                }
                if (discard != 0) throw failure("NATIVE_CIPHER_POSITION_INVALID");
                if (!segment.unchanged()) throw failure("NATIVE_SOURCE_CHANGED");
            }
            out.getFD().sync();
        } catch (Exception e) {
            temporary.delete();
            throw e;
        }
        if (temporary.length() != group.bytes) {
            temporary.delete();
            throw failure("STAGING_SIZE_MISMATCH");
        }
        for (Segment segment : group.segments.values()) if (!segment.unchanged()) {
            temporary.delete();
            throw failure("NATIVE_SOURCE_CHANGED");
        }
        replace(temporary, output);
        return hex(hash.digest());
    }

    private static boolean reusable(File directory, Group group, String fingerprint) {
        try {
            for (Segment segment : group.segments.values()) if (!segment.unchanged()) return false;
            File output = new File(directory, group.id + ".stream");
            if (!output.isFile() || output.length() != group.bytes) return false;
            JSONObject done = new JSONObject(new String(readBounded(
                    new File(directory, group.id + ".done-private.json"), 4096), StandardCharsets.UTF_8));
            return fingerprint.equals(done.getString("sourceFingerprint")) && done.getLong("size") == group.bytes &&
                    done.getString("sha256").equals(hashFile(output));
        } catch (Exception ignored) { return false; }
    }

    private static String groupFingerprint(Group group, Entry entry) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(entry.key.getBytes(StandardCharsets.UTF_8));
        hash.update(ByteBuffer.allocate(4).putInt(group.id).array());
        for (Segment segment : group.segments.values()) {
            hash.update(ByteBuffer.allocate(24).putLong(segment.position).putLong(segment.size).putLong(segment.modified).array());
            hash.update(segment.file.getName().getBytes(StandardCharsets.UTF_8));
        }
        return hex(hash.digest());
    }

    private static long cacheNonce(String key) {
        long hash = 0;
        for (int i = 0; i < key.length(); i++) { hash ^= key.charAt(i); hash *= 1099511628211L; }
        return hash;
    }

    private static long contentLength(Map<String, byte[]> metadata) {
        byte[] value = metadata.get("exo_len");
        return value != null && value.length == 8 ? ByteBuffer.wrap(value).getLong() : -1;
    }

    private static JSONObject metadataJson(Map<String, byte[]> metadata) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, byte[]> entry : metadata.entrySet())
            json.put(entry.getKey(), Base64.encodeToString(entry.getValue(), Base64.NO_WRAP));
        return json;
    }

    private static int bounded(int value, int minimum, int maximum) throws IOException {
        if (value < minimum || value > maximum) throw failure("NATIVE_INDEX_BOUND_EXCEEDED");
        return value;
    }

    private static byte[] readBounded(File file, int maximum) throws IOException {
        if (!file.isFile() || file.length() < 0 || file.length() > maximum) throw failure("PRIVATE_FILE_BOUND_EXCEEDED");
        try (FileInputStream in = new FileInputStream(file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() > maximum - n) throw failure("PRIVATE_FILE_BOUND_EXCEEDED");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".part");
        requireChild(target, target.getParentFile());
        requireChild(temporary, target.getParentFile());
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); out.getFD().sync(); }
        replace(temporary, target);
    }

    private static void requireChild(File file, File parent) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!parent.getCanonicalFile().equals(canonical.getParentFile()) || !file.getName().equals(canonical.getName()))
            throw failure("PRIVATE_FILE_PATH_INVALID");
    }

    private static void replace(File temporary, File target) throws IOException {
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING); }
    }

    private static byte[] digest(byte[] value) throws Exception { return MessageDigest.getInstance("SHA-256").digest(value); }

    private static String hashFile(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            int n;
            while ((n = in.read(buffer)) != -1) { interrupted(); hash.update(buffer, 0, n); }
        }
        return hex(hash.digest());
    }

    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray(), out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) { out[i * 2] = digits[(value[i] & 255) >>> 4]; out[i * 2 + 1] = digits[value[i] & 15]; }
        return new String(out);
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("NATIVE_STAGING_CANCELLED");
    }

    private static IOException failure(String code) { return new IOException(code); }

    private static final class KeyIndex {
        final byte[] key, plainIndex;
        final Index index;
        KeyIndex(byte[] key, byte[] plainIndex, Index index) { this.key = key; this.plainIndex = plainIndex; this.index = index; }
    }

    private static final class Index {
        final TreeMap<Integer, Entry> entries;
        Index(TreeMap<Integer, Entry> entries) { this.entries = entries; }
    }

    private static final class Entry {
        final String key;
        final Map<String, byte[]> metadata;
        Entry(String key, Map<String, byte[]> metadata) { this.key = key; this.metadata = metadata; }
    }

    private static final class Group {
        final int id;
        final TreeMap<Long, Segment> segments = new TreeMap<>();
        long bytes;
        boolean contiguous = true;
        Group(int id) { this.id = id; }
    }

    private static final class Segment {
        final File file;
        final long position, size, modified;
        Segment(File file, long position, long size, long modified) { this.file = file; this.position = position; this.size = size; this.modified = modified; }
        boolean unchanged() { return file.isFile() && file.length() == size && file.lastModified() == modified; }
    }

    private static final class WalkDirectory {
        final File file;
        final int depth;
        WalkDirectory(File file, int depth) { this.file = file; this.depth = depth; }
    }
}
