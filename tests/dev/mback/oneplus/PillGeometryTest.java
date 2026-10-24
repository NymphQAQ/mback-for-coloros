package dev.mback.oneplus;

/** Validates that scaling around the drawn pill keeps its actual center fixed. */
public final class PillGeometryTest {
    public static void main(String[] args) {
        int assertions = 0;
        float pivot = PillGeometry.visualCenterY(131f, 22f, 9f, 20.7f, 0f);
        // The bar occupies [100,109] in a 131px tall View, not [61,70].
        check(pivot == 104.5f, "pivot follows actual pill center"); assertions++;
        check(pivot != 131f / 2f, "view center differs from visual pill center"); assertions++;
        for (float scale : new float[]{0.5f, 0.75f, 1f, 1.5f}) {
            float visibleTop = 100f, visibleBottom = 109f;
            float transformedTop = pivot + (visibleTop - pivot) * scale;
            float transformedBottom = pivot + (visibleBottom - pivot) * scale;
            check(Math.abs((transformedTop + transformedBottom) / 2f - pivot) < .0001f,
                    "visual center invariant for scale " + scale); assertions++;
        }
        float longPressPivot = PillGeometry.visualCenterY(131f, 22f, 9f, 20.7f, 1f);
        check(Math.abs(longPressPivot - 94.15f) < 0.001f,
                "long press draw height included"); assertions++;
        System.out.println("PillGeometryTest passed (" + assertions + " assertions)");
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
