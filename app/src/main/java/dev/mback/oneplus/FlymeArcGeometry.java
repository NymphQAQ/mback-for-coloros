package dev.mback.oneplus;

/**
 * Screen-relative layout measured from the user's Flyme reference sketch.
 * It forms a short ascending row by the right edge (mirrored on the left),
 * not a long radial fan filling half the display.
 *
 * Normalized sketch points (in order from the lowest shortcut):
 *   x = .59, .70, .81, .92 of screen width;
 *   distance from bottom = .12, .235, .345, .45 of screen width.
 * All four points are visually separated at any Android display density.
 */
final class FlymeArcGeometry {
    static final int VISIBLE_SHORTCUTS = 4;
    private static final float[] X = {.59f, .70f, .81f, .92f};
    private static final float[] FROM_BOTTOM = {.12f, .235f, .345f, .45f};

    static float x(int side, int index, float screenWidth) {
        float fraction = X[index];
        return (side == CornerGesture.LEFT ? 1f - fraction : fraction) * screenWidth;
    }

    static float y(int index, float width, float height) {
        return height - FROM_BOTTOM[index] * width;
    }

    static int visibleCount(int appCount, int page) {
        int remain = appCount - page * VISIBLE_SHORTCUTS;
        return Math.max(0, Math.min(VISIBLE_SHORTCUTS, remain));
    }

    static int hit(float x, float y, int side, float width, float height,
                   int visibleCount, float radius) {
        int chosen = -1;
        float bestDistance = radius * radius;
        for (int i = 0; i < visibleCount; i++) {
            float dx = x - x(side, i, width);
            float dy = y - y(i, width, height);
            float dist = dx * dx + dy * dy;
            if (dist <= bestDistance) {
                bestDistance = dist;
                chosen = i;
            }
        }
        return chosen;
    }

    private FlymeArcGeometry() {}
}
