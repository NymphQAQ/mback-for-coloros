package dev.mback.oneplus;

/**
 * A real circular arc based at the bottom corner; mirrored on the left.
 * The gaps are calculated from available arc length, visible icon count,
 * chosen icon size and radius. No user-managed spacing setting is needed.
 */
final class FlymeArcGeometry {
    static final int MAX_APPS = 6;
    static final int MAX_POSITIONS = MAX_APPS + 1;
    static final int MORE_POSITION = 0;

    private static final double START = Math.toRadians(11d);
    private static final double END = Math.toRadians(85d);

    static float angleStep(int radiusPercent, int iconDp,
                           float screenWidth, float density, int visiblePositions) {
        double radius = screenWidth * ArcConfig.radius(radiusPercent) / 100d;
        double iconHalf = density * ArcConfig.icon(iconDp) / 2d;
        // Keep the entire top icon inside the right (or left) screen edge.
        double upper = Math.min(END, Math.acos(Math.min(1d, iconHalf / radius)));
        double sweep = Math.max(0d, upper - START);
        int gaps = Math.max(1, visiblePositions - 1);
        // Occupy a useful portion of the arc even with fewer shortcuts;
        // when there are seven positions the available arc is evenly divided.
        double ergonomic = sweep / Math.max(4, gaps);
        double iconClearance = density * ArcConfig.icon(iconDp) * 1.13d / radius;
        return (float) Math.min(sweep / gaps, Math.max(ergonomic, iconClearance));
    }

    static float angle(int position, int radiusPercent, int iconDp,
                       float screenWidth, float density, int visiblePositions) {
        return (float) (START + position * angleStep(
                radiusPercent, iconDp, screenWidth, density, visiblePositions));
    }

    static float x(int side, int position, float width, float density,
                   int radiusPercent, int iconDp, int count) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double a = angle(position, radiusPercent, iconDp, width, density, count);
        float right = (float) (width - radius * Math.cos(a));
        return side == CornerGesture.LEFT ? width - right : right;
    }

    static float y(int position, float width, float height, float density,
                   int radiusPercent, int iconDp, int count) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double a = angle(position, radiusPercent, iconDp, width, density, count);
        return (float) (height - radius * Math.sin(a));
    }

    static int appCount(int saved) {
        return Math.max(0, Math.min(MAX_APPS, saved));
    }

    static int visiblePositions(int saved) { return appCount(saved) + 1; }

    static int hit(float x, float y, int side, float width, float height,
                   float density, int radiusPercent, int iconDp, int visiblePositions) {
        int closest = -1;
        float threshold = density * (ArcConfig.icon(iconDp) * .5f + 8f);
        float best = threshold * threshold;
        for (int i = 0; i < visiblePositions; i++) {
            float dx = x - x(side, i, width, density, radiusPercent, iconDp, visiblePositions);
            float dy = y - y(i, width, height, density, radiusPercent, iconDp, visiblePositions);
            float distance = dx * dx + dy * dy;
            if (distance <= best) {
                best = distance;
                closest = i;
            }
        }
        return closest;
    }

    private FlymeArcGeometry() {}
}
