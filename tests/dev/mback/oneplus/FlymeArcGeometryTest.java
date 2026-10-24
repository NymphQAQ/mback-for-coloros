package dev.mback.oneplus;

public final class FlymeArcGeometryTest {
    private static int assertions;
    public static void main(String[] args) {
        float w = 1272f, h = 2772f;
        // Exact right-side four-stop diagonal from the supplied sketch:
        // lowest target closer to center, highest near screen right.
        for (int i = 0; i < 4; i++) {
            float x = FlymeArcGeometry.x(CornerGesture.RIGHT, i, w);
            float y = FlymeArcGeometry.y(i, w, h);
            check(x > 0 && x < w && y > 0 && y < h, "within screen"); 
            check(Math.abs(FlymeArcGeometry.x(CornerGesture.LEFT, i, w) + x - w) < .0001f,
                    "left/right mirror");
            check(FlymeArcGeometry.hit(x, y, CornerGesture.RIGHT, w, h, 4, 75f) == i,
                    "hit right icon");
            check(FlymeArcGeometry.hit(w - x, y, CornerGesture.LEFT, w, h, 4, 75f) == i,
                    "hit left icon");
            if (i > 0) {
                check(x > FlymeArcGeometry.x(CornerGesture.RIGHT, i - 1, w),
                        "right-side upward/rightward ordering");
                check(y < FlymeArcGeometry.y(i - 1, w, h), "vertical ordering");
            }
        }
        check(FlymeArcGeometry.visibleCount(6, 0) == 4, "first page 4 icons");
        check(FlymeArcGeometry.visibleCount(6, 1) == 2, "second page 2 icons");
        check(FlymeArcGeometry.visibleCount(4, 1) == 0, "no empty extra page");
        check(FlymeArcGeometry.hit(w * .5f, h * .5f, CornerGesture.RIGHT,
                w, h, 4, 75f) == -1, "not selecting empty area");
        check(FlymeArcGeometry.hit(FlymeArcGeometry.x(CornerGesture.RIGHT, 3, w),
                FlymeArcGeometry.y(3, w, h), CornerGesture.RIGHT,
                w, h, 2, 75f) == -1, "cannot select hidden page icon");
        // Reference sketch proportions: lowest shortcut at ~.59w
        // and highest at ~.92w, limited to bottom .45 screen widths.
        check(Math.abs(FlymeArcGeometry.x(CornerGesture.RIGHT, 0, 100f) - 59f) < .001,
                "sketch lowest point");
        check(Math.abs(FlymeArcGeometry.x(CornerGesture.RIGHT, 3, 100f) - 92f) < .001,
                "sketch upper point");
        check(Math.abs(FlymeArcGeometry.y(3, 100f, 220f) - 175f) < .001,
                "compact near-bottom position");
        System.out.println("FlymeArcGeometryTest passed (" + assertions + " assertions)");
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
        assertions++;
    }
}
