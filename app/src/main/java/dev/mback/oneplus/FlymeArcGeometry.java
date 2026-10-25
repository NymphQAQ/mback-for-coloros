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

    private static double iconHalf(int iconDp, float density) {
        return density * (ArcConfig.icon(iconDp) * 1.13d / 2d + 9d);
    }

    private static double lowerAngle(double radius, double half, float width, float side) {
        double oppositeEdge = Math.acos(Math.max(-1d,
                Math.min(1d, (width - 2d * side - half) / radius)));
        return Math.max(START, Math.max(Math.asin(Math.min(1d, half / radius)), oppositeEdge));
    }

    static float angleStep(int radiusPercent, int iconDp,
                           float screenWidth, float density, int visiblePositions, int sideMarginDp) {
        double radius = screenWidth * ArcConfig.radius(radiusPercent) / 100d;
        double half = iconHalf(iconDp, density);
        double lower = lowerAngle(radius, half, screenWidth, density * ArcConfig.margin(sideMarginDp));
        double upper = Math.min(END, Math.acos(Math.min(1d, half / radius)));
        double sweep = Math.max(0d, upper - lower);
        int gaps = Math.max(1, visiblePositions - 1);
        double ergonomic = sweep / Math.max(4, gaps);
        double iconClearance = density * ArcConfig.icon(iconDp) * 1.13d / radius;
        // shortcut: oversized icons can exceed the arc; reduce icon size or increase radius.
        return (float) Math.min(sweep / gaps, Math.max(ergonomic, iconClearance));
    }

    static float angle(int position, int radiusPercent, int iconDp,
                       float screenWidth, float density, int visiblePositions, int sideMarginDp) {
        double radius = screenWidth * ArcConfig.radius(radiusPercent) / 100d;
        double half = iconHalf(iconDp, density);
        double lower = lowerAngle(radius, half, screenWidth, density * ArcConfig.margin(sideMarginDp));
        double upper = Math.min(END, Math.acos(Math.min(1d, half / radius)));
        return (float) ((lower + upper) * .5d + (position - (visiblePositions - 1) * .5d)
                * angleStep(radiusPercent, iconDp, screenWidth, density, visiblePositions, sideMarginDp));
    }

    static float x(int side, int position, float width, float density,
                   int radiusPercent, int iconDp, int count, int sideMarginDp) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double a = angle(position, radiusPercent, iconDp, width, density, count, sideMarginDp);
        float right = (float) (width - density * ArcConfig.margin(sideMarginDp) - radius * Math.cos(a));
        return side == CornerGesture.LEFT ? width - right : right;
    }

    static float y(int position, float width, float height, float density,
                   int radiusPercent, int iconDp, int count, int sideMarginDp, int bottomMarginDp) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double a = angle(position, radiusPercent, iconDp, width, density, count, sideMarginDp);
        return (float) (height - density * ArcConfig.margin(bottomMarginDp) - radius * Math.sin(a));
    }

    static int appCount(int saved) {
        return Math.max(0, Math.min(MAX_APPS, saved));
    }

    static int visiblePositions(int saved) { return appCount(saved) + 1; }

    static int hit(float x, float y, int side, float width, float height,
                   float density, int radiusPercent, int iconDp, int visiblePositions,
                   int sideMarginDp, int bottomMarginDp) {
        int closest = -1;
        float threshold = density * (ArcConfig.icon(iconDp) * .5f + 8f);
        float best = threshold * threshold;
        for (int i = 0; i < visiblePositions; i++) {
            float dx = x - x(side, i, width, density, radiusPercent, iconDp, visiblePositions, sideMarginDp);
            float dy = y - y(i, width, height, density, radiusPercent, iconDp, visiblePositions,
                    sideMarginDp, bottomMarginDp);
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
