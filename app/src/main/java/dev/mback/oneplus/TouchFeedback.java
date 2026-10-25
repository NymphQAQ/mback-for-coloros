package dev.mback.oneplus;

import android.media.AudioAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Explicit touch usage keeps short feedback out of media vibration scheduling. */
final class TouchFeedback {
    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();

    static boolean play(Vibrator vibrator, int level) {
        level = FeedbackConfig.vibration(level);
        if (level == 0 || vibrator == null || !vibrator.hasVibrator()) return false;
        vibrator.vibrate(VibrationEffect.createOneShot(FeedbackConfig.vibrationDuration(level),
                vibrator.hasAmplitudeControl() ? FeedbackConfig.vibrationAmplitude(level)
                        : VibrationEffect.DEFAULT_AMPLITUDE), ATTRIBUTES);
        return true;
    }

    private TouchFeedback() {}
}
