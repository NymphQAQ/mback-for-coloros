package dev.mback.oneplus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Shared input limits for icon archives and their component names. */
final class IconPackRules {
    static final int MAX_ENTRIES = 50000;
    static final int MAX_ICONS = 8000;
    static final int MAX_MAP_BYTES = 96 * 1024;
    static final long MAX_ARCHIVE_BYTES = 200L * 1024 * 1024;

    static boolean safePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512 || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) return false;
        for (String part : path.split("/", -1)) if (part.equals("..") || part.equals(".")) return false;
        return true;
    }

    static String component(String value) {
        if (value == null || value.length() > 512) return null;
        if (value.startsWith("ComponentInfo{") && value.endsWith("}")) value = value.substring(14, value.length() - 1);
        ShortcutTarget target = ShortcutTarget.parse(value);
        return target == null ? null : target.id();
    }

    static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[16384];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("文件超过允许的大小");
            output.write(buffer, 0, count);
        }
        return total;
    }

    private IconPackRules() {}
}
