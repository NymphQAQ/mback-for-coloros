package dev.mback.oneplus;

/** Percent of the remaining space, with the window's visible size kept intact. */
final class WindowPlacement {
    static final String ENABLED = "mback_window_position_enabled";
    static final String X = "mback_window_x_percent", Y = "mback_window_y_percent";
    static final String OUTSIDE = "mback_window_outside_action";
    static final String BLOCK_OUTSIDE = "mback_window_block_outside";
    static final String READY = "mback_window_hook_ready";
    static final int DEFAULT_X = 100, DEFAULT_Y = 33;
    static final int OFF = 0, HIDE = 1, FINISH = 2, BACKGROUND = 3;
    static final String HOOK_VERSION = "44";

    static int percent(int value) { return Math.max(0, Math.min(100, value)); }

    static int coordinate(int start, int end, int visibleSize, int percent) {
        return start + Math.round(Math.max(0, end - start - visibleSize) * percent(percent) / 100f);
    }

    static int outside(int value) {
        return value == HIDE || value == FINISH || value == BACKGROUND ? value : OFF;
    }

    static boolean isTap(float dx, float dy, long duration, int slop, int timeout) {
        return dx * dx + dy * dy <= (float) slop * slop && duration >= 0 && duration < timeout;
    }
}
