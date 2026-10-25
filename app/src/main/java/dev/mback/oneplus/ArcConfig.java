package dev.mback.oneplus;

/** Validated device-independent Flyme corner arc preferences. */
final class ArcConfig {
    static final String RADIUS_KEY = "mback_arc_radius_percent";
    static final String ICON_SIZE_KEY = "mback_arc_icon_size_dp";
    static final String ORIGINAL_ICON_KEY = "mback_arc_original_icons";
    static final String SIDE_MARGIN_KEY = "mback_arc_side_margin_dp";
    static final String BOTTOM_MARGIN_KEY = "mback_arc_bottom_margin_dp";

    static final int RADIUS_MIN = 50, RADIUS_MAX = 86, RADIUS_STEP = 2, DEFAULT_RADIUS = 54;
    static final int ICON_MIN = 28, ICON_MAX = 64, ICON_STEP = 2, DEFAULT_ICON = 40;
    static final int MARGIN_MIN = 0, MARGIN_MAX = 64, MARGIN_STEP = 2;
    static final int DEFAULT_SIDE_MARGIN = 12, DEFAULT_BOTTOM_MARGIN = 6;
    static final String HOVER_VIBRATION_KEY = "mback_corner_hover_vibration";
    static final int DEFAULT_HOVER_VIBRATION = 3;
    static final boolean DEFAULT_ORIGINAL = false;

    static int radius(int value) { return snap(value, RADIUS_MIN, RADIUS_MAX, RADIUS_STEP); }
    static int icon(int value) { return snap(value, ICON_MIN, ICON_MAX, ICON_STEP); }
    static int margin(int value) { return snap(value, MARGIN_MIN, MARGIN_MAX, MARGIN_STEP); }
    static int hoverVibration(int level) { return Math.max(0, Math.min(10, level)); }

    private static int snap(int value, int min, int max, int step) {
        int limited = Math.max(min, Math.min(max, value));
        return min + Math.round((limited - min) / (float) step) * step;
    }

    private ArcConfig() {}
}
