package dev.mback.oneplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Runs the production archive core without Android or a connected phone. */
public final class SettingsArchiveTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jk1kAAAAASUVORK5CYII=");

    private static final class Preferences implements SettingsArchive.Preferences {
        Map<String, String> values = new LinkedHashMap<>();
        boolean notification, failNext;
        int writes;
        @Override public Map<String, String> read() { return new LinkedHashMap<>(values); }
        @Override public boolean notification() { return notification; }
        @Override public void write(Map<String, String> values, boolean notification) throws Exception {
            writes++;
            this.values = new LinkedHashMap<>(values);
            this.notification = notification;
            if (failNext) { failNext = false; throw new IOException("simulated partial settings write"); }
        }
    }

    private interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String scenario) throws Exception {
        try { attempt.run(); throw new AssertionError("Accepted " + scenario); }
        catch (IOException expected) { }
    }

    private static byte[] encoded(SettingsArchive.Snapshot snapshot) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SettingsArchive.write(output, snapshot);
        return output.toByteArray();
    }

    private static SettingsArchive.Snapshot read(byte[] data) throws Exception {
        return SettingsArchive.read(new ByteArrayInputStream(data));
    }

    private static byte[] zip(String name, byte[] content) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(content); zip.closeEntry();
        }
        return output.toByteArray();
    }

    private static void delete(File file) throws Exception {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        Files.deleteIfExists(file.toPath());
    }

    public static void main(String[] args) throws Exception {
        String icon = SettingsArchive.hash(PNG);
        String image = "shortcut-icons/" + icon + ".png";
        Map<String, String> values = new LinkedHashMap<>();
        values.put("mback_window_x_percent", "27");
        values.put("mback_window_handle_double_tap", "2");
        values.put("mback_window_handle_vibration", "8");
        values.put("mback_shortcuts_v2", "[{\"id\":\"com.example/.Main\",\"icon\":\"" + icon + "\"}]");
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put(image, PNG);
        SettingsArchive.Snapshot source = new SettingsArchive.Snapshot(values, files, true, 123456789L);
        byte[] encoded = encoded(source);
        SettingsArchive.Snapshot decoded = read(encoded);
        assert decoded.settings.equals(values) && decoded.notification && decoded.created == source.created;
        assert decoded.icons() == 1 && decoded.packs() == 0;
        assert Arrays.equals(decoded.files.get(image), PNG);

        reject(() -> read(Arrays.copyOf(encoded, encoded.length / 2)), "truncated icon data");
        reject(() -> read(Arrays.copyOf(encoded, encoded.length - 1)), "truncated ZIP footer");
        reject(() -> read(Arrays.copyOf(encoded, encoded.length - 22)), "missing ZIP footer");
        reject(() -> read(zip("../outside.png", PNG)), "path traversal");
        reject(() -> read(zip("/shortcut-icons/" + icon + ".png", PNG)), "absolute path");
        reject(() -> read(zip("icon-packs/" + icon + ".json", new byte[SettingsArchive.MAX_FILE + 1])), "oversized entry");
        Map<String, byte[]> wrongHash = new LinkedHashMap<>(files);
        byte[] changed = PNG.clone(); changed[changed.length - 1] ^= 1; wrongHash.put(image, changed);
        reject(() -> encoded(new SettingsArchive.Snapshot(values, wrongHash, true, 1)), "incorrect icon hash");
        Map<String, String> runtime = new LinkedHashMap<>(values); runtime.put("mback_window_hook_ready", "old:123");
        reject(() -> encoded(new SettingsArchive.Snapshot(runtime, files, true, 1)), "runtime state");
        assert !SettingsArchive.KEYS.contains("boot_count");
        assert !SettingsArchive.KEYS.contains("mback_icon_assets_revision");
        assert !SettingsArchive.KEYS.contains("mback_window_handle_swipe_preview");
        byte[] legacy = zip("mback.properties", ("product=MBack\nformat=1\ncreated=1\n"
                + "show_notification=false\nsetting.mback_window_handle_swipe_preview=1\n"
                + "setting.mback_window_handle_hold_move=1\n").getBytes("UTF-8"));
        SettingsArchive.Snapshot old = read(legacy);
        assert old.settings.size() == 1 && "1".equals(old.settings.get("mback_window_handle_hold_move"));

        File directory = Files.createTempDirectory("mback-archive-test-").toFile();
        try {
            File originals = new File(directory, "shortcut-icons"); assert originals.mkdir();
            byte[] prior = PNG.clone(); prior[prior.length - 1] ^= 2;
            String oldImage = SettingsArchive.hash(prior) + ".png";
            File oldIcon = new File(originals, oldImage); Files.write(oldIcon.toPath(), prior);
            Preferences preferences = new Preferences(); preferences.values.put("mback_window_y_percent", "81");
            Map<String, String> before = new LinkedHashMap<>(preferences.values);
            preferences.failNext = true;
            reject(() -> SettingsArchive.restore(directory, decoded, preferences), "failed root settings write");
            assert preferences.values.equals(before) && !preferences.notification;
            assert preferences.writes == 2 : "Settings were not rolled back";
            assert Arrays.equals(Files.readAllBytes(oldIcon.toPath()), prior);
            assert !new File(directory, image).exists();
            assert directory.listFiles().length == 1 : "Staging files leaked after rollback";

            SettingsArchive.restore(directory, decoded, preferences);
            assert preferences.values.equals(values) && preferences.notification;
            assert !oldIcon.exists();
            assert Arrays.equals(Files.readAllBytes(new File(directory, image).toPath()), PNG);
            SettingsArchive.Snapshot captured = SettingsArchive.capture(directory, preferences);
            assert captured.settings.equals(values) && captured.icons() == 1 && captured.notification;
            assert new File(directory, "icon-packs").isDirectory();
        } finally { delete(directory); }
        System.out.println("SettingsArchive: round trip, truncated ZIP, traversal, size/hash checks, runtime exclusions and transactional rollback passed");
    }
}
