package dev.mback.oneplus;

import android.content.Context;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import android.view.WindowManager;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Observe the whole finger path without pilfering. Bottom-corner input
 * shields prevent app click-through. Xposed guards in Launcher/SystemUI stop
 * Quickstep/Back from consuming these reserved corner gestures or showing
 * their first-frame previews. Center navigation gestures are unaffected.
 */
final class CornerMonitor {
    private static final String TAG = "[MBack-Corner]";
    private final Context context;
    private final CornerAppMenu menu;
    private final CornerTouchShield shields;
    private InputMonitor monitor;
    private Receiver receiver;

    CornerMonitor(Context context, CornerAppMenu menu) {
        this.context = context;
        this.menu = menu;
        this.shields = new CornerTouchShield(context);
    }

    void start() {
        if (monitor != null) return;
        try {
            InputManager inputManager = context.getSystemService(InputManager.class);
            if (inputManager == null) throw new IllegalStateException("missing InputManager");
            monitor = (InputMonitor) XposedHelpers.callMethod(inputManager,
                    "monitorGestureInput", "MBack corner gestures", 0);
            InputChannel channel = monitor.getInputChannel();
            receiver = new Receiver(channel, Looper.getMainLooper());
            shields.start();
            XposedBridge.log(TAG + " corner monitor and transparent shields started");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " corner monitor unavailable: " + t);
            stop();
        }
    }

    void stop() {
        shields.stop();
        if (receiver != null) {
            receiver.dispose();
            receiver = null;
        }
        if (monitor != null) {
            monitor.dispose();
            monitor = null;
        }
    }

    private final class Receiver extends InputEventReceiver {
        private int side;
        private float downX, downY;

        Receiver(InputChannel channel, Looper looper) { super(channel, looper); }

        @Override public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) onTouch((MotionEvent) event);
            } catch (Throwable error) {
                XposedBridge.log(TAG + " corner touch error: " + error);
            } finally {
                finishInputEvent(event, false);
            }
        }

        private void onTouch(MotionEvent event) {
            int action = event.getActionMasked();
            if (menu.isGestureActive()) {
                if (action == MotionEvent.ACTION_MOVE) {
                    menu.highlightBySwipe(event.getRawX(), event.getRawY());
                } else if (action == MotionEvent.ACTION_UP) {
                    menu.finishGesture(event.getRawX(), event.getRawY());
                    side = CornerGesture.NONE;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    menu.hide();
                    side = CornerGesture.NONE;
                }
                return;
            }
            // Menu is never sticky: it lives exclusively during the finger gesture.
            if (menu.isOpened()) return;
            if (action == MotionEvent.ACTION_DOWN) {
                side = CornerGesture.NONE;
                Display display = ((WindowManager) context.getSystemService(
                        Context.WINDOW_SERVICE)).getDefaultDisplay();
                if (display == null || display.getRotation() != android.view.Surface.ROTATION_0)
                    return;
                android.app.KeyguardManager keyguard = (android.app.KeyguardManager)
                        context.getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard != null && keyguard.isDeviceLocked()) return;
                Point size = new Point();
                display.getRealSize(size);
                downX = event.getRawX();
                downY = event.getRawY();
                side = CornerGesture.screenOrigin(downX, downY, size.x, size.y,
                        context.getResources().getDisplayMetrics().density);
                return;
            }
            if (side == CornerGesture.NONE) return;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                side = CornerGesture.NONE;
            } else if (action == MotionEvent.ACTION_MOVE
                    && CornerGesture.earlyCapture(side, downX, downY,
                    event.getRawX(), event.getRawY(),
                    context.getResources().getDisplayMetrics().density)) {
                // Neither the app nor native navigation owns the start region.
                // Drawing here does not steal the ongoing input stream.
                menu.showForGesture(side);
                side = CornerGesture.NONE;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                side = CornerGesture.NONE;
            }
        }
    }
}
