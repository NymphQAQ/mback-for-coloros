package dev.mback.oneplus;

/** Stable IDs keep legacy package shortcuts compatible with explicit activities. */
final class ShortcutTarget {
    final String packageName;
    final String activityName;

    private ShortcutTarget(String pkg, String activity) {
        packageName = pkg;
        activityName = activity;
    }

    static ShortcutTarget parse(String value) {
        if (value == null || value.isEmpty() || "none".equals(value)) return null;
        String[] parts = value.split("/", -1);
        if (parts.length > 2 || !parts[0].matches("[a-zA-Z_][a-zA-Z0-9_.]*")) return null;
        String activity = null;
        if (parts.length == 2) {
            activity = parts[1];
            if (activity.startsWith(".")) activity = parts[0] + activity;
            if (!activity.matches("[a-zA-Z_][a-zA-Z0-9_.$]*")) return null;
        }
        return new ShortcutTarget(parts[0], activity);
    }

    String id() { return activityName == null ? packageName : packageName + "/" + activityName; }
}
