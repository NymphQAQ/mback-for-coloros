package dev.mback.oneplus;

public final class FeedbackConfigTest {
    public static void main(String[] args) {
        check(FeedbackConfig.DEFAULT_SCALE == 110, "current phone scale is default");
        check(FeedbackConfig.DEFAULT_DURATION == 240, "current phone duration is default");
        check(FeedbackConfig.DEFAULT_VIBRATION == 3, "current phone vibration is default");
        check(FeedbackConfig.scale(-1) == 50, "scale min");
        check(FeedbackConfig.scale(999) == 150, "scale max");
        check(FeedbackConfig.scale(77) == 75, "scale snap");
        check(FeedbackConfig.duration(87) == 80, "duration snap");
        check(FeedbackConfig.duration(999) == 500, "duration max");
        check(FeedbackConfig.vibration(-3) == 0, "vibration min");
        check(FeedbackConfig.vibration(100) == 10, "vibration max");
        check(FeedbackConfig.vibrationAmplitude(0) == 0, "off amplitude");
        check(FeedbackConfig.vibrationDuration(0) == 0, "off duration");
        int previous = 0;
        for (int level = 1; level <= 10; level++) {
            int amplitude = FeedbackConfig.vibrationAmplitude(level);
            check(amplitude > previous && amplitude <= 255, "amplitude is strictly increasing: " + level);
            previous = amplitude;
        }
        System.out.println("FeedbackConfigTest passed (19 assertions)");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
