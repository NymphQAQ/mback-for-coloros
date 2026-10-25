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
 * Observe app touches until an inward diagonal is confirmed, then pilfer once.
 * Fixed shields stay inside the navigation inset so app controls remain touchable.
 * Launcher/SystemUI guards prevent native navigation from racing the corner gesture.
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
        this.shields = new CornerTouchShield(context, menu::prepare, menu::hide);
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
        menu.hide();
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
        private long downTime = -1;
        private final IconFlight trail = new IconFlight();

        Receiver(InputChannel channel, Looper looper) { super(channel, looper); }

        @Override public void onInputEvent(InputEvent event) {
            try {
                if (event instanceof MotionEvent) onTouch((MotionEvent) event);
            } catch (Throwable error) {
                side = CornerGesture.NONE;
                downTime = -1;
                menu.hide();
                XposedBridge.log(TAG + " corner touch error: " + error);
            } finally {
                finishInputEvent(event, false);
            }
        }

        private void onTouch(MotionEvent event) {
            if (ShadeVisibility.isExpanded()) {
                side = CornerGesture.NONE;
                downTime = -1;
                if (menu.isOpened()) menu.hide();
                return;
            }
            int action = event.getActionMasked();
            // After release the overlay owns a fresh touch stream.
            if (menu.isLatched()) { side = CornerGesture.NONE; downTime = -1; return; }
            if (action == MotionEvent.ACTION_DOWN) {
                // A new stream must recover even if the previous UP/CANCEL was lost.
                downTime = event.getDownTime();
                side = CornerGesture.NONE;
                menu.hide();
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
                trail.reset(downX, downY);
                side = CornerPreferences.region(context, size.x, size.y).origin(downX, downY);
                if (side != CornerGesture.NONE) menu.refreshExtraApps();
                return;
            }
            // Removing the previous overlay can deliver its ending events late.
            if (downTime < 0 || event.getDownTime() != downTime) return;
            if (action == MotionEvent.ACTION_MOVE && (side != CornerGesture.NONE || menu.isGestureActive())) {
                for (int i = 0; i < event.getHistorySize(); i++)
                    trail.add(event.getHistoricalX(i), event.getHistoricalY(i));
                trail.add(event.getRawX(), event.getRawY());
            }
            if (menu.isGestureActive()) {
                if (action == MotionEvent.ACTION_MOVE) {
                    menu.highlightBySwipe(event.getRawX(), event.getRawY());
                } else if (action == MotionEvent.ACTION_UP) {
                    side = CornerGesture.NONE;
                    downTime = -1;
                    menu.finishGesture(event.getRawX(), event.getRawY(), event.getEventTime());
                } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                    side = CornerGesture.NONE;
                    downTime = -1;
                    menu.hide();
                }
                return;
            }
            if (side == CornerGesture.NONE) return;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                side = CornerGesture.NONE;
                downTime = -1;
            } else if (action == MotionEvent.ACTION_MOVE
                    && CornerGesture.earlyCapture(side, downX, downY,
                    event.getRawX(), event.getRawY(),
                    context.getResources().getDisplayMetrics().density)) {
                int capturedSide = side;
                side = CornerGesture.NONE;
                // Cancel the app's pending touch only after recognizing the diagonal.
                monitor.pilferPointers();
                menu.showForGesture(capturedSide, trail);
                menu.highlightBySwipe(event.getRawX(), event.getRawY());
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                side = CornerGesture.NONE;
                downTime = -1;
            }
        }
    }
}
