package dev.mback.oneplus;

import android.content.Context;
import android.provider.Settings;

final class CornerPreferences {
    static final String DIM_KEY = "mback_corner_dim_percent";
    static final String KEEP_OPEN_KEY = "mback_corner_keep_open";
    static final String ANIMATION_KEY = "mback_corner_animation_ms";
    static final int DEFAULT_DIM = 20;
    static final int DEFAULT_ANIMATION = 160, MAX_ANIMATION = 600, ANIMATION_STEP = 20;

    static int animationDuration(int value) { return Math.max(0, Math.min(MAX_ANIMATION, value)); }

    static int animationDuration(Context context) {
        return animationDuration(Settings.Global.getInt(context.getContentResolver(),
                ANIMATION_KEY, DEFAULT_ANIMATION));
    }

    static CornerRegion region(Context context, int width, int height) {
        float density = context.getResources().getDisplayMetrics().density;
        int[] values = new int[6];
        for (int i = 0; i < 6; i++) values[i] = Settings.Global.getInt(context.getContentResolver(),
                CornerRegion.KEYS[i], fallback(i));
        return new CornerRegion(width, height, density, values);
    }

    static int fallback(int index) { return CornerRegion.DEFAULT[index]; }

    static int dim(Context context) {
        return Math.max(0, Math.min(70, Settings.Global.getInt(context.getContentResolver(),
                DIM_KEY, DEFAULT_DIM)));
    }

    private CornerPreferences() {}
}
