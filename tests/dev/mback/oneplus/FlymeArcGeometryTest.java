package dev.mback.oneplus;

public final class FlymeArcGeometryTest {
    private static int asserts;

    public static void main(String[] args) {
        float w = 1272f, h = 2772f, density = 3.5f;
        int r = ArcConfig.DEFAULT_RADIUS, icon = ArcConfig.DEFAULT_ICON, gap = ArcConfig.DEFAULT_SPACING;
        int count = 7;
        check(FlymeArcGeometry.MAX_APPS == 6, "six apps");
        check(FlymeArcGeometry.visiblePositions(6) == 7, "seven positions");
        check(FlymeArcGeometry.visiblePositions(0) == 1, "More when empty");
        check(FlymeArcGeometry.MORE_POSITION == 0, "More stays lowest");

        for (int i = 0; i < count; i++) {
            float x = FlymeArcGeometry.x(CornerGesture.RIGHT, i, w, density, r, icon, gap, count);
            float y = FlymeArcGeometry.y(i, w, h, density, r, icon, gap, count);
            float mirror = FlymeArcGeometry.x(CornerGesture.LEFT, i, w, density, r, icon, gap, count);
            check(x > 0 && x < w && y > 0 && y < h, "onscreen");
            check(Math.abs(mirror + x - w) < .0001f, "mirror");
            double circle = Math.hypot(w - x, h - y);
            check(Math.abs(circle - w * r / 100d) < .001, "points lie on exact circle");
            check(FlymeArcGeometry.hit(x, y, CornerGesture.RIGHT, w, h, density, r, icon, gap, count) == i,
                    "right hit");
            check(FlymeArcGeometry.hit(mirror, y, CornerGesture.LEFT, w, h, density, r, icon, gap, count) == i,
                    "left hit");
            if (i > 0) {
                check(x > FlymeArcGeometry.x(CornerGesture.RIGHT, i - 1, w, density, r, icon, gap, count),
                        "x rises");
                check(y < FlymeArcGeometry.y(i - 1, w, h, density, r, icon, gap, count),
                        "y rises");
            }
        }
        // Non-collinear triplets prove it is actually curved, not another diagonal.
        double x0 = FlymeArcGeometry.x(2, 0, w, density, r, icon, gap, count);
        double x3 = FlymeArcGeometry.x(2, 3, w, density, r, icon, gap, count);
        double x6 = FlymeArcGeometry.x(2, 6, w, density, r, icon, gap, count);
        double y0 = FlymeArcGeometry.y(0, w, h, density, r, icon, gap, count);
        double y3 = FlymeArcGeometry.y(3, w, h, density, r, icon, gap, count);
        double y6 = FlymeArcGeometry.y(6, w, h, density, r, icon, gap, count);
        double cross = (x3 - x0) * (y6 - y0) - (x6 - x0) * (y3 - y0);
        check(Math.abs(cross) > 5000, "real curvature, not a line");

        // Smaller radius -> more curvature and different real coordinates.
        check(Math.abs(FlymeArcGeometry.x(2, 3, w, density, 50, icon, gap, count)
                - FlymeArcGeometry.x(2, 3, w, density, 86, icon, gap, count)) > 60,
                "radius slider moves actual icons");
        // Bigger spacing -> larger angular gap.
        check(FlymeArcGeometry.angleStep(r, icon, 28, w, density, count) >
                FlymeArcGeometry.angleStep(r, icon, 0, w, density, count), "spacing slider changes angular gap");
        // Bigger icon -> bigger hit circle and natural spacing.
        float x = FlymeArcGeometry.x(2, 0, w, density, r, icon, gap, count);
        float y = FlymeArcGeometry.y(0, w, h, density, r, icon, gap, count);
        check(FlymeArcGeometry.hit(x + 26*density, y, 2, w, h, density, r, 28, gap, 1) == -1,
                "small icon smaller hit region");
        check(FlymeArcGeometry.hit(x + 26*density, y, 2, w, h, density, r, 64, gap, 1) == 0,
                "large icon larger hit region");
        check(FlymeArcGeometry.hit(w*.5f, h*.5f, 2, w, h, density, r, icon, gap, count) == -1,
                "empty space does not select");
        check(FlymeArcGeometry.hit(FlymeArcGeometry.x(2,6,w,density,r,icon,gap,count),
                FlymeArcGeometry.y(6,w,h,density,r,icon,gap,count),2,w,h,density,
                r,icon,gap,2) == -1, "absent app is not selectable");

        for (int radius : new int[]{50,72,86}) {
            for (int size : new int[]{28,44,64}) {
                for (int spacing : new int[]{0,12,28}) {
                    for (int i = 0; i < 7; i++) {
                        float px = FlymeArcGeometry.x(2,i,w,density,radius,size,spacing,7);
                        float py = FlymeArcGeometry.y(i,w,h,density,radius,size,spacing,7);
                        float half = size*density/2f;
                        check(px - half >= 0 && px + half <= w &&
                                        py - half >= h - w*.97f && py + half <= h,
                                "all controls avoid clipping");
                    }
                }
            }
        }
        check(ArcConfig.radius(-1)==50 && ArcConfig.radius(999)==86, "radius clamp");
        check(ArcConfig.icon(-1)==28 && ArcConfig.icon(999)==64, "size clamp");
        check(ArcConfig.spacing(-1)==0 && ArcConfig.spacing(999)==28, "spacing clamp");
        System.out.println("FlymeArcGeometryTest passed ("+asserts+" assertions)");
    }

    private static void check(boolean yes, String what) {
        if(!yes) throw new AssertionError(what);
        asserts++;
    }
}
