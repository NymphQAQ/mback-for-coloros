package dev.mback.oneplus;

/** The center third of the system navigation inset, in NavigationBarView coordinates. */
final class HitZone {
    static int[] bounds(int width, int height, int navigationHeight) {
        if (width <= 0 || height <= 0 || navigationHeight <= 0) return new int[]{0, 0, 0, 0};
        if (width >= height) {
            int side = width / 3;
            return new int[]{side, Math.max(0, height - navigationHeight), width - side, height};
        }
        int side = height / 3;
        return new int[]{0, side, width, height - side};
    }

    private HitZone() {}
}
