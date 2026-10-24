package dev.mback.oneplus;

/**
 * Geometry of the tap-catching region in the actual SystemUI navigation window.
 * Coordinates are local to NavigationBarView, not raw touchscreen coordinates.
 */
final class HitZone {
    private static final float BOTTOM_WIDTH_FRACTION = 0.68f;
    private static final float SIDE_LENGTH_FRACTION = 0.52f;

    static int[] bounds(int width, int height, int center) {
        if (width <= 0 || height <= 0) return new int[]{0, 0, 0, 0};
        boolean horizontal = width >= height;
        int length = horizontal ? width : height;
        float fraction = horizontal ? BOTTOM_WIDTH_FRACTION : SIDE_LENGTH_FRACTION;
        int span = Math.min(length, Math.max(1, Math.round(length * fraction)));
        int start = Math.max(0, Math.min(length - span, center - span / 2));
        return horizontal
                ? new int[]{start, 0, start + span, height}
                : new int[]{0, start, width, start + span};
    }

    private HitZone() {}
}
