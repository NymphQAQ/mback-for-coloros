package dev.mback.oneplus;

/** Shared settings and gesture decisions; independent of Android for local checks. */
final class HandleGestureConfig {
    static final String DOUBLE_TAP = "mback_window_handle_double_tap";
    static final String HOLD_MOVE = "mback_window_handle_hold_move";
    static final String HOLD_VIBRATION = "mback_window_handle_vibration";
    static final int DEFAULT_VIBRATION = 3;
    static final int OFF = 0, BACKGROUND = 1, PREVIEW = 2;
    static final int HOLD_MS = 500;

    private HandleGestureConfig() {}

    static int action(int value) { return value == BACKGROUND || value == PREVIEW ? value : OFF; }

    static boolean within(float dx, float dy, float slop) {
        return Float.isFinite(dx) && Float.isFinite(dy) && Float.isFinite(slop) && slop > 0
                && dx * dx + dy * dy <= slop * slop;
    }

    static boolean holdReady(long elapsed, float dx, float dy, float slop, int pointers) {
        return elapsed >= HOLD_MS && pointers == 1 && within(dx, dy, slop);
    }

    static int barHeight(int current, float density, float scale) {
        if (!Float.isFinite(density) || !Float.isFinite(scale) || density <= 0 || scale <= 0)
            return current;
        // Caption coordinates shrink with the task; keep a 40dp physical touch target.
        return Math.max(current, Math.round(40 * density / scale));
    }

    static final class Taps {
        private long lastUp = -1;
        private float lastX, lastY;
        private int lastDevice;
        private Object lastTask;

        boolean second(long time, float x, float y, int device, Object task, int timeout, int slop) {
            return task != null && task == lastTask && device == lastDevice && lastUp >= 0
                    && time >= lastUp && time - lastUp <= timeout
                    && within(x - lastX, y - lastY, slop);
        }

        void remember(long time, float x, float y, int device, Object task) {
            lastUp = time; lastX = x; lastY = y; lastDevice = device; lastTask = task;
        }

        void clear() { lastUp = -1; lastTask = null; }
    }
}
