package dev.mback.oneplus;

/** Validated device-independent Flyme corner arc preferences. */
final class ArcConfig {
    static final String RADIUS_KEY = "mback_arc_radius_percent";
    static final String ICON_SIZE_KEY = "mback_arc_icon_size_dp";
    static final String ORIGINAL_ICON_KEY = "mback_arc_original_icons";

    static final int RADIUS_MIN = 50, RADIUS_MAX = 86, RADIUS_STEP = 2, DEFAULT_RADIUS = 72;
    static final int ICON_MIN = 28, ICON_MAX = 64, ICON_STEP = 2, DEFAULT_ICON = 44;
    static final String HOVER_VIBRATION_KEY = "mback_corner_hover_vibration";
    static final int DEFAULT_HOVER_VIBRATION = 3;
    static final boolean DEFAULT_ORIGINAL = false;

    static int radius(int value) { return snap(value, RADIUS_MIN, RADIUS_MAX, RADIUS_STEP); }
    static int icon(int value) { return snap(value, ICON_MIN, ICON_MAX, ICON_STEP); }
    static int hoverVibration(int level) { return Math.max(0, Math.min(10, level)); }

    private static int snap(int value, int min, int max, int step) {
        int limited = Math.max(min, Math.min(max, value));
        return min + Math.round((limited - min) / (float) step) * step;
    }

    private ArcConfig() {}
}
