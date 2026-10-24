package dev.mback.oneplus;

/** Validated device-independent Flyme corner arc preferences. */
final class ArcConfig {
    static final String RADIUS_KEY = "mback_arc_radius_percent";
    static final String ICON_SIZE_KEY = "mback_arc_icon_size_dp";
    static final String SPACING_KEY = "mback_arc_spacing_dp";
    static final String ORIGINAL_ICON_KEY = "mback_arc_original_icons";

    static final int RADIUS_MIN = 50, RADIUS_MAX = 86, RADIUS_STEP = 2, DEFAULT_RADIUS = 72;
    static final int ICON_MIN = 28, ICON_MAX = 64, ICON_STEP = 2, DEFAULT_ICON = 44;
    static final int SPACING_MIN = 0, SPACING_MAX = 28, SPACING_STEP = 2, DEFAULT_SPACING = 12;
    static final boolean DEFAULT_ORIGINAL = false;

    static int radius(int value) { return snap(value, RADIUS_MIN, RADIUS_MAX, RADIUS_STEP); }
    static int icon(int value) { return snap(value, ICON_MIN, ICON_MAX, ICON_STEP); }
    static int spacing(int value) { return snap(value, SPACING_MIN, SPACING_MAX, SPACING_STEP); }

    private static int snap(int value, int min, int max, int step) {
        int limited = Math.max(min, Math.min(max, value));
        return min + Math.round((limited - min) / (float) step) * step;
    }

    private ArcConfig() {}
}
