package dev.mback.oneplus;

import android.app.KeyguardManager;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.View;
import android.view.ViewConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Hooks ColorOS' shared window policy, including windows opened outside MBack. */
final class NativeWindowHook {
    private static final String TAG = "[MBack-Windows]";
    private static final WeakHashMap<Object, Tap> taps = new WeakHashMap<>();
    private static final HashMap<Integer, Tap> blockedTouches = new HashMap<>();
    private static volatile boolean blockOutside;
    private static Object inputService, windowController;
    private static boolean loggedError;

    static void install(ClassLoader loader) {
        try {
            Class<?> policy = Class.forName("com.android.server.wm.OplusFlexibleTaskLayoutPolicy", false, loader);
            Class<?> controller = Class.forName("com.android.server.wm.FlexibleTaskController", false, loader);
            Class<?> memory = Class.forName("com.android.server.wm.FlexibleTaskLayoutController", false, loader);
            Class<?> floats = Class.forName("com.android.server.wm.FloatHandleController", false, loader);
            Class<?> touch = Class.forName("com.android.server.wm.FlexibleTaskController$TouchListener", false, loader);
            Class<?> input = Class.forName("com.android.server.input.InputManagerService", false, loader);
            require(input, "filterInputEvent", 2);
            require(input, "setInputFilter", 1);
            require(policy, "offsetBounds", 6);
            require(touch, "onPointerEvent", 1);
            require(controller, "handleTapOutsideTask", 3);
            require(controller, "unregisterPointerEventListener", 0);
            require(controller, "systemReady", 1);
            require(memory, "adjustFlexibleTaskInfo", 5);
            require(floats, "fillFloatPositionRect", 2);
            // Consume before dispatch: a passive pointer listener cannot cancel the app's click.
            XposedBridge.hookAllMethods(input, "filterInputEvent", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 2 || !(p.args[0] instanceof MotionEvent)) return;
                    try {
                        MotionEvent event = (MotionEvent) p.args[0];
                        if (blockTouch(event)) { event.recycle(); p.setResult(false); }
                    } catch (Throwable error) { blockedTouches.clear(); report(error); }
                }
            });
            // Keep the existing accessibility filter installed and respect its enable state.
            XposedBridge.hookAllMethods(input, "setInputFilter", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable() && inputService == p.thisObject) refreshInput();
                }
            });
            XposedBridge.hookAllMethods(policy, "offsetBounds", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 6) return;
                    try { place(p.thisObject, (Rect) p.args[0], (Point) p.args[2]); }
                    catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.hookAllMethods(memory, "adjustFlexibleTaskInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 5 || p.args[4] == null) return;
                    try { placeRestored(field(p.thisObject, "mController"), p.args); }
                    catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.hookAllMethods(floats, "fillFloatPositionRect", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.args.length != 2 || p.args[0] == null || !(p.args[1] instanceof Rect)) return;
                    try { placeFloat(p.thisObject, p.args[0], (Rect) p.args[1]); }
                    catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.hookAllMethods(touch, "onPointerEvent", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable() || p.args.length != 1 || !(p.args[0] instanceof MotionEvent)) return;
                    try { pointer(p.thisObject, (MotionEvent) p.args[0]); }
                    catch (Throwable error) {
                        synchronized (taps) { taps.remove(p.thisObject); }
                        report(error);
                    }
                }
            });
            // Our action waits for UP; suppress the game's separate delayed DOWN action.
            XposedBridge.hookAllMethods(controller, "handleTapOutsideTask", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try { if (mode(context(p.thisObject)) != WindowPlacement.OFF) p.setResult(null); }
                    catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.hookAllMethods(controller, "unregisterPointerEventListener", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (mode(context(p.thisObject)) != WindowPlacement.OFF
                                && yes(p.thisObject, "hasVisibleFlexibleTask")) p.setResult(null);
                    } catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.hookAllMethods(controller, "systemReady", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    try {
                        Context context = context(p.thisObject);
                        Handler handler = (Handler) field(p.thisObject, "mFlexibleTaskHandler");
                        windowController = p.thisObject;
                        inputService = field(field(field(p.thisObject, "mAtms"), "mWindowManager"), "mInputManager");
                        ContentObserver inputSettings = new ContentObserver(handler) {
                            @Override public void onChange(boolean selfChange) { refreshInput(); }
                        };
                        context.getContentResolver().registerContentObserver(
                                Settings.Global.getUriFor(WindowPlacement.OUTSIDE), false, inputSettings);
                        context.getContentResolver().registerContentObserver(
                                Settings.Global.getUriFor(WindowPlacement.BLOCK_OUTSIDE), false, inputSettings);
                        refreshInput();
                        // ActivityManager increments BOOT_COUNT after this early systemReady callback.
                        context.getContentResolver().registerContentObserver(
                                Settings.Global.getUriFor(Settings.Global.BOOT_COUNT), false,
                                new ContentObserver(handler) {
                                    @Override public void onChange(boolean selfChange) { markReady(context); }
                                });
                        handler.post(() -> markReady(context));
                        XposedBridge.log(TAG + " v0.8.4-window44 ready");
                    } catch (Throwable error) { report(error); }
                }
            });
            XposedBridge.log(TAG + " v0.8.4-window44 registered");
        } catch (Throwable error) { report(error); }
    }

    private static void place(Object policy, Rect bounds, Point visible) {
        Context context = context(policy);
        if (context == null || bounds == null || bounds.isEmpty() || visible == null
                || visible.x <= 0 || visible.y <= 0
                || Settings.Global.getInt(context.getContentResolver(), WindowPlacement.ENABLED, 0) != 1) return;
        Object display = field(policy, "mDisplayContent");
        if (display == null || ((Number) call(display, "getDisplayId")).intValue() != 0) return;
        Rect safe = new Rect((Rect) field(policy, "mWindowBounds"));
        int margin = ((Number) field(policy, "mSecurityMarginRight")).intValue();
        safe.inset(margin, 0);
        excludeBar(safe, (Rect) field(policy, "mStatusBarFrame"));
        excludeBar(safe, (Rect) field(policy, "mNaviBarFrame"));
        if (safe.width() < visible.x || safe.height() < visible.y) return;
        int x = Settings.Global.getInt(context.getContentResolver(), WindowPlacement.X, WindowPlacement.DEFAULT_X);
        int y = Settings.Global.getInt(context.getContentResolver(), WindowPlacement.Y, WindowPlacement.DEFAULT_Y);
        bounds.offsetTo(WindowPlacement.coordinate(safe.left, safe.right, visible.x, x),
                WindowPlacement.coordinate(safe.top, safe.bottom, visible.y, y));
    }

    private static void placeRestored(Object controller, Object[] args) {
        Object task = args[0];
        Object info = args[4], options = args[3];
        if (((Number) args[2]).intValue() == 32
                || ((Number) call(info, "getFlexibleState")).intValue() == 2
                || ((Number) call(info, "getResizeMode")).intValue() == 0) return;
        Rect original = (Rect) call(info, "getLaunchBounds");
        float scale = ((Number) call(info, "getScale")).floatValue();
        Rect bounds = positioned(controller, task, args[1], options, original, scale);
        if (bounds != null) applyBounds(controller, task, info, bounds);
    }

    private static void placeFloat(Object floats, Object handle, Rect target) {
        Object controller = field(floats, "mFlexibleTaskController");
        if (Settings.Global.getInt(context(controller).getContentResolver(), WindowPlacement.ENABLED, 0) != 1
                || ((Number) call(handle, "getFlexibleTaskState")).intValue() != 1) return;
        Object atms = field(floats, "mAtms");
        synchronized (field(atms, "mGlobalLock")) {
            Object task = call(field(atms, "mRootWindowContainer"), "anyTaskForId",
                    ((Number) call(handle, "getTaskId")).intValue(), 0);
            Object info = field(handle, "flexibleTaskInfo");
            if (task == null || info == null) return;
            float scale = ((Number) call(info, "getScale")).floatValue();
            Rect bounds = positioned(controller, task, null, null, target, scale);
            if (bounds == null) return;
            // The floating-entry animation and restored task must share the same destination.
            target.set(bounds);
            applyBounds(controller, task, info, bounds);
        }
    }

    private static Rect positioned(Object controller, Object task, Object record, Object options,
                                   Rect original, float scale) {
        Context context = context(controller);
        if (Settings.Global.getInt(context.getContentResolver(), WindowPlacement.ENABLED, 0) != 1
                || original == null || original.isEmpty() || !Float.isFinite(scale) || scale <= 0) return null;
        if (task != null && yes(task, "isVisible") && yes(controller, "isTaskInDefaultFlexible", task)) return null;
        int displayId = ((Number) call(controller, "getPreferredDisplayId", options, null, record, task)).intValue();
        if (displayId != 0) return null;
        Object display = call(field(field(controller, "mAtms"), "mRootWindowContainer"), "getDisplayContent", displayId);
        Object policy = field(controller, "mTaskLayoutPolicy");
        call(policy, "initScreenParams", display, context);
        Rect bounds = new Rect(original);
        place(policy, bounds, new Point(Math.round(bounds.width() * scale), Math.round(bounds.height() * scale)));
        return bounds;
    }

    private static void applyBounds(Object controller, Object task, Object info, Rect bounds) {
        call(info, "setLaunchBounds", bounds);
        // A restored task otherwise overwrites the launch policy with its remembered bounds.
        if (task != null && !yes(task, "isVisible") && yes(controller, "isTaskInFlexibleState", task, 4))
            call(task, "setBounds", bounds);
    }

    private static void excludeBar(Rect safe, Rect bar) {
        if (bar == null || bar.isEmpty() || !Rect.intersects(safe, bar)) return;
        if (bar.width() >= safe.width()) {
            if (bar.top <= safe.top) safe.top = Math.max(safe.top, bar.bottom);
            else if (bar.bottom >= safe.bottom) safe.bottom = Math.min(safe.bottom, bar.top);
        } else if (bar.height() >= safe.height()) {
            if (bar.left <= safe.left) safe.left = Math.max(safe.left, bar.right);
            else if (bar.right >= safe.right) safe.right = Math.min(safe.right, bar.left);
        }
    }

    private static void pointer(Object listener, MotionEvent event) {
        int action = event.getActionMasked();
        Tap tap;
        synchronized (taps) { tap = taps.get(listener); }
        if (action == MotionEvent.ACTION_DOWN) {
            synchronized (taps) { taps.remove(listener); }
            Object controller = field(listener, "this$0");
            Context context = context(controller);
            int mode = mode(context);
            if (mode == WindowPlacement.OFF || ((Number) call(event, "getDisplayId")).intValue() != 0) return;
            Object atms = field(controller, "mAtms");
            synchronized (field(atms, "mGlobalLock")) {
                if (!outside(controller, event.getRawX(), event.getRawY())) return;
                List<?> tasks = (List<?>) field(controller, "mFlexibleTasks");
                tap = new Tap(controller, new ArrayList<>(tasks), mode, event, context);
            }
            synchronized (taps) { taps.put(listener, tap); }
        } else if (tap != null) {
            if (action == MotionEvent.ACTION_MOVE) {
                if (!WindowPlacement.isTap(event.getRawX() - tap.x, event.getRawY() - tap.y,
                        event.getEventTime() - tap.time, tap.slop, ViewConfiguration.getLongPressTimeout())) {
                    synchronized (taps) { taps.remove(listener); }
                }
            } else {
                synchronized (taps) { taps.remove(listener); }
                if (action != MotionEvent.ACTION_UP || event.getPointerCount() != 1
                        || !WindowPlacement.isTap(event.getRawX() - tap.x, event.getRawY() - tap.y,
                        event.getEventTime() - tap.time, tap.slop, ViewConfiguration.getLongPressTimeout())) return;
                Tap completed = tap;
                // Leave input dispatch immediately; task mutations run on the system's task handler.
                ((Handler) field(field(tap.controller, "mAtms"), "mH")).post(() -> finishTap(completed));
            }
        }
    }

    private static void refreshInput() {
        try {
            Context context = context(windowController);
            blockOutside = mode(context) != WindowPlacement.OFF && Settings.Global.getInt(
                    context.getContentResolver(), WindowPlacement.BLOCK_OUTSIDE, 0) == 1;
            synchronized (field(inputService, "mInputFilterLock")) {
                call(field(inputService, "mNative"), "setInputFilterEnabled",
                        blockOutside || field(inputService, "mInputFilter") != null);
            }
        } catch (Throwable error) { blockOutside = false; report(error); }
    }

    private static boolean blockTouch(MotionEvent event) {
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                || ((Number) call(event, "getDisplayId")).intValue() != 0) return false;
        int device = event.getDeviceId(), action = event.getActionMasked();
        Tap tap = blockedTouches.get(device);
        if (action == MotionEvent.ACTION_DOWN) {
            blockedTouches.remove(device);
            if (!blockOutside) return false;
            Object controller = windowController;
            Object atms = field(controller, "mAtms");
            synchronized (field(atms, "mGlobalLock")) {
                if (!yes(controller, "hasVisibleFlexibleTask")
                        || !outside(controller, event.getRawX(), event.getRawY())) return false;
                ArrayList<Object> tasks = new ArrayList<>();
                for (Object task : (List<?>) field(controller, "mFlexibleTasks"))
                    if (yes(task, "isVisible") && !yes(task, "inTransition")
                            && yes(controller, "isTaskInDefaultFlexible", task)
                            && ((Number) call(task, "getDisplayId")).intValue() == 0) tasks.add(task);
                if (tasks.isEmpty()) return false;
                tap = new Tap(controller, tasks, mode(context(controller)), event, context(controller));
            }
            blockedTouches.put(device, tap);
            return true;
        }
        if (tap == null || event.getDownTime() != tap.downTime) return false;
        tap.eligible &= event.getPointerCount() == 1 && WindowPlacement.isTap(
                event.getRawX() - tap.x, event.getRawY() - tap.y, event.getEventTime() - tap.time,
                tap.slop, ViewConfiguration.getLongPressTimeout());
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            blockedTouches.remove(device);
            if (action == MotionEvent.ACTION_UP && tap.eligible) {
                Tap completed = tap;
                ((Handler) field(field(tap.controller, "mAtms"), "mH")).post(() -> finishTap(completed));
            }
        }
        // Keep the whole outside stream away from the app, including drags and long presses.
        return true;
    }

    private static boolean outside(Object controller, float x, float y) {
        Context context = context(controller);
        KeyguardManager lock = context.getSystemService(KeyguardManager.class);
        if (lock == null || lock.isDeviceLocked() || !yes(controller, "isCanRespondEvent")
                || yes(controller, "hasTaskInHandlingTouch") || yes(controller, "hasFlexibleTaskMenuShow")
                || field(controller, "mFlexibleScaleGaussBlur") != null) return false;
        Object atms = field(controller, "mAtms");
        Object display = call(field(atms, "mRootWindowContainer"), "getDefaultDisplay");
        if (yes(controller, "isIgnoreExpandRegion", display)
                || yes(controller, "hasFlexibleTaskInTouchableRegion", (int) x, (int) y)
                || insideHandle(controller, x, y)) return false;
        Region excluded = (Region) call(controller, "updateWindowTapExcludeRegion", display, null);
        if (excluded != null && excluded.contains((int) x, (int) y)) return false;
        Rect screen = (Rect) call(display, "getBounds");
        // Status and navigation gestures must never dismiss an application window.
        int edge = Math.round(32 * context.getResources().getDisplayMetrics().density);
        if (x < screen.left + edge || x >= screen.right - edge
                || y < screen.top + edge || y >= screen.bottom - edge) return false;
        return true;
    }

    private static boolean insideHandle(Object controller, float x, float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) return false;
        for (Object task : (List<?>) field(controller, "mFlexibleTasks")) {
            try {
                if (!yes(task, "isVisible") || !yes(controller, "isTaskInDefaultFlexible", task)
                        || ((Number) call(task, "getDisplayId")).intValue() != 0) continue;
                Object ext = call(call(task, "getWrapper"), "getExtImpl");
                Object caption = call(ext, "getFlexibleCaptionView");
                if (!(caption instanceof View) || !((View) caption).isAttachedToWindow()
                        || !((View) caption).isShown()) continue;
                float scale = ((Number) call(ext, "getScale")).floatValue();
                if (!Float.isFinite(scale) || scale <= 0) continue;
                Rect bounds = (Rect) call(controller, "getFlexibleTaskVisibleBounds", task);
                Rect ime = (Rect) call(ext, "getFlexibleTaskPosAfterIme");
                if (ime != null && !ime.isEmpty()) bounds = ime;
                if (bounds == null || bounds.isEmpty()) continue;
                int localX = (int) Math.floor((x - bounds.left) / scale);
                int localY = (int) Math.floor((y - bounds.top) / scale);
                // The native window-body test excludes the caption's extended bottom target.
                int action = ((Number) call(caption, "getFlexibleTaskActionFlag", localX, localY)).intValue();
                if (action == 8 || action == 10) return true;
            } catch (Throwable error) { report(error); }
        }
        return false;
    }

    private static void finishTap(Tap tap) {
        try {
            Object controller = tap.controller;
            Object atms = field(controller, "mAtms");
            synchronized (field(atms, "mGlobalLock")) {
                if (mode(context(controller)) != tap.mode || !outside(controller, tap.x, tap.y)) return;
                List<?> current = (List<?>) field(controller, "mFlexibleTasks");
                for (Object task : tap.tasks) {
                    if (!current.contains(task) || !yes(task, "isVisible") || yes(task, "inTransition")
                            || ((Number) call(task, "getDisplayId")).intValue() != 0
                            || !yes(controller, "isTaskInDefaultFlexible", task)) continue;
                    if (tap.mode == WindowPlacement.HIDE) {
                        // Native minimize retains the entire task, including light-open activities.
                        call(controller, "notifyFlexibleTaskEvent", ((Number) field(task, "mTaskId")).intValue(), 2002, null);
                    } else if (tap.mode == WindowPlacement.BACKGROUND) {
                        // ColorOS' close event finishes light-open pages; use the task's normal background path.
                        call(call(task, "getRootTask"), "moveTaskToBack", task);
                    } else if (tap.mode == WindowPlacement.FINISH) {
                        // Remove only this task; keep other tasks and the package process alive.
                        call(field(atms, "mTaskSupervisor"), "removeTaskById",
                                ((Number) field(task, "mTaskId")).intValue(), false, true,
                                "mback-outside-tap", Process.SYSTEM_UID, Process.myPid());
                    }
                }
            }
        } catch (Throwable error) { report(error); }
    }

    private static int mode(Context context) {
        return WindowPlacement.outside(Settings.Global.getInt(context.getContentResolver(), WindowPlacement.OUTSIDE, 0));
    }
    private static void markReady(Context context) {
        try {
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (boot >= 0) Settings.Global.putString(context.getContentResolver(), WindowPlacement.READY,
                    WindowPlacement.HOOK_VERSION + ":" + boot);
        } catch (Throwable error) { report(error); }
    }
    private static void require(Class<?> type, String name, int arguments) throws NoSuchMethodException {
        for (java.lang.reflect.Method method : type.getDeclaredMethods())
            if (method.getName().equals(name) && method.getParameterTypes().length == arguments) return;
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
    private static Context context(Object object) { return (Context) field(object, "mContext"); }
    private static Object field(Object object, String name) { return XposedHelpers.getObjectField(object, name); }
    private static Object call(Object object, String method, Object... args) { return XposedHelpers.callMethod(object, method, args); }
    private static boolean yes(Object object, String method, Object... args) { return Boolean.TRUE.equals(call(object, method, args)); }
    private static void report(Throwable error) {
        if (!loggedError) { loggedError = true; XposedBridge.log(TAG + " feature unavailable: " + error); }
    }

    private static final class Tap {
        final Object controller;
        final ArrayList<?> tasks;
        final int mode, slop;
        final float x, y;
        final long time, downTime;
        boolean eligible = true;
        Tap(Object controller, ArrayList<?> tasks, int mode, MotionEvent event, Context context) {
            this.controller = controller; this.tasks = tasks; this.mode = mode;
            x = event.getRawX(); y = event.getRawY(); time = event.getEventTime();
            downTime = event.getDownTime();
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }
    }
}
