package dev.mback.oneplus;

import android.graphics.Rect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses the native caption and drag controller; never creates a second touch surface. */
final class NativeHandleGestures {
    private static final String TAG = "[MBack-Handle] ";
    private static final String CANCEL_EVENT = "mback.handle.cancel";
    private static final Map<View, Stream> streams = new WeakHashMap<>();
    private static final Map<View, HandleGestureConfig.Taps> taps = new WeakHashMap<>();
    private static final Map<View, Object> requests = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> replay = new ThreadLocal<>();
    private static Method previewMethod;
    private static boolean installed, holdAvailable, loggedError;

    private NativeHandleGestures() {}

    static synchronized void install(ClassLoader loader) {
        if (installed) return;
        try {
            Class<?> caption = Class.forName("com.android.server.wm.FlexibleCaptionView", false, loader);
            Class<?> controller = Class.forName("com.android.server.wm.FlexibleTaskController", false, loader);
            Class<?> task = Class.forName("com.android.server.wm.Task", false, loader);
            // Each optional route is probed separately so one OS change does not disable the others.
            try {
                try {
                    previewMethod = method(controller, "switchStateFlexibleTask", ArrayList.class,
                            int.class, String.class, boolean.class, Runnable.class);
                } catch (NoSuchMethodException missing) {
                    previewMethod = method(controller, "switchState", task, int.class, String.class);
                }
            } catch (Throwable error) { report(error); }
            try {
                Class<?> regions = Class.forName("com.android.server.wm.FlexibleCaptionViewTouchRegionMgr", false, loader);
                XposedBridge.hookAllMethods(regions, "updateTouchRegionInFlexible", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        try {
                            if (p.hasThrowable() || p.args.length != 6 || !(p.args[4] instanceof View)) return;
                            View view = (View) p.args[4];
                            if (!normal(view) || !Boolean.TRUE.equals(call(view, "isBottomHandleShouldVisible"))) return;
                            Rect bar = (Rect) field(p.thisObject, "mBottomHandleButtonRegion");
                            if (bar == null || bar.isEmpty()) return;
                            int height = HandleGestureConfig.barHeight(bar.height(),
                                    view.getResources().getDisplayMetrics().density, ((Number) p.args[3]).floatValue());
                            int center = bar.centerY();
                            bar.top = center - height / 2;
                            // Keep the existing target and add one target-height below it.
                            bar.bottom = bar.top + height * 2;
                            ((Rect) field(p.thisObject, "mBottomHandleExpandRegion")).union(bar);
                        } catch (Throwable error) { report(error); }
                    }
                });
            } catch (Throwable error) { report(error); }
            try {
                method(caption, "calculateActionFlag", MotionEvent.class);
                method(caption, "handleEventProcess", MotionEvent.class, int.class, int.class);
                XposedBridge.hookAllMethods(caption, "calculateActionFlag", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Stream stream = streams.get(p.thisObject);
                        if (stream != null && stream.dragging && !p.hasThrowable()) {
                            try { setAction(p.thisObject); }
                            catch (Throwable error) { report(error); }
                        }
                    }
                });
                XposedBridge.hookAllMethods(caption, "handleEventProcess", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Stream stream = streams.get(p.thisObject);
                        if (stream != null && stream.dragging && p.args.length == 3) p.args[1] = 1;
                    }
                });
                holdAvailable = true;
            } catch (Throwable error) { report(error); }
            hookTapCallbacks(caption);
            try { hookTapCallbacks(Class.forName("com.android.server.wm.FlexibleTaskCaptionView", false, loader)); }
            catch (Throwable error) { report(error); }
            XposedBridge.hookAllMethods(caption, "dispatchTouchEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(replay.get()) || p.args.length != 1
                            || !(p.thisObject instanceof View) || !(p.args[0] instanceof MotionEvent)) return;
                    View view = (View) p.thisObject;
                    try { touch(p, view, (MotionEvent) p.args[0]); }
                    catch (Throwable error) {
                        Stream stream = streams.get(view);
                        if (stream != null) {
                            try { stream.releaseDown(); } catch (Throwable ignored) {}
                            stream.dispose();
                        }
                        report(error);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    int action = p.args[0] instanceof MotionEvent ? ((MotionEvent) p.args[0]).getActionMasked() : -1;
                    MotionEvent cancel = (MotionEvent) p.getObjectExtra(CANCEL_EVENT);
                    if (cancel != null) cancel.recycle();
                    if (Boolean.TRUE.equals(replay.get()) || !(p.thisObject instanceof View)) return;
                    Stream stream = streams.get(p.thisObject);
                    if (stream == null) return;
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || p.hasThrowable()) {
                        if (p.hasThrowable()) requests.remove(p.thisObject);
                        stream.dispose();
                    }
                }
            });
            for (String name : new String[]{"onDetachedFromWindow", "initCaptionViewAndAttachToTask"}) {
                XposedBridge.hookAllMethods(caption, name, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Stream stream = streams.get(p.thisObject);
                        if (stream != null) stream.dispose();
                        taps.remove(p.thisObject);
                        requests.remove(p.thisObject);
                    }
                });
            }
            installed = true;
            XposedBridge.log(TAG + "registered hold=" + holdAvailable);
        } catch (Throwable error) { report(error); }
    }

    private static void hookTapCallbacks(Class<?> caption) {
        for (String name : new String[]{"onDoubleTap", "onDoubleTapEvent", "onSingleTapUp", "onLongPress"}) {
            XposedBridge.hookAllMethods(caption, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Stream stream = streams.get(p.thisObject);
                    if (stream == null) return;
                    String method = p.method.getName();
                    boolean doubleCallback = method.equals("onDoubleTap") || method.equals("onDoubleTapEvent");
                    if (stream.dragging || (doubleCallback && stream.doubleAction != HandleGestureConfig.OFF)) {
                        p.setResult(method.equals("onLongPress") ? null : true);
                    }
                }
            });
        }
    }

    private static void touch(XC_MethodHook.MethodHookParam p, View view, MotionEvent event) {
        int action = event.getActionMasked();
        Stream stream = streams.get(view);
        if (action == MotionEvent.ACTION_DOWN) {
            if (stream != null) stream.dispose();
            requests.remove(view);
            int doubleAction = HandleGestureConfig.action(read(view, HandleGestureConfig.DOUBLE_TAP));
            boolean hold = holdAvailable && read(view, HandleGestureConfig.HOLD_MOVE) == 1;
            if ((doubleAction == HandleGestureConfig.OFF && !hold)
                    || event.getPointerCount() != 1 || !onBar(view, event) || !normal(view)) {
                taps.remove(view);
                return;
            }
            if (doubleAction == HandleGestureConfig.PREVIEW && previewMethod == null) doubleAction = HandleGestureConfig.OFF;
            stream = new Stream(view, event, doubleAction, hold);
            streams.put(view, stream);
            if (hold) { view.postDelayed(stream, HandleGestureConfig.HOLD_MS); p.setResult(true); }
            return;
        }
        if (stream == null) return;
        if (event.getDownTime() != stream.down.getDownTime() || event.getDeviceId() != stream.down.getDeviceId()) {
            stream.releaseDown(); stream.dispose(); return;
        }
        if (stream.suppressed) { p.setResult(true); return; }
        float dx = event.getRawX() - stream.down.getRawX(), dy = event.getRawY() - stream.down.getRawY();
        if (field(view, "mTask") != stream.task) {
            stream.dispose(); return;
        }
        if (stream.dragging) {
            if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_POINTER_DOWN) {
                replaceWithCancel(p, event); stream.suppressed = true;
            }
            return;
        }
        if (event.getPointerCount() != 1 || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_CANCEL) {
            taps.remove(view); stream.releaseDown(); stream.dispose(); return;
        }
        if (!HandleGestureConfig.within(dx, dy, stream.slop)) stream.moved = true;
        if (stream.buffered) {
            if (stream.moved || action == MotionEvent.ACTION_UP) stream.releaseDown();
            else { p.setResult(true); return; }
        }
        if (action != MotionEvent.ACTION_UP) return;
        stream.view.removeCallbacks(stream);
        boolean tap = !stream.moved && onBar(view, event)
                && event.getEventTime() - stream.down.getEventTime() < ViewConfiguration.getLongPressTimeout();
        if (tap && stream.second && stream.doubleAction != HandleGestureConfig.OFF) {
            taps.remove(view);
            replaceWithCancel(p, event);
            stream.requestAction(stream.doubleAction, 0);
        } else if (tap && stream.doubleAction != HandleGestureConfig.OFF) {
            stream.taps.remember(event.getEventTime(), event.getRawX(), event.getRawY(),
                    event.getDeviceId(), stream.task);
        } else { taps.remove(view); }
    }

    private static void replaceWithCancel(XC_MethodHook.MethodHookParam p, MotionEvent original) {
        MotionEvent cancel = MotionEvent.obtain(original);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        p.args[0] = cancel;
        p.setObjectExtra(CANCEL_EVENT, cancel);
    }

    private static boolean onBar(View caption, MotionEvent event) {
        try {
            Object regions = field(caption, "mFlexibleCaptionViewRegionMgr");
            Rect bar = (Rect) field(regions, "mBottomHandleButtonRegion");
            if (bar != null && !bar.isEmpty())
                return bar.contains((int) event.getX(), (int) event.getY());
        } catch (Throwable olderVersion) { /* Older ROMs use the visible handle as a fallback. */ }
        if (!(caption instanceof ViewGroup)) return false;
        for (String name : new String[]{"mBottomHandleView", "mBottomHandleLongView"}) {
            Object child = field(caption, name);
            if (!(child instanceof View) || !((View) child).isShown()) continue;
            Rect bounds = new Rect();
            ((View) child).getDrawingRect(bounds);
            ((ViewGroup) caption).offsetDescendantRectToMyCoords((View) child, bounds);
            if (bounds.isEmpty()) continue;
            int padding = Math.round(8 * caption.getResources().getDisplayMetrics().density);
            bounds.inset(-padding, -padding);
            if (bounds.contains((int) event.getX(), (int) event.getY())) return true;
        }
        return false;
    }

    private static boolean normal(View view) {
        Object task = field(view, "mTask"), controller = field(view, "mFlexibleTaskController");
        return task != null && Boolean.TRUE.equals(call(controller, "isTaskInFlexibleState", task, 1));
    }

    private static int read(View view, String key) {
        return Settings.Global.getInt(view.getContext().getContentResolver(), key, 0);
    }

    private static void setAction(Object caption) throws ReflectiveOperationException {
        Class<?> type = caption.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("mActionFlag");
                field.setAccessible(true); field.setInt(caption, 1); return;
            } catch (NoSuchFieldException missing) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException("mActionFlag");
    }

    private static Object field(Object object, String name) { return XposedHelpers.getObjectField(object, name); }
    private static Object call(Object object, String name, Object... args) { return XposedHelpers.callMethod(object, name, args); }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Method method = current.getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
            catch (NoSuchMethodException missing) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static void report(Throwable error) {
        if (!loggedError) { loggedError = true; XposedBridge.log(TAG + error); }
    }

    private static final class Stream implements Runnable {
        final View view;
        final Object task, controller;
        final MotionEvent down;
        final HandleGestureConfig.Taps taps;
        final float slop;
        final int doubleAction;
        final boolean second;
        final Object requestToken = new Object();
        boolean buffered, dragging, moved, suppressed, disposed;

        Stream(View view, MotionEvent event, int doubleAction, boolean hold) {
            this.view = view; this.task = field(view, "mTask");
            this.controller = field(view, "mFlexibleTaskController");
            this.down = MotionEvent.obtain(event); this.doubleAction = doubleAction;
            this.buffered = hold;
            ViewConfiguration config = ViewConfiguration.get(view.getContext());
            this.slop = config.getScaledTouchSlop();
            HandleGestureConfig.Taps previous = NativeHandleGestures.taps.get(view);
            if (previous == null) { previous = new HandleGestureConfig.Taps(); NativeHandleGestures.taps.put(view, previous); }
            this.taps = previous;
            this.second = previous.second(event.getEventTime(), event.getRawX(), event.getRawY(),
                    event.getDeviceId(), task, ViewConfiguration.getDoubleTapTimeout(), config.getScaledDoubleTapSlop());
        }

        @Override public void run() {
            if (disposed || !buffered || streams.get(view) != this) return;
            try {
                if (!view.isAttachedToWindow() || !view.isShown() || !normal(view)
                        || read(view, HandleGestureConfig.HOLD_MOVE) != 1) { releaseDown(); return; }
                Object pointer = field(view, "mFlexiblePointerHandler");
                boolean allowed;
                try { allowed = Boolean.TRUE.equals(call(pointer, "canDragHandle", view)); }
                catch (Throwable olderVersion) { allowed = Boolean.TRUE.equals(call(pointer, "canDragHandle")); }
                if (!allowed) { releaseDown(); return; }
                dragging = true; taps.clear(); releaseDown();
                if (((Number) field(view, "mHandlingEventPointerId")).intValue() == down.getPointerId(0)) {
                    try {
                        int level = Settings.Global.getInt(view.getContext().getContentResolver(),
                                HandleGestureConfig.HOLD_VIBRATION, HandleGestureConfig.DEFAULT_VIBRATION);
                        TouchFeedback.play(view.getContext().getSystemService(Vibrator.class), level);
                    } catch (Throwable error) { report(error); }
                }
            } catch (Throwable error) {
                dragging = false;
                try { releaseDown(); } catch (Throwable ignored) {}
                report(error);
            }
        }

        void releaseDown() {
            view.removeCallbacks(this);
            if (!buffered) return;
            buffered = false;
            replay.set(true);
            try { call(view, "dispatchTouchEvent", down); }
            finally { replay.remove(); }
        }

        void requestAction(int action, int attempt) {
            if (attempt == 0) requests.put(view, requestToken);
            view.postDelayed(() -> {
                boolean retry = false;
                try {
                    if (requests.get(view) != requestToken || !view.isAttachedToWindow()
                            || field(view, "mTask") != task || !normal(view)) return;
                    Object atms = call(controller, "getAtms");
                    synchronized (field(atms, "mGlobalLock")) {
                        if (Boolean.TRUE.equals(call(controller, "isInDragingOrScaling", task))) {
                            if (attempt < 8) { requestAction(action, attempt + 1); retry = true; }
                            return;
                        }
                        if (action == HandleGestureConfig.BACKGROUND) {
                            call(call(task, "getRootTask"), "moveTaskToBack", task);
                        } else if (previewMethod != null) {
                            if (previewMethod.getParameterTypes().length == 3)
                                previewMethod.invoke(controller, task, 2, "hideZoomWindow");
                            else {
                                ArrayList<Object> tasks = new ArrayList<>(); tasks.add(task);
                                previewMethod.invoke(controller, tasks, 2, "hideZoomWindow", true, null);
                            }
                        }
                    }
                } catch (Throwable error) { report(error); }
                finally {
                    if (!retry) {
                        if (requests.get(view) == requestToken) requests.remove(view);
                    }
                }
            }, attempt == 0 ? 0 : 50);
        }

        void dispose() {
            if (disposed) return;
            disposed = true; view.removeCallbacks(this);
            if (streams.get(view) == this) streams.remove(view);
            down.recycle();
        }
    }
}
