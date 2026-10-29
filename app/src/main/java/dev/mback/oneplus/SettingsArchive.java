package dev.mback.oneplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Device-independent user settings and assets; runtime probes are deliberately excluded. */
final class SettingsArchive {
    static final int MAX_FILES = 12000, MAX_FILE = 2 * 1024 * 1024, MAX_TOTAL = 64 * 1024 * 1024;
    private static final String MANIFEST = "mback.properties";
    private static final String[] DIRECTORIES = {"shortcut-icons", "icon-packs"};
    static final Set<String> KEYS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
            "mback_xposed_enabled", "mback_scale_percent", "mback_scale_duration_ms", "mback_vibration_level",
            "mback_corner_menu_enabled", "mback_corner_menu_apps", "mback_shortcuts_v2",
            "mback_inner_shortcuts_v1", "mback_outer_shortcuts_v1", "mback_arc_radius_percent",
            "mback_arc_icon_size_dp", "mback_arc_original_icons", "mback_arc_side_margin_dp",
            "mback_arc_bottom_margin_dp", "mback_corner_hover_vibration", "mback_corner_dim_percent",
            "mback_corner_keep_open", "mback_corner_animation_ms", "mback_corner_bottom_length_dp",
            "mback_corner_bottom_height_dp", "mback_corner_side_length_dp", "mback_corner_side_width_dp",
            "mback_corner_triangle_width_dp", "mback_corner_triangle_height_dp",
            "mback_outer_ring_enabled", "mback_outer_ring_recent", "mback_outer_ring_count",
            "mback_outer_ring_icon_dp", "mback_inner_ring_enabled", "mback_inner_ring_recent",
            "mback_inner_ring_count", "mback_inner_ring_icon_dp", "mback_transit_direct",
            "mback_window_position_enabled", "mback_window_x_percent", "mback_window_y_percent",
            "mback_window_outside_action", "mback_window_block_outside", "mback_icon_pack_map",
            "mback_window_handle_double_tap", "mback_window_handle_hold_move", "mback_window_handle_vibration")));

    interface Preferences {
        Map<String, String> read() throws Exception;
        boolean notification();
        void write(Map<String, String> values, boolean notification) throws Exception;
    }

    static final class Snapshot {
        final Map<String, String> settings;
        final Map<String, byte[]> files;
        final boolean notification;
        final long created;

        Snapshot(Map<String, String> settings, Map<String, byte[]> files, boolean notification, long created) {
            this.settings = new LinkedHashMap<>(settings);
            this.files = new LinkedHashMap<>(files);
            this.notification = notification;
            this.created = created;
        }

        int icons() { return count("shortcut-icons/"); }
        int packs() { return count("icon-packs/"); }
        private int count(String prefix) {
            int count = 0;
            for (String name : files.keySet()) if (name.startsWith(prefix)) count++;
            return count;
        }
    }

    static Snapshot capture(File root, Preferences preferences) throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        for (String directory : DIRECTORIES) {
            File parent = new File(root, directory);
            File[] children = parent.listFiles();
            if (children == null) {
                if (parent.exists()) throw new IOException("无法读取图标目录");
                continue;
            }
            Arrays.sort(children);
            for (File child : children) {
                if (child.getName().startsWith("import-")) continue;
                if (!child.isFile() || !child.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile()))
                    throw new IOException("图标目录含无效文件");
                String name = directory + "/" + child.getName();
                checkName(name);
                if (child.length() > MAX_FILE || (total += child.length()) > MAX_TOTAL || files.size() >= MAX_FILES)
                    throw new IOException("图标数据超过备份上限（64 MB）");
                try (InputStream input = new FileInputStream(child)) { files.put(name, bytes(input, MAX_FILE)); }
            }
        }
        Snapshot result = new Snapshot(preferences.read(), files, preferences.notification(), System.currentTimeMillis());
        validate(result);
        return result;
    }

    static void write(OutputStream destination, Snapshot snapshot) throws Exception {
        validate(snapshot);
        Properties manifest = new Properties();
        manifest.setProperty("product", "MBack");
        manifest.setProperty("format", "1");
        manifest.setProperty("created", Long.toString(snapshot.created));
        manifest.setProperty("show_notification", Boolean.toString(snapshot.notification));
        for (Map.Entry<String, String> setting : snapshot.settings.entrySet())
            manifest.setProperty("setting." + setting.getKey(), setting.getValue());
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        manifest.store(encoded, "MBack settings backup");
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            put(zip, MANIFEST, encoded.toByteArray());
            for (Map.Entry<String, byte[]> file : snapshot.files.entrySet()) put(zip, file.getKey(), file.getValue());
        }
    }

    static Snapshot read(InputStream source) throws Exception {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        byte[] manifest = null;
        long total = 0;
        TailInputStream tracked = new TailInputStream(source);
        try (ZipInputStream zip = new ZipInputStream(tracked)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !seen.add(name) || seen.size() > MAX_FILES + 1)
                    throw new IOException("备份含重复或无效项目");
                if (!MANIFEST.equals(name)) checkName(name);
                byte[] data = bytes(zip, MANIFEST.equals(name) ? 1024 * 1024 : MAX_FILE);
                if (!MANIFEST.equals(name) && (total += data.length) > MAX_TOTAL)
                    throw new IOException("备份解压后超过 64 MB");
                if (MANIFEST.equals(name)) manifest = data;
                else files.put(name, data);
                zip.closeEntry();
            }
            byte[] remaining = new byte[8192];
            while (tracked.read(remaining) != -1) { /* Read the central-directory footer before accepting a file. */ }
            tracked.check(seen.size());
        }
        if (manifest == null) throw new IOException("这不是 MBack 备份文件");
        Properties properties = new Properties();
        properties.load(new ByteArrayInputStream(manifest));
        if (!"MBack".equals(properties.getProperty("product")) || !"1".equals(properties.getProperty("format")))
            throw new IOException("不支持此备份格式，请更新 MBack");
        String notification = properties.getProperty("show_notification");
        if (!"true".equals(notification) && !"false".equals(notification)) throw new IOException("备份通知设置无效");
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            // Retired in 0.8.2; accept old backups without restoring the removed gesture.
            if (key.equals("setting.mback_window_handle_swipe_preview")) continue;
            if (key.startsWith("setting.")) settings.put(key.substring(8), properties.getProperty(key));
            else if (!Arrays.asList("product", "format", "created", "show_notification").contains(key))
                throw new IOException("备份含未知字段");
        }
        long created;
        try { created = Long.parseLong(properties.getProperty("created", "")); }
        catch (NumberFormatException error) { throw new IOException("备份日期无效", error); }
        Snapshot snapshot = new Snapshot(settings, files, Boolean.parseBoolean(notification), created);
        validate(snapshot);
        return snapshot;
    }

    static void validate(Snapshot snapshot) throws Exception {
        if (snapshot.created < 0 || snapshot.files.size() > MAX_FILES) throw new IOException("备份数据无效");
        for (Map.Entry<String, String> entry : snapshot.settings.entrySet()) {
            if (!KEYS.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().length() > 768 * 1024
                    || entry.getValue().indexOf('\0') >= 0) throw new IOException("备份含无效或非用户设置");
        }
        long total = 0;
        for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet()) {
            checkName(entry.getKey());
            byte[] data = entry.getValue();
            if (data == null || data.length == 0 || data.length > MAX_FILE || (total += data.length) > MAX_TOTAL)
                throw new IOException("图标数据大小无效");
            if (entry.getKey().startsWith("shortcut-icons/")) {
                String hash = entry.getKey().substring(15, 79);
                if (!hash.equals(hash(data))) throw new IOException("图标校验失败，备份可能已损坏");
                byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
                if (data.length < 33 || !Arrays.equals(signature, Arrays.copyOf(data, 8))
                        || data[12] != 'I' || data[13] != 'H' || data[14] != 'D' || data[15] != 'R'
                        || dimension(data, 16) < 1 || dimension(data, 16) > 1024
                        || dimension(data, 20) < 1 || dimension(data, 20) > 1024)
                    throw new IOException("图标图片格式或尺寸无效");
            }
        }
    }

    static void restore(File root, Snapshot snapshot, Preferences preferences) throws Exception {
        validate(snapshot);
        Map<String, String> oldSettings = new LinkedHashMap<>(preferences.read());
        boolean oldNotification = preferences.notification();
        File work = Files.createTempDirectory(root.toPath(), "mback-restore-").toFile();
        File staged = new File(work, "new"), old = new File(work, "old");
        boolean[] moved = new boolean[DIRECTORIES.length], installed = new boolean[DIRECTORIES.length];
        boolean settingsStarted = false, success = false, rolledBack = false;
        try {
            if (!staged.mkdirs() || !old.mkdirs()) throw new IOException("无法准备恢复目录");
            for (String directory : DIRECTORIES)
                if (!new File(staged, directory).mkdir()) throw new IOException("无法准备图标目录");
            for (Map.Entry<String, byte[]> entry : snapshot.files.entrySet())
                try (OutputStream output = new FileOutputStream(new File(staged, entry.getKey()))) { output.write(entry.getValue()); }
            for (int i = 0; i < DIRECTORIES.length; i++) {
                File current = new File(root, DIRECTORIES[i]);
                if (current.exists()) {
                    if (!current.renameTo(new File(old, DIRECTORIES[i]))) throw new IOException("无法保留原图标");
                    moved[i] = true;
                }
                if (!new File(staged, DIRECTORIES[i]).renameTo(current)) throw new IOException("无法恢复图标");
                installed[i] = true;
            }
            settingsStarted = true;
            preferences.write(snapshot.settings, snapshot.notification);
            success = true;
        } catch (Exception error) {
            try {
                for (int i = DIRECTORIES.length - 1; i >= 0; i--) {
                    File current = new File(root, DIRECTORIES[i]);
                    if (installed[i]) delete(current);
                    if (moved[i] && !new File(old, DIRECTORIES[i]).renameTo(current)) throw new IOException("无法回退图标");
                }
                if (settingsStarted && (!oldSettings.equals(preferences.read()) || oldNotification != preferences.notification()))
                    preferences.write(oldSettings, oldNotification);
                rolledBack = true;
            } catch (Exception rollback) {
                error.addSuppressed(rollback);
                throw new IOException("恢复失败且回退未完成；原图标保留于 " + work.getAbsolutePath(), error);
            }
            throw error;
        } finally {
            if (success || rolledBack) delete(work);
        }
    }

    private static int dimension(byte[] data, int at) {
        return (data[at] & 255) << 24 | (data[at + 1] & 255) << 16 | (data[at + 2] & 255) << 8 | data[at + 3] & 255;
    }

    private static void checkName(String name) throws IOException {
        if (!name.matches("shortcut-icons/[0-9a-f]{64}\\.png")
                && !name.matches("icon-packs/[0-9a-f]{64}\\.json")) throw new IOException("备份文件路径无效");
    }

    static String hash(byte[] data) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data))
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static byte[] bytes(InputStream source, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = source.read(buffer)) != -1) {
            if (output.size() > limit - length) throw new IOException("备份项目超过大小上限");
            output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static final class TailInputStream extends InputStream {
        private final InputStream source;
        private final byte[] tail = new byte[22];
        private long count;
        TailInputStream(InputStream source) { this.source = source; }
        @Override public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) == -1 ? -1 : single[0] & 255;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            int result = source.read(data, offset, length);
            if (result > 0) {
                if (count + result > MAX_TOTAL + 4 * 1024 * 1024) throw new IOException("备份文件超过大小上限");
                for (int i = 0; i < result; i++) tail[(int) (count++ % 22)] = data[offset + i];
            }
            return result;
        }
        void check(int entries) throws IOException {
            byte[] end = new byte[22];
            for (int i = 0; i < 22; i++) end[i] = tail[(int) ((count + i) % 22)];
            if (count < 22 || end[0] != 'P' || end[1] != 'K' || end[2] != 5 || end[3] != 6
                    || little(end, 4, 2) != 0 || little(end, 6, 2) != 0
                    || little(end, 8, 2) != entries || little(end, 10, 2) != entries
                    || little(end, 20, 2) != 0
                    || little(end, 12, 4) + little(end, 16, 4) != count - 22)
                throw new IOException("备份文件不完整或已损坏");
        }
        private static long little(byte[] data, int at, int length) {
            long result = 0;
            for (int i = 0; i < length; i++) result |= (data[at + i] & 255L) << (8 * i);
            return result;
        }
        @Override public void close() throws IOException { source.close(); }
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private SettingsArchive() {}
}
