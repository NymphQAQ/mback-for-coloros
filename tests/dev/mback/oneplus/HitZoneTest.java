package dev.mback.oneplus;

public final class HitZoneTest {
    public static void main(String[] args) {
        int[] portrait = HitZone.bounds(1272, 131, 636);
        assertContains(portrait, 636, 130, "portrait bottom-most pixel");
        assertContains(portrait, 636, 0, "portrait top of navigation window");
        assertContains(portrait, 300, 110, "portrait wider thumb hit");
        assertOutside(portrait, 100, 130, "portrait distant app edge");

        int[] landscape = HitZone.bounds(131, 2772, 1386);
        assertContains(landscape, 130, 1386, "landscape short-axis edge");
        assertContains(landscape, 0, 1386, "landscape rotated edge");
        assertContains(landscape, 60, 850, "landscape wider thumb hit");
        assertOutside(landscape, 60, 200, "landscape edge away from handle");

        int[] shifted = HitZone.bounds(1272, 131, 1190);
        assertContains(shifted, 1271, 130, "handle near right edge clamps rectangle");
        assertOutside(shifted, 0, 120, "handle right is not mirrored");

        int[] empty = HitZone.bounds(0, 131, 0);
        check(empty[0] == empty[2] && empty[1] == empty[3], "zero width");
        System.out.println("HitZoneTest: all 11 assertions passed");
    }

    private static void assertContains(int[] rect, int x, int y, String description) {
        check(x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3], description);
    }

    private static void assertOutside(int[] rect, int x, int y, String description) {
        check(x < rect[0] || x >= rect[2] || y < rect[1] || y >= rect[3], description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
