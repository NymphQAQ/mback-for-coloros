package dev.mback.oneplus;

public final class HandleGestureConfigTest {
    private static int checks;

    public static void main(String[] args) {
        check(HandleGestureConfig.action(0) == HandleGestureConfig.OFF);
        check(HandleGestureConfig.action(1) == HandleGestureConfig.BACKGROUND);
        check(HandleGestureConfig.action(2) == HandleGestureConfig.PREVIEW);
        check(HandleGestureConfig.action(3) == HandleGestureConfig.OFF);
        check(HandleGestureConfig.action(-1) == HandleGestureConfig.OFF);
        check(HandleGestureConfig.holdReady(500, 3, 4, 5, 1));
        check(!HandleGestureConfig.holdReady(499, 0, 0, 5, 1));
        check(!HandleGestureConfig.holdReady(500, 6, 0, 5, 1));
        check(!HandleGestureConfig.holdReady(500, 0, 0, 5, 2));
        check(!HandleGestureConfig.holdReady(500, Float.NaN, 0, 5, 1));
        check(!HandleGestureConfig.within(0, 0, 0));
        check(!HandleGestureConfig.within(Float.POSITIVE_INFINITY, 0, 5));
        check(HandleGestureConfig.barHeight(20, 3, 1) == 120);
        check(HandleGestureConfig.barHeight(20, 3, .5f) == 240);
        check(HandleGestureConfig.barHeight(300, 3, .5f) == 300);
        check(HandleGestureConfig.barHeight(20, 3, 0) == 20);
        check(HandleGestureConfig.barHeight(20, Float.NaN, 1) == 20);
        check(HandleGestureConfig.barHeight(20, 3, Float.POSITIVE_INFINITY) == 20);
        for (int percent = 20; percent <= 100; percent++) {
            float scale = percent / 100f;
            check(Math.abs(HandleGestureConfig.barHeight(20, 2.975f, scale) * scale - 119) < 1);
        }
        HandleGestureConfig.Taps taps = new HandleGestureConfig.Taps();
        Object task = new Object();
        check(!taps.second(100, 10, 10, 7, task, 300, 20));
        taps.remember(100, 10, 10, 7, task);
        check(taps.second(250, 13, 14, 7, task, 300, 20));
        check(taps.second(400, 10, 10, 7, task, 300, 20));
        check(!taps.second(401, 10, 10, 7, task, 300, 20));
        check(!taps.second(99, 10, 10, 7, task, 300, 20));
        check(!taps.second(250, 31, 10, 7, task, 300, 20));
        check(!taps.second(250, 10, 10, 8, task, 300, 20));
        check(!taps.second(250, 10, 10, 7, new Object(), 300, 20));
        taps.clear();
        check(!taps.second(250, 10, 10, 7, task, 300, 20));
        System.out.println("HandleGestureConfigTest passed (" + checks + " assertions)");
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
}
