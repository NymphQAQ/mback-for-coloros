package dev.mback.oneplus;

public final class CornerGestureTest {
    public static void main(String[] args) {
        int count = 0;
        check(CornerGesture.triggerHeight(16, 1f) == 48); count++;
        check(CornerGesture.triggerHeight(48, 3f) == 144); count++;
        check(CornerGesture.triggerHeight(200, 3f) == 200); count++;
        int trigger = CornerGesture.triggerHeight(48, 3f);
        check(CornerGesture.screenOrigin(127, 2677, 1272, 2772, trigger) == CornerGesture.LEFT); count++;
        check(CornerGesture.screenOrigin(1145, 2677, 1272, 2772, trigger) == CornerGesture.RIGHT); count++;
        check(CornerGesture.screenOrigin(127, 2627, 1272, 2772, trigger) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(127, 2628, 1272, 2772, trigger) == CornerGesture.LEFT); count++;
        check(CornerGesture.screenOrigin(35, 2760, 1272, 2772, 48) == CornerGesture.LEFT); count++;
        check(CornerGesture.screenOrigin(1230, 2760, 1272, 2772, 48) == CornerGesture.RIGHT); count++;
        check(CornerGesture.screenOrigin(636, 2760, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(35, 2100, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(35, 2760, 2772, 1272, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(127, 2625, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(1145, 2704, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(35, 2723, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(35, 2724, 1272, 2772, 48) == CornerGesture.LEFT); count++;
        check(CornerGesture.screenOrigin(1230, 2771, 1272, 2772, 48) == CornerGesture.RIGHT); count++;
        check(CornerGesture.screenOrigin(35, 2772, 1272, 2772, 48) == CornerGesture.NONE); count++;
        check(CornerGesture.screenOrigin(35, 2760, 1272, 2772, 0) == CornerGesture.NONE); count++;
        check(CornerGesture.diagonal(CornerGesture.LEFT, 35, 2720, 100, 2640, 3.5f)); count++;
        check(CornerGesture.diagonal(CornerGesture.RIGHT, 1230, 2720, 1160, 2640, 3.5f)); count++;
        check(!CornerGesture.diagonal(CornerGesture.LEFT, 35, 2720, 35, 2300, 3.5f)); count++;
        check(!CornerGesture.diagonal(CornerGesture.RIGHT, 1230, 2720, 1230, 2300, 3.5f)); count++;
        check(!CornerGesture.diagonal(CornerGesture.LEFT, 35, 2720, 90, 2720, 3.5f)); count++;
        check(!CornerGesture.diagonal(CornerGesture.LEFT, 35, 2720, 10, 2620, 3.5f)); count++;
        check(CornerGesture.sanitize("com.example.app") != null); count++;
        check(CornerGesture.sanitize("com.app;rm -rf /") == null); count++;
        check(CornerGesture.earlyCapture(CornerGesture.LEFT, 35, 2720, 49, 2703, 3.0f)); count++;
        check(CornerGesture.earlyCapture(CornerGesture.RIGHT, 1230, 2720, 1216, 2703, 3.0f)); count++;
        check(!CornerGesture.earlyCapture(CornerGesture.LEFT, 35, 2720, 35, 2703, 3.0f)); count++;
        check(!CornerGesture.earlyCapture(CornerGesture.LEFT, 35, 2720, 49, 2720, 3.0f)); count++;
        check(!CornerGesture.earlyCapture(CornerGesture.LEFT, 35, 2720, 21, 2703, 3.0f)); count++;
        System.out.println("CornerGestureTest passed (" + count + " assertions)");
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
