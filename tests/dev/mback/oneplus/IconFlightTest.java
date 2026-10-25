package dev.mback.oneplus;

public final class IconFlightTest {
    private static int checks;
    private static void near(float actual, float expected) {
        checks++; if (Math.abs(actual - expected) > .005f) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        IconFlight path = new IconFlight();
        float[] point = new float[2];
        path.reset(100, 1000); path.add(80, 980); path.add(70, 950);
        path.point(0, 200, 400, point); near(point[0], 100); near(point[1], 1000);
        path.point(.175f, 200, 400, point); near(point[0], 80); near(point[1], 980);
        path.point(.35f, 200, 400, point); near(point[0], 70); near(point[1], 950);
        path.point(1, 200, 400, point); near(point[0], 200); near(point[1], 400);
        path.point(2, 200, 400, point); near(point[0], 200); near(point[1], 400);
        for (int i = 0; i < 300; i++) path.add(i, 900 - i);
        path.point(0, 200, 400, point); near(point[0], 100); near(point[1], 1000);
        path.point(.35f, 200, 400, point); near(point[0], 299); near(point[1], 601);
        path.point(1, 200, 400, point); near(point[0], 200); near(point[1], 400);
        path.reset(20, 30); path.point(0, 100, 200, point); near(point[0], 20); near(point[1], 30);
        path.point(1, 100, 200, point); near(point[0], 100); near(point[1], 200);
        System.out.println("IconFlightTest passed (" + checks + " assertions)");
    }
}
