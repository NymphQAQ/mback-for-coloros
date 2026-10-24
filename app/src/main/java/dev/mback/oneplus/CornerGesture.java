package dev.mback.oneplus;

/** Pure recognition rules for Flyme-style bottom-corner gestures on portrait displays. */
final class CornerGesture {
    static final int NONE = 0;
    static final int LEFT = 1;
    static final int RIGHT = 2;

    static int screenOrigin(float x, float y, int width, int height, float density) {
        if (width <= 0 || height <= 0 || width >= height) return NONE;
        float stripHeight = Math.max(45f * density, height * .065f);
        if (y < height - stripHeight || y >= height) return NONE;
        if (x >= 0 && x <= width * .19f) return LEFT;
        if (x >= width * .81f && x < width) return RIGHT;
        return NONE;
    }

    static boolean diagonal(int side, float startX, float startY,
                            float x, float y, float density) {
        float dx = x - startX;
        float up = startY - y;
        if (up < 16f * density || Math.abs(dx) < 16f * density) return false;
        if (Math.abs(dx) < up * .33f) return false;
        return side == LEFT ? dx > 0 : side == RIGHT && dx < 0;
    }

    static String sanitize(String raw) {
        if (raw == null || !raw.matches("[a-zA-Z0-9_.]+")) return null;
        return raw;
    }

    private CornerGesture() {}
}
