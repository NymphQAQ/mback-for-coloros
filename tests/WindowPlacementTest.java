package dev.mback.oneplus;

/** Run with -ea; geometry and tap classification require no phone or Android runtime. */
public final class WindowPlacementTest {
    public static void main(String[] args) {
        assert WindowPlacement.coordinate(54, 1218, 700, 0) == 54;
        assert WindowPlacement.coordinate(54, 1218, 700, 50) == 286;
        assert WindowPlacement.coordinate(54, 1218, 700, 100) == 518;
        assert WindowPlacement.coordinate(112, 2694, 1500, 33) == 469;
        assert WindowPlacement.coordinate(54, 1218, 700, -20) == 54;
        assert WindowPlacement.coordinate(54, 1218, 700, 200) == 518;
        assert WindowPlacement.coordinate(112, 112, 1500, 100) == 112;
        assert WindowPlacement.coordinate(0, 500, 900, 50) == 0;
        for (int width : new int[]{464, 700, 1164}) {
            for (int p = -10; p <= 110; p++) {
                int coordinate = WindowPlacement.coordinate(54, 1218, width, p);
                assert coordinate >= 54 && coordinate + width <= 1218;
            }
        }
        assert WindowPlacement.outside(0) == WindowPlacement.OFF;
        assert WindowPlacement.outside(1) == WindowPlacement.HIDE;
        assert WindowPlacement.outside(2) == WindowPlacement.FINISH;
        assert WindowPlacement.outside(3) == WindowPlacement.BACKGROUND;
        assert WindowPlacement.outside(4) == WindowPlacement.OFF;
        assert WindowPlacement.outside(-1) == WindowPlacement.OFF;
        assert WindowPlacement.isTap(3, 4, 120, 8, 500);
        assert WindowPlacement.isTap(8, 0, 0, 8, 500);
        assert !WindowPlacement.isTap(8, 1, 120, 8, 500);
        assert !WindowPlacement.isTap(0, 0, 500, 8, 500);
        assert !WindowPlacement.isTap(0, 0, -1, 8, 500);
        System.out.println("Window placement bounds, invalid settings, tap/slop/timeout checks passed");
    }
}
