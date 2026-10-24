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
 * SystemUI owns MONITOR_INPUT. A SPY input monitor sees corner gestures before
 * Launcher's separate 'swipe-up' receiver takes ownership.
 * Pilfer ONLY when the gesture moves diagonally toward the center.
 */
final class CornerMonitor {
    private static final String TAG = "[MBack-Corner]";
    private final Context context;
    private final CornerAppMenu menu;
    private InputMonitor monitor;
    private Receiver receiver;

    CornerMonitor(Context context, CornerAppMenu menu) {
        this.context = context;
        this.menu = menu;
    }

    void start() {
        if (monitor != null) return;
        try {
            InputManager inputManager = context.getSystemService(InputManager.class);
            if (inputManager == null) throw new IllegalStateException("missing InputManager");
            Object result = XposedHelpers.callMethod(inputManager,
                    "monitorGestureInput", "MBack corner gestures", 0);
            monitor = (InputMonitor) result;
            InputChannel channel = monitor.getInputChannel();
            receiver = new Receiver(channel, Looper.getMainLooper());
            XposedBridge.log(TAG + " input monitor started");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " input monitor unavailable: " + t);
            stop();
        }
    }

    void stop() {
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
        private boolean pilfered;

        Receiver(InputChannel channel, Looper looper) { super(channel, looper); }

        @Override public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) onTouch((MotionEvent) event);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " monitor handling failed: " + t);
            } finally {
                finishInputEvent(event, false);
            }
        }

        private void onTouch(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                side = CornerGesture.NONE;
                pilfered = false;
                Display display = ((WindowManager) context.getSystemService(
                        Context.WINDOW_SERVICE)).getDefaultDisplay();
                if (display.getRotation() != android.view.Surface.ROTATION_0) return;
                android.app.KeyguardManager keyguard = (android.app.KeyguardManager)
                        context.getSystemService(Context.KEYGUARD_SERVICE);
                if (keyguard != null && keyguard.isKeyguardLocked()) return;
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
                if (!pilfered) side = CornerGesture.NONE;
                return;
            }
            if (action == MotionEvent.ACTION_MOVE && !pilfered) {
                float dx = event.getRawX() - downX;
                float up = downY - event.getRawY();
                // Pilfer early enough to win over Launcher quickstep,
                // but only for directional diagonal swipes.
                if (CornerGesture.diagonal(side, downX, downY,
                        event.getRawX(), event.getRawY(),
                        context.getResources().getDisplayMetrics().density)) {
                    pilfered = true;
                    if (monitor != null) monitor.pilferPointers();
                    menu.show(side);
                    XposedBridge.log(TAG + " diagonal corner swipe side=" + side
                            + " dx=" + dx + " up=" + up);
                }
            }
            if (action == MotionEvent.ACTION_MOVE && pilfered) {
                menu.highlightBySwipe(event.getRawX(), event.getRawY());
            }
            if (action == MotionEvent.ACTION_UP) {
                if (pilfered) menu.chooseBySwipe(event.getRawX(), event.getRawY());
                side = CornerGesture.NONE;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                side = CornerGesture.NONE;
            }
        }

        private float dp(int count) {
            return count * context.getResources().getDisplayMetrics().density;
        }
    }
}
