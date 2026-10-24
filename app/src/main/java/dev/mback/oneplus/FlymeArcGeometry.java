package dev.mback.oneplus;

/**
 * True circular arc around the bottom-right (or bottom-left) screen corner.
 * R = radiusPercent * screenWidth, x = width - R*cos(theta),
 * y = height - R*sin(theta). LEFT is a horizontal reflection.
 *
 * Position zero is always the blue "More" button; 1..6 are applications.
 * A fixed initial 9-degree angle places More near the lower center.
 * Arc step is the desired center-to-center gap divided by circle radius,
 * capped at 83 degrees so all icons stay within the phone's bounds.
 */
final class FlymeArcGeometry {
    static final int MAX_APPS = 6;
    static final int MAX_POSITIONS = MAX_APPS + 1;
    static final int MORE_POSITION = 0;

    private static final double START = Math.toRadians(11d);
    private static final double END = Math.toRadians(85d);

    static float angleStep(int radiusPercent, int iconDp, int spacingDp,
                           float screenWidth, float density, int visiblePositions) {
        double radius = screenWidth * ArcConfig.radius(radiusPercent) / 100d;
        double iconHalf = density * ArcConfig.icon(iconDp) / 2d;
        // Leave room for the icon itself near the physical screen edge.
        // A small radius plus large icons otherwise clips the top-right icon.
        double upperBound = Math.min(END, Math.acos(Math.min(1d, iconHalf / radius)));
        double desired = density * (ArcConfig.icon(iconDp) + ArcConfig.spacing(spacingDp)) / radius;
        return (float) Math.min(desired, (upperBound - START) / Math.max(1, visiblePositions - 1));
    }

    static float angle(int position, int radiusPercent, int iconDp, int spacingDp,
                       float screenWidth, float density, int visiblePositions) {
        return (float) (START + position * angleStep(
                radiusPercent, iconDp, spacingDp, screenWidth, density, visiblePositions));
    }

    static float x(int side, int position, float width, float density,
                   int radiusPercent, int iconDp, int spacingDp, int count) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double angle = angle(position, radiusPercent, iconDp, spacingDp, width, density, count);
        float right = (float) (width - radius * Math.cos(angle));
        return side == CornerGesture.LEFT ? width - right : right;
    }

    static float y(int position, float width, float height, float density,
                   int radiusPercent, int iconDp, int spacingDp, int count) {
        double radius = width * ArcConfig.radius(radiusPercent) / 100d;
        double angle = angle(position, radiusPercent, iconDp, spacingDp, width, density, count);
        return (float) (height - radius * Math.sin(angle));
    }

    static int appCount(int value) {
        return Math.max(0, Math.min(MAX_APPS, value));
    }

    static int visiblePositions(int savedCount) {
        return appCount(savedCount) + 1;
    }

    static int hit(float x, float y, int side, float width, float height,
                   float density, int radiusPercent, int iconDp, int spacingDp,
                   int visiblePositions) {
        int chosen = -1;
        float hitRadius = density * (ArcConfig.icon(iconDp) * .5f + 8f);
        float best = hitRadius * hitRadius;
        for (int i = 0; i < visiblePositions; i++) {
            float dx = x - x(side, i, width, density, radiusPercent, iconDp, spacingDp,
                    visiblePositions);
            float dy = y - y(i, width, height, density, radiusPercent, iconDp, spacingDp,
                    visiblePositions);
            float distance = dx * dx + dy * dy;
            if (distance <= best) {
                best = distance;
                chosen = i;
            }
        }
        return chosen;
    }

    private FlymeArcGeometry() {}
}
