package dev.mback.oneplus;

import android.content.Context;
import android.provider.Settings;
import java.util.ArrayList;

/**
 * Shared SystemUI notification-shade gate. The panel state is observed from
 * NotificationShadeWindowControllerImpl, including ColorOS Control Center.
 * Launcher sees the same state via Settings.Global, updated only on transitions.
 */
final class ShadeVisibility {
    static final String KEY = "mback_notification_shade_open";
    private static volatile boolean expanded;
    private static final ArrayList<Runnable> listeners = new ArrayList<>();

    static boolean isExpanded() { return expanded; }

    static synchronized void addListener(Runnable listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.run();
    }

    static synchronized void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    static synchronized void update(Context context, boolean state) {
        if (expanded == state) return;
        expanded = state;
        de.robv.android.xposed.XposedBridge.log("[MBack-Shade] expanded=" + state);
        if (context != null) {
            try {
                Settings.Global.putInt(context.getContentResolver(), KEY, state ? 1 : 0);
            } catch (Throwable ignored) {}
        }
        for (Runnable listener : new ArrayList<>(listeners)) {
            try { listener.run(); } catch (Throwable ignored) {}
        }
    }

    private ShadeVisibility() {}
}
