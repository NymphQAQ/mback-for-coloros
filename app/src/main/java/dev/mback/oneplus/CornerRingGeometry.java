package dev.mback.oneplus;

/** Shared drawing and hit-test positions for concentric corner arcs. */
final class CornerRingGeometry {
    static final int MAX_POSITIONS = FlymeArcGeometry.MAX_POSITIONS + 16;
    final float[] x = new float[MAX_POSITIONS], y = new float[MAX_POSITIONS], icon = new float[MAX_POSITIONS];
    final int middleCount, outerStart, innerStart, count;

    CornerRingGeometry(int side, float width, float height, float density, int radiusPercent,
                    int middleIcon, int middleApps, int sideMargin, int bottomMargin,
                    int outerApps, int outerIcon, int innerApps, int innerIcon) {
        middleCount = FlymeArcGeometry.visiblePositions(middleApps);
        int outerCount = Math.max(0, Math.min(8, outerApps));
        int innerCount = Math.max(0, Math.min(8, innerApps));
        outerStart = middleCount;
        innerStart = middleCount + outerCount;
        count = innerStart + innerCount;
        if (innerCount == 0 && outerCount == 0) {
            for (int i = 0; i < middleCount; i++) {
                x[i] = FlymeArcGeometry.x(side, i, width, density, radiusPercent, middleIcon, middleCount, sideMargin);
                y[i] = FlymeArcGeometry.y(i, width, height, density, radiusPercent, middleIcon, middleCount, sideMargin, bottomMargin);
                icon[i] = ArcConfig.icon(middleIcon);
            }
            return;
        }
        float radius = width * ArcConfig.radius(radiusPercent) / 100f;
        int middleSize = ArcConfig.icon(middleIcon);
        while (middleSize > 12 && !fits(radius, middleCount, middleSize, width, density, sideMargin)) middleSize -= 2;
        int innerSize = ArcConfig.icon(innerIcon);
        float innerRadius;
        while (true) {
            innerRadius = Math.max(density, radius - density * ((middleSize + innerSize) * .565f + 12));
            if (innerSize <= 12 || fits(innerRadius, innerCount, innerSize, width, density, sideMargin)) break;
            innerSize -= 2;
        }
        int outerSize = ArcConfig.icon(outerIcon);
        float outerRadius;
        while (true) {
            outerRadius = radius + density * ((middleSize + outerSize) * .565f + 12);
            if (outerSize <= 12 || fits(outerRadius, outerCount, outerSize, width, density, sideMargin)) break;
            outerSize -= 2;
        }
        fill(0, middleCount, radius, middleSize, side, width, height, density, sideMargin, bottomMargin);
        fill(outerStart, outerCount, outerRadius, outerSize, side, width, height, density, sideMargin, bottomMargin);
        fill(innerStart, innerCount, innerRadius, innerSize, side, width, height, density, sideMargin, bottomMargin);
    }

    private static double lower(float radius, int size, float width, float density, int sideMargin) {
        double half = density * (size * .565 + 9);
        return Math.max(Math.toRadians(11), Math.max(Math.asin(Math.min(1, half / radius)),
                Math.acos(Math.max(-1, Math.min(1, (width - 2 * density * ArcConfig.margin(sideMargin) - half) / radius)))));
    }
    private static double upper(float radius, int size, float density) {
        return Math.min(Math.toRadians(85), Math.acos(Math.min(1, density * (size * .565 + 9) / radius)));
    }
    private static boolean fits(float radius, int count, int size, float width, float density, int margin) {
        double sweep = upper(radius, size, density) - lower(radius, size, width, density, margin);
        return sweep >= 0 && (count <= 1 || 2 * radius * Math.sin(sweep / (count - 1) * .5)
                >= density * (size * 1.13 + 4));
    }
    private void fill(int offset, int count, float radius, int size, int side, float width,
                      float height, float density, int sideMargin, int bottomMargin) {
        double low = lower(radius, size, width, density, sideMargin);
        double high = Math.max(low, upper(radius, size, density));
        double sweep = high - low;
        int gaps = Math.max(1, count - 1);
        double step = Math.min(sweep / gaps, Math.max(sweep / Math.max(4, gaps), density * size * 1.13 / radius));
        for (int i = 0; i < count; i++) {
            double angle = (low + high) * .5 + (i - (count - 1) * .5) * step;
            float rightX = (float) (width - density * ArcConfig.margin(sideMargin) - radius * Math.cos(angle));
            x[offset + i] = side == CornerGesture.LEFT ? width - rightX : rightX;
            y[offset + i] = (float) (height - density * ArcConfig.margin(bottomMargin) - radius * Math.sin(angle));
            icon[offset + i] = size;
        }
    }
}
