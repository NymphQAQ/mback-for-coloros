package dev.mback.oneplus;

public final class CornerRegionTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        CornerRegion bottom = new CornerRegion(1000, 2000, 2, new int[]{80, 48, 0, 0, 0, 0});
        check(bottom.origin(100, 1950) == CornerGesture.LEFT);
        check(bottom.origin(900, 1950) == CornerGesture.RIGHT);
        check(bottom.origin(100, 1903) == CornerGesture.NONE);
        check(bottom.origin(161, 1990) == CornerGesture.NONE);
        check(bottom.origin(500, 1990) == CornerGesture.NONE);
        check(bottom.origin(1000, 1990) == CornerGesture.NONE);
        check(bottom.origin(-1, 1990) == CornerGesture.NONE);
        check(bottom.origin(10, 2000) == CornerGesture.NONE);
        CornerRegion side = new CornerRegion(1000, 2000, 2, new int[]{0, 0, 120, 12, 0, 0});
        check(side.origin(10, 1800) == CornerGesture.LEFT);
        check(side.origin(990, 1800) == CornerGesture.RIGHT);
        check(side.origin(25, 1800) == CornerGesture.NONE);
        check(side.origin(10, 1759) == CornerGesture.NONE);
        CornerRegion triangle = new CornerRegion(1000, 2000, 2, new int[]{0, 0, 0, 0, 100, 100});
        check(triangle.origin(100, 1900) == CornerGesture.LEFT);
        check(triangle.origin(900, 1900) == CornerGesture.RIGHT);
        check(triangle.origin(101, 1900) == CornerGesture.NONE);
        check(triangle.origin(899, 1900) == CornerGesture.NONE);
        check(triangle.origin(50, 1850) == CornerGesture.LEFT);
        CornerRegion all = new CornerRegion(1000, 2000, 2, new int[]{80, 48, 120, 12, 100, 100});
        for (int x = 1; x < 500; x += 7) for (int up = 1; up < 300; up += 11) {
            int expected = bottom.origin(x, 2000 - up) | side.origin(x, 2000 - up) | triangle.origin(x, 2000 - up);
            check(all.origin(x, 2000 - up) == expected);
            check((all.origin(x, 2000 - up) == CornerGesture.LEFT)
                    == (all.origin(1000 - x, 2000 - up) == CornerGesture.RIGHT));
        }
        check(new CornerRegion(2000, 1000, 2, CornerRegion.DEFAULT).origin(10, 990) == 0);
        check(new CornerRegion(1000, 2000, 2, new int[6]).shieldWidth() == 0);
        check(new CornerRegion(1000, 2000, 2, new int[]{80, 0, 120, 0, 100, 0}).origin(10, 1990) == 0);
        check(CornerRegion.limit(0, -50) == 0);
        check(CornerRegion.limit(0, 10000) == 200);
        check(CornerRegion.limit(0, Integer.MAX_VALUE) == 200);
        check(CornerRegion.limit(0, Integer.MIN_VALUE) == 0);
        check(CornerRegion.limit(0, 81) == 82);
        check(new CornerRegion(300, 900, 3, new int[]{200, 120, 300, 64, 200, 200}).bottomLength == 135);
        System.out.println("CornerRegionTest passed (" + checks + " assertions)");
    }
}
