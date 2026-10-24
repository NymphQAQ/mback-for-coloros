package dev.mback.oneplus;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.app.KeyguardManager;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.graphics.PixelFormat;

import de.robv.android.xposed.XposedBridge;

/**
 * Two tiny, invisible SystemUI-owned touch targets at the portrait bottom
 * corners. They prevent tapping or scrolling the underlying app during a
 * Flyme corner gesture without covering the central navigation bar.
 *
 * The SPY input monitor observes touches on these windows without pilfering,
 * while native Quickstep/edge-back receivers are filtered by the Xposed hooks.
 */
final class CornerTouchShield {
    private static final String TAG = "[MBack-Corner]";
    private final Context context;
    private final WindowManager wm;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DisplayManager.DisplayListener rotationListener =
            new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) update();
                }
            };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                clear();
                handler.removeCallbacks(awaitUnlock);
            } else {
                handler.post(() -> update());
                handler.removeCallbacks(awaitUnlock);
                handler.postDelayed(awaitUnlock, 1200);
            }
        }
    };
    private final Runnable awaitUnlock = new Runnable() {
        @Override public void run() {
            if (!started || ShadeVisibility.isExpanded() || (left != null && right != null)) return;
            android.os.PowerManager power = (android.os.PowerManager)
                    context.getSystemService(Context.POWER_SERVICE);
            if (power != null && !power.isInteractive()) return;
            update();
            if (started && (left == null || right == null)) {
                handler.postDelayed(this, 2000);
            }
        }
    };
    private final Runnable onShadeChange = () -> {
        if (ShadeVisibility.isExpanded()) clear();
        else update();
    };
    private View left, right;
    private boolean started;
    private int lastRotation = -1;

    CornerTouchShield(Context context) {
        this.context = context;
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        displays = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    }

    void start() {
        if (started) return;
        started = true;
        ShadeVisibility.addListener(onShadeChange);
        if (displays != null) displays.registerDisplayListener(rotationListener, handler);
        IntentFilter screenEvents = new IntentFilter();
        screenEvents.addAction(Intent.ACTION_SCREEN_OFF);
        screenEvents.addAction(Intent.ACTION_SCREEN_ON);
        screenEvents.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(screenReceiver, screenEvents, Context.RECEIVER_NOT_EXPORTED);
        update();
        handler.postDelayed(awaitUnlock, 2000);
    }

    void stop() {
        if (!started) return;
        started = false;
        ShadeVisibility.removeListener(onShadeChange);
        if (displays != null) displays.unregisterDisplayListener(rotationListener);
        try { context.unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        handler.removeCallbacks(awaitUnlock);
        clear();
    }

    private void update() {
        Display display = wm.getDefaultDisplay();
        if (display == null) return;
        int rotation = display.getRotation();
        if (ShadeVisibility.isExpanded()) {
            clear();
            return;
        }
        if (rotation == lastRotation && left != null && right != null) return;
        lastRotation = rotation;
        clear();
        if (!started || rotation != 0) {
            XposedBridge.log(TAG + " shield not installed: started=" + started + " rotation=" + rotation);
            return;
        }
        KeyguardManager keyguard = (KeyguardManager)
                context.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isDeviceLocked()) {
            XposedBridge.log(TAG + " shield deferred: device locked");
            return;
        }
        Point size = new Point();
        display.getRealSize(size);
        if (size.x >= size.y) return;
        int width = Math.round(size.x * .19f);
        int height = Math.round(Math.max(dp(45f), size.y * .065f));
        left = create("Left", Gravity.BOTTOM | Gravity.LEFT, width, height);
        right = create("Right", Gravity.BOTTOM | Gravity.RIGHT, width, height);
        XposedBridge.log(TAG + " touch shields active " + width + "x" + height);
    }

    private View create(String name, int gravity, int width, int height) {
        View v = new View(context);
        v.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        v.setOnTouchListener((ignored, event) -> true);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = gravity;
        // ColorOS otherwise clips overlay coordinates above the bottom
        // navigation inset, leaving a narrow stripe exposed.
        params.setFitInsetsTypes(0);
        params.setFitInsetsSides(0);
        params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("MBack Corner Shield " + name);
        try {
            wm.addView(v, params);
            return v;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " shield " + name + " not installed: " + t);
            return null;
        }
    }

    private void clear() {
        if (left != null) {
            try { wm.removeViewImmediate(left); } catch (Throwable ignored) {}
            left = null;
        }
        if (right != null) {
            try { wm.removeViewImmediate(right); } catch (Throwable ignored) {}
            right = null;
        }
    }

    private int dp(float dps) {
        return Math.round(dps * context.getResources().getDisplayMetrics().density);
    }
}
