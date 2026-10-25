package dev.mback.oneplus;

/** Pure recognition rules for Flyme-style bottom-corner gestures on portrait displays. */
final class CornerGesture {
    static final int NONE = 0;
    static final int LEFT = 1;
    static final int RIGHT = 2;

    static int triggerHeight(int bottomInset, float density) {
        return Math.max(bottomInset, Math.round(48f * density));
    }

    static int screenOrigin(float x, float y, int width, int height, int triggerHeight) {
        if (width <= 0 || height <= 0 || width >= height || triggerHeight <= 0) return NONE;
        if (y < height - Math.min(height, triggerHeight) || y >= height) return NONE;
        if (x >= 0 && x <= width * .19f) return LEFT;
        if (x >= width * .81f && x < width) return RIGHT;
        return NONE;
    }

    /**
     * Commit before Android's edge-back or Quickstep touch-slop creates any
     * visible preview. Unlike grabbing on DOWN, waiting for a tiny inward
     * diagonal avoids reentrant DOWN/CANCEL on ColorOS' input monitors.
     */
    static boolean earlyCapture(int side, float startX, float startY,
                                float x, float y, float density) {
        float dx = x - startX;
        float up = startY - y;
        if (up < 3f * density || Math.abs(dx) < 3f * density) return false;
        if (Math.abs(dx) < up * .23f) return false;
        return side == LEFT ? dx > 0 : side == RIGHT && dx < 0;
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
