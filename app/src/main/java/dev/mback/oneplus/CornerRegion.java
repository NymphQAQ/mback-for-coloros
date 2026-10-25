package dev.mback.oneplus;

/** Shared physical hit area for the monitor, navigation guards and preview. */
final class CornerRegion {
    static final String[] KEYS = {"mback_corner_bottom_length_dp", "mback_corner_bottom_height_dp",
            "mback_corner_side_length_dp", "mback_corner_side_width_dp",
            "mback_corner_triangle_width_dp", "mback_corner_triangle_height_dp"};
    static final int[] MAX = {200, 120, 300, 64, 200, 200};
    static final int[] DEFAULT = {0, 0, 0, 0, 56, 66};
    final int width, height;
    final float bottomLength, bottomHeight, sideLength, sideWidth, triangleWidth, triangleHeight;

    CornerRegion(int width, int height, float density, int[] values) {
        this.width = width;
        this.height = height;
        float[] px = new float[6];
        for (int i = 0; i < 6; i++) px[i] = limit(i, values[i]) * density;
        bottomLength = Math.min(width * .45f, px[0]);
        bottomHeight = Math.min(height, px[1]);
        sideLength = Math.min(height, px[2]);
        sideWidth = Math.min(width * .45f, px[3]);
        triangleWidth = Math.min(width * .45f, px[4]);
        triangleHeight = Math.min(height, px[5]);
    }

    static int limit(int index, int value) {
        int limited = Math.max(0, Math.min(MAX[index], value));
        return Math.round(limited / 2f) * 2;
    }

    boolean contains(float inward, float up) {
        if (inward < 0 || up < 0) return false;
        return (bottomLength > 0 && bottomHeight > 0 && inward <= bottomLength && up <= bottomHeight)
                || (sideLength > 0 && sideWidth > 0 && inward <= sideWidth && up <= sideLength)
                || (triangleWidth > 0 && triangleHeight > 0
                    && inward / triangleWidth + up / triangleHeight <= 1f);
    }

    int origin(float x, float y) {
        if (width <= 0 || height <= width || x < 0 || x >= width || y < 0 || y >= height)
            return CornerGesture.NONE;
        if (contains(x, height - y)) return CornerGesture.LEFT;
        if (contains(width - x, height - y)) return CornerGesture.RIGHT;
        return CornerGesture.NONE;
    }

    int shieldWidth() {
        float bottom = bottomHeight > 0 ? bottomLength : 0;
        float side = sideLength > 0 ? sideWidth : 0;
        float triangle = triangleHeight > 0 ? triangleWidth : 0;
        return (int) Math.ceil(Math.max(bottom, Math.max(side, triangle)));
    }
}
