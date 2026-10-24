package dev.mback.oneplus;

/** Shared, Android-independent defaults and validated tuning ranges. */
public final class FeedbackConfig {
    public static final String SCALE_KEY = "mback_scale_percent";
    public static final String DURATION_KEY = "mback_scale_duration_ms";
    public static final String VIBRATION_KEY = "mback_vibration_level";

    public static final int SCALE_MIN = 50;
    public static final int SCALE_MAX = 150;
    public static final int SCALE_STEP = 5;
    public static final int DEFAULT_SCALE = 110;

    public static final int DURATION_MIN = 80;
    public static final int DURATION_MAX = 500;
    public static final int DURATION_STEP = 20;
    public static final int DEFAULT_DURATION = 240;

    public static final int VIBRATION_MIN = 0;
    public static final int VIBRATION_MAX = 10;
    public static final int DEFAULT_VIBRATION = 3;

    public static int scale(int raw) {
        return snap(raw, SCALE_MIN, SCALE_MAX, SCALE_STEP);
    }

    public static int duration(int raw) {
        return snap(raw, DURATION_MIN, DURATION_MAX, DURATION_STEP);
    }

    public static int vibration(int raw) {
        return clamp(raw, VIBRATION_MIN, VIBRATION_MAX);
    }

    public static int vibrationAmplitude(int level) {
        if (level <= 0) return 0;
        // 10 distinct amplitudes; never pass 0 to createOneShot.
        return clamp(32 + (vibration(level) - 1) * 24, 1, 255);
    }

    public static int vibrationDuration(int level) {
        return level <= 0 ? 0 : 10 + vibration(level);
    }

    private static int snap(int value, int min, int max, int step) {
        return min + Math.round((clamp(value, min, max) - min) / (float) step) * step;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private FeedbackConfig() {}
}
