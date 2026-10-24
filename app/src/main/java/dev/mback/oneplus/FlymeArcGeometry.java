package dev.mback.oneplus;

/**
 * One seven-stop rising diagonal based on the Flyme reference.
 * Position 0 (lowest, nearer the center) is ALWAYS the More button;
 * positions 1..6 hold up to six launchable applications.
 *
 * Width-relative coordinates work on the OnePlus 15 at any display density.
 * The left corner simply mirrors the entire row around the screen center.
 */
final class FlymeArcGeometry {
    static final int MAX_APPS = 6;
    static final int MAX_POSITIONS = MAX_APPS + 1;
    static final int MORE_POSITION = 0;
    private static final float[] X = {.31f, .415f, .52f, .625f, .73f, .835f, .94f};
    private static final float[] FROM_BOTTOM = {.115f, .22f, .325f, .43f, .535f, .64f, .745f};

    static float x(int side, int position, float screenWidth) {
        float right = X[position] * screenWidth;
        return side == CornerGesture.LEFT ? screenWidth - right : right;
    }

    static float y(int position, float width, float height) {
        return height - FROM_BOTTOM[position] * width;
    }

    static int appCount(int savedCount) {
        return Math.max(0, Math.min(MAX_APPS, savedCount));
    }

    static int visiblePositions(int savedCount) {
        return appCount(savedCount) + 1;
    }

    static int hit(float x, float y, int side, float width, float height,
                   int visiblePositions, float radius) {
        int chosen = -1;
        float bestDistance = radius * radius;
        for (int i = 0; i < visiblePositions; i++) {
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
