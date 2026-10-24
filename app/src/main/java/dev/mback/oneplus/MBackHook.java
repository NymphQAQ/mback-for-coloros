package dev.mback.oneplus;

import android.database.ContentObserver;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.animation.TimeInterpolator;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hooks SystemUI's NavigationBarView, not applications or /dev/input.
 * The SystemUI navigation window has an empty touchable region on ColorOS gesture mode.
 * We cover the full thickness of the navigation window around the handle,
 * so lower-edge taps no longer fall through to the application underneath.
 */
public final class MBackHook implements IXposedHookLoadPackage {
    public static final String SETTING = "mback_xposed_enabled";
    private static final String TAG = "[MBack]";
    private static final WeakHashMap<ViewGroup, Interceptor> INSTANCES = new WeakHashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam packageParam) {
        if ("com.android.launcher".equals(packageParam.packageName)) {
            hookLauncherGesturePipeline(packageParam.classLoader);
            return;
        }
        if (!"com.android.systemui".equals(packageParam.packageName)) return;
        hookNotificationShade(packageParam.classLoader);
        hookSystemUiEdgeGesturePipeline(packageParam.classLoader);
        Class<?> cls = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.views.NavigationBarView", packageParam.classLoader);
        if (cls == null) {
            cls = XposedHelpers.findClassIfExists(
                    "com.android.systemui.navigationbar.NavigationBarView", packageParam.classLoader);
        }
        if (cls == null) {
            XposedBridge.log(TAG + " NavigationBarView class unavailable");
            return;
        }
        final Class<?> navClass = cls;
        boolean declaresDispatch = false;
        for (Method m : navClass.getDeclaredMethods()) {
            if ("dispatchTouchEvent".equals(m.getName())) declaresDispatch = true;
        }
        Class<?> dispatchOwner = declaresDispatch ? navClass : ViewGroup.class;
        XposedBridge.hookAllMethods(dispatchOwner, "dispatchTouchEvent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!navClass.isInstance(param.thisObject) || param.args.length == 0 ||
                        !(param.args[0] instanceof MotionEvent)) return;
                Interceptor interceptor = INSTANCES.get((ViewGroup) param.thisObject);
                if (interceptor != null && interceptor.dispatch((MotionEvent) param.args[0])) {
                    param.setResult(true);
                }
            }
        });
        XposedBridge.hookAllMethods(cls, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !(param.thisObject instanceof ViewGroup)) return;
                ViewGroup root = (ViewGroup) param.thisObject;
                root.post(() -> attach(root));
            }
        });
        XposedBridge.hookAllMethods(cls, "onDetachedFromWindow", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof ViewGroup) detach((ViewGroup) param.thisObject);
            }
        });
        XposedBridge.log(TAG + " v0.6.8-adaptive-arc-shade-guard registered: " + cls.getName()
                + " dispatch=" + dispatchOwner.getName());
    }

    /**
     * Launcher owns the Quickstep "swipe-up" monitor. Its input entry is
     * OplusBaseTouchInteractionService.K(InputEvent) on PLK110 / ColorOS 17.
     * Scoped filtering is much safer than racing Launcher's pilferPointers.
     */
    private static void hookLauncherGesturePipeline(ClassLoader loader) {
        Class<?> cls = XposedHelpers.findClassIfExists(
                "com.android.quickstep.OplusBaseTouchInteractionService", loader);
        if (cls == null) {
            XposedBridge.log(TAG + " Launcher Quickstep class not found");
            return;
        }
        XposedBridge.hookAllMethods(cls, "K", new XC_MethodHook() {
            private final WeakHashMap<Object, Boolean> cornerStreams = new WeakHashMap<>();
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length != 1
                        || !(param.args[0] instanceof MotionEvent)
                        || !(param.thisObject instanceof android.content.Context)) return;
                MotionEvent e = (MotionEvent) param.args[0];
                Object service = param.thisObject;
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    android.content.Context context = (android.content.Context) service;
                    boolean enabled = Settings.Global.getInt(context.getContentResolver(),
                            CornerAppMenu.ENABLE_KEY, 0) == 1;
                    boolean inside = false;
                    if (enabled && Settings.Global.getInt(
                            context.getContentResolver(), ShadeVisibility.KEY, 0) == 1) {
                        enabled = false;
                    }
                    if (enabled) {
                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                context.getSystemService(android.content.Context.KEYGUARD_SERVICE);
                        if (km != null && km.isDeviceLocked()) enabled = false;
                    }
                    if (enabled) {
                        try {
                            android.view.Display display = ((android.view.WindowManager)
                                    context.getSystemService(android.content.Context.WINDOW_SERVICE))
                                    .getDefaultDisplay();
                            if (display != null && display.getRotation() == 0) {
                                android.graphics.Point size = new android.graphics.Point();
                                display.getRealSize(size);
                                float density = context.getResources().getDisplayMetrics().density;
                                inside = CornerGesture.screenOrigin(
                                        e.getRawX(), e.getRawY(), size.x, size.y, density)
                                        != CornerGesture.NONE;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " launcher corner check: " + t);
                        }
                    }
                    if (inside) {
                        cornerStreams.put(service, Boolean.TRUE);
                        XposedBridge.log(TAG + " Launcher Quickstep BLOCK corner DOWN");
                        param.setResult(null);
                        return;
                    }
                    cornerStreams.remove(service);
                } else if (Boolean.TRUE.equals(cornerStreams.get(service))) {
                    param.setResult(null);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        cornerStreams.remove(service);
                }
            }
        });
        XposedBridge.log(TAG + " Launcher Quickstep corner-region filter active");
    }

    private static void hookNotificationShade(ClassLoader loader) {
        final Class<?> panel = XposedHelpers.findClassIfExists(
                "com.android.systemui.shade.NotificationPanelViewController", loader);
        if (panel == null) {
            XposedBridge.log(TAG + " notification shade controller not found");
            return;
        }
        // ColorOS 17 calls setExpandedHeightInternal(float, Boolean) throughout
        // shade transitions. Hook AFTER the fraction changes, then notify the
        // two corner shields and Launcher only if expanded/collapsed flips.
        XC_MethodHook observer = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                updateShadeFromPanel(param.thisObject);
            }
        };
        XposedBridge.hookAllConstructors(panel, observer);
        XposedBridge.hookAllMethods(panel, "setExpandedHeightInternal", observer);
        XposedBridge.log(TAG + " notification shade expand/collapse gate attached");
    }

    private static void updateShadeFromPanel(Object panelController) {
        try {
            float fraction = XposedHelpers.getFloatField(panelController,
                    "mExpandedFraction");
            View panelView = (View) XposedHelpers.getObjectField(panelController, "mView");
            ShadeVisibility.update(panelView == null ? null : panelView.getContext(),
                    fraction > .001f);
        } catch (Throwable ignored) {
            // SystemUI internals vary by ROM. Do not break shade animations.
        }
    }

    private static void hookSystemUiEdgeGesturePipeline(ClassLoader loader) {
        final Class<?> edge = XposedHelpers.findClassIfExists(
                "com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler", loader);
        if (edge == null) {
            XposedBridge.log(TAG + " SystemUI EdgeBackGestureHandler unavailable");
            return;
        }
        // PLK110's edge-swipe input receiver calls this lambda before
        // NavigationBarGestureUpEx or the back-panel animation sees a touch.
        final String entry = "$r8$lambda$kHZGHLhsJ29ijVg4dOahX71CzUw";
        XposedBridge.hookAllMethods(edge, entry, new XC_MethodHook() {
            private final WeakHashMap<Object, Boolean> blocked = new WeakHashMap<>();
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length != 2
                        || !(param.args[1] instanceof MotionEvent)) return;
                Object instance = param.args[0];
                MotionEvent e = (MotionEvent) param.args[1];
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    android.content.Context context = null;
                    try {
                        Field field = edge.getDeclaredField("mContext");
                        field.setAccessible(true);
                        context = (android.content.Context) field.get(instance);
                    } catch (Throwable ignored) {}
                    boolean isCorner = context != null
                            && Settings.Global.getInt(context.getContentResolver(),
                                    CornerAppMenu.ENABLE_KEY, 0) == 1;
                    if (isCorner && ShadeVisibility.isExpanded()) isCorner = false;
                    if (isCorner) {
                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                context.getSystemService(android.content.Context.KEYGUARD_SERVICE);
                        if (km != null && km.isDeviceLocked()) isCorner = false;
                    }
                    if (isCorner) {
                        android.view.WindowManager wm = (android.view.WindowManager)
                                context.getSystemService(android.content.Context.WINDOW_SERVICE);
                        android.view.Display display = wm.getDefaultDisplay();
                        android.graphics.Point size = new android.graphics.Point();
                        display.getRealSize(size);
                        isCorner = display.getRotation() == 0
                                && CornerGesture.screenOrigin(e.getRawX(), e.getRawY(),
                                size.x, size.y,
                                context.getResources().getDisplayMetrics().density)
                                != CornerGesture.NONE;
                    }
                    if (isCorner) {
                        blocked.put(instance, true);
                        XposedBridge.log(TAG + " SystemUI edge-back BLOCK corner DOWN");
                        param.setResult(null);
                    } else {
                        blocked.remove(instance);
                    }
                } else if (Boolean.TRUE.equals(blocked.get(instance))) {
                    param.setResult(null);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        blocked.remove(instance);
                }
            }
        });
        XposedBridge.log(TAG + " SystemUI edge-back corner filter registered");
    }

    private static synchronized void attach(ViewGroup root) {
        if (!root.isAttachedToWindow() || INSTANCES.containsKey(root)) return;
        try {
            Interceptor interceptor = new Interceptor(root);
            interceptor.attach();
            INSTANCES.put(root, interceptor);
            XposedBridge.log(TAG + " navigation interceptor attached");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " attach failed: " + t);
        }
    }

    private static synchronized void detach(ViewGroup root) {
        Interceptor interceptor = INSTANCES.remove(root);
        if (interceptor != null) interceptor.detach();
    }

    private static final class Interceptor implements View.OnTouchListener {
        private final ViewGroup root;
        private final View cover;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final ContentObserver settingObserver;
        private Object insetsListener;
        private boolean active;
        private boolean cornerEnabled;
        private CornerAppMenu cornerMenu;
        private CornerMonitor cornerMonitor;
        private int scalePercent = FeedbackConfig.DEFAULT_SCALE;
        private int scaleDuration = FeedbackConfig.DEFAULT_DURATION;
        private int vibrationLevel = FeedbackConfig.DEFAULT_VIBRATION;
        private View animatedHandle;
        private boolean handleLogged;
        private boolean pivotLogged;
        private final Rect hitRect = new Rect();
        private boolean pressed;
        private boolean moved;
        private float downX, downY;
        private long downAt, lastBackAt;

        Interceptor(ViewGroup root) {
            this.root = root;
            this.cover = new View(root.getContext());
            cover.setClickable(true);
            // Parent dispatch is hooked directly, as ColorOS may intercept events before child views.
            // A clickable cover keeps the window's region non-pass-through if hooks fail.
            cover.setOnTouchListener(null);
            settingObserver = new ContentObserver(handler) {
                @Override public void onChange(boolean selfChange) {
                    active = readEnabled();
                    cornerEnabled = readCornerEnabled();
                    if (cornerMenu != null) cornerMenu.prepare();
                    if (cornerMonitor != null) {
                        if (cornerEnabled) cornerMonitor.start();
                        else cornerMonitor.stop();
                    }
                    if (!cornerEnabled && cornerMenu != null) cornerMenu.hide();
                    loadFeedbackSettings();
                    if (!active) {
                        pressed = false;
                        restoreHandle();
                    }
                    root.requestLayout();
                    root.invalidate();
                }
            };
        }

        void attach() throws Exception {
            active = readEnabled();
            cornerEnabled = readCornerEnabled();
            cornerMenu = new CornerAppMenu(root.getContext());
            cornerMonitor = new CornerMonitor(root.getContext(), cornerMenu);
            if (cornerEnabled) cornerMonitor.start();
            loadFeedbackSettings();
            root.addView(cover, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING), false, settingObserver);
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(FeedbackConfig.SCALE_KEY), false, settingObserver);
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(FeedbackConfig.DURATION_KEY), false, settingObserver);
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(FeedbackConfig.VIBRATION_KEY), false, settingObserver);
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(CornerAppMenu.ENABLE_KEY), false, settingObserver);
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(CornerAppMenu.APPS_KEY), false, settingObserver);
            Class<?> listenerClass = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            insetsListener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                        if ("onComputeInternalInsets".equals(method.getName()) && args != null && args.length == 1) {
                            updateInsets(args[0]);
                        }
                        return null;
                    });
            Method add = ViewTreeObserver.class.getDeclaredMethod(
                    "addOnComputeInternalInsetsListener", listenerClass);
            add.setAccessible(true);
            add.invoke(root.getViewTreeObserver(), insetsListener);
            root.requestLayout();
        }

        void detach() {
            if (cornerMonitor != null) cornerMonitor.stop();
            if (cornerMenu != null) cornerMenu.hide();
            restoreHandle();
            root.getContext().getContentResolver().unregisterContentObserver(settingObserver);
            if (insetsListener != null) {
                try {
                    Class<?> listenerClass = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
                    Method remove = ViewTreeObserver.class.getDeclaredMethod(
                            "removeOnComputeInternalInsetsListener", listenerClass);
                    remove.setAccessible(true);
                    remove.invoke(root.getViewTreeObserver(), insetsListener);
                } catch (Exception e) { XposedBridge.log(TAG + " remove insets failed: " + e); }
            }
            root.removeView(cover);
        }

        private boolean readEnabled() {
            return Settings.Global.getInt(root.getContext().getContentResolver(), SETTING, 0) == 1;
        }

        private boolean readCornerEnabled() {
            return Settings.Global.getInt(root.getContext().getContentResolver(),
                    CornerAppMenu.ENABLE_KEY, 0) == 1;
        }

        private void loadFeedbackSettings() {
            scalePercent = FeedbackConfig.scale(Settings.Global.getInt(
                    root.getContext().getContentResolver(), FeedbackConfig.SCALE_KEY,
                    FeedbackConfig.DEFAULT_SCALE));
            scaleDuration = FeedbackConfig.duration(Settings.Global.getInt(
                    root.getContext().getContentResolver(), FeedbackConfig.DURATION_KEY,
                    FeedbackConfig.DEFAULT_DURATION));
            vibrationLevel = FeedbackConfig.vibration(Settings.Global.getInt(
                    root.getContext().getContentResolver(), FeedbackConfig.VIBRATION_KEY,
                    FeedbackConfig.DEFAULT_VIBRATION));
        }

        private void restoreHandle() {
            if (animatedHandle == null) return;
            animatedHandle.animate().cancel();
            animatedHandle.setScaleX(1f);
            animatedHandle.setScaleY(1f);
            animatedHandle = null;
        }

        private void playTapFeedback() {
            playVibration();
            View handle = findHandle(root);
            if (handle == null || !handle.isShown() ||
                    handle.getWidth() <= 0 || handle.getHeight() <= 0) {
                if (!handleLogged) {
                    XposedBridge.log(TAG + " no visible NavigationHandle found for animation");
                    handleLogged = true;
                }
                return;
            }
            if (!handleLogged) {
                XposedBridge.log(TAG + " animating handle " + handle.getClass().getName()
                        + " " + handle.getWidth() + "x" + handle.getHeight());
                handleLogged = true;
            }
            if (animatedHandle != null && animatedHandle != handle) restoreHandle();
            animatedHandle = handle;
            handle.animate().cancel();
            handle.setPivotX(handle.getWidth() * .5f);
            // OplusNavigationHandle draws its pill near the BOTTOM of a much
            // taller View. Its actual drawn bounds come from onDraw(), so a
            // view-height/2 pivot visibly pushes the bar downward when scaled.
            handle.setPivotY(readVisualPillPivotY(handle));
            handle.setScaleX(1f);
            handle.setScaleY(1f);
            final float targetScale = scalePercent / 100f;
            final int shrinkMs = Math.max(1, Math.round(scaleDuration * .45f));
            final int restoreMs = Math.max(1, scaleDuration - shrinkMs);
            // Don't stretch or recolor the rest of the SystemUI navigation window.
            handle.animate().scaleX(targetScale).scaleY(targetScale)
                    .setDuration(shrinkMs)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> handle.animate().scaleX(1f).scaleY(1f)
                            .setDuration(restoreMs)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .start())
                    .start();
        }

        private float readVisualPillPivotY(View handle) {
            try {
                Class<?> cls = handle.getClass();
                // Real ColorOS OplusNavigationHandle.onDraw geometry:
                // f = mHeight + mAdditionalHeightForAnimation * longPressAnimProgress;
                // bottom = getHeight() - mHandleBottom; top = bottom - f.
                float bottomInset = cls.getField("mHandleBottom").getInt(handle);
                float barHeight = cls.getField("mHeight").getInt(handle);
                float extraHeight = cls.getField("mAdditionalHeightForAnimation").getFloat(handle);
                float progress = cls.getField("longPressAnimProgress").getFloat(handle);
                float pivot = PillGeometry.visualCenterY(handle.getHeight(), bottomInset,
                        barHeight, extraHeight, progress);
                if (Float.isNaN(pivot) || Float.isInfinite(pivot)
                        || pivot < 0f || pivot > handle.getHeight()) {
                    throw new IllegalStateException("invalid pill pivot=" + pivot);
                }
                if (!pivotLogged) {
                    XposedBridge.log(TAG + " handle visual pivotY=" + pivot +
                            " vs view center=" + (handle.getHeight() * .5f) +
                            " pillHeight=" + barHeight + " bottomInset=" + bottomInset);
                    pivotLogged = true;
                }
                return pivot;
            } catch (Throwable error) {
                if (!pivotLogged) {
                    XposedBridge.log(TAG + " visual pivot fallback: " + error);
                    pivotLogged = true;
                }
                return handle.getHeight() * .5f;
            }
        }

        private void playVibration() {
            int level = vibrationLevel;
            if (level == 0) return;
            try {
                Vibrator vibrator = (Vibrator) root.getContext().getSystemService(
                        android.content.Context.VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    vibrator.vibrate(VibrationEffect.createOneShot(
                            FeedbackConfig.vibrationDuration(level),
                            vibrator.hasAmplitudeControl()
                                    ? FeedbackConfig.vibrationAmplitude(level)
                                    : VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } catch (Throwable error) {
                XposedBridge.log(TAG + " vibration unavailable: " + error);
            }
        }

        private void updateInsets(Object info) {
            try {
                if (!root.isShown() || !active) {
                    hitRect.setEmpty();
                    return;
                }
                Rect hit = calculateHitRect();
                hitRect.set(hit);
                Field regionField = info.getClass().getField("touchableRegion");
                Region region = (Region) regionField.get(info);
                if (!hit.isEmpty()) region.op(hit, Region.Op.UNION);
                // Corner gestures use a separate SPY input monitor, so extending
                // the navigation touchable region would just steal corner taps.
                Method setter = info.getClass().getMethod("setTouchableInsets", int.class);
                setter.invoke(info, 3); // TOUCHABLE_INSETS_REGION
            } catch (Exception e) {
                XposedBridge.log(TAG + " touch region failed: " + e);
            }
        }

        private Rect calculateHitRect() {
            int w = root.getWidth(), h = root.getHeight();
            if (w == 0 || h == 0) return new Rect();
            // The system navigation window itself is only ~44dp thick on PLK110.
            // The old handle-centered rectangle left a 19px unprotected strip below
            // the white bar, sending those touches directly to the app underneath.
            // Cover the FULL short axis of this real SystemUI window (never an
            // application overlay), while limiting the long axis to the handle area.
            boolean horizontal = w >= h;
            int center = (horizontal ? w : h) / 2;
            View handle = findHandle(root);
            if (handle != null && handle.isShown()
                    && handle.getWidth() > 0 && handle.getHeight() > 0) {
                int[] p = new int[2], base = new int[2];
                handle.getLocationInWindow(p);
                root.getLocationInWindow(base);
                int candidate = horizontal
                        ? p[0] - base[0] + handle.getWidth() / 2
                        : p[1] - base[1] + handle.getHeight() / 2;
                int length = horizontal ? w : h;
                if (candidate >= 0 && candidate < length) center = candidate;
            }
            int[] bounds = HitZone.bounds(w, h, center);
            return new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
        }

        private View findHandle(View view) {
            if (view != root && view.getClass().getName().contains("NavigationHandle")) {
                return view;
            }
            if (!(view instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child == cover) continue;
                View handle = findHandle(child);
                if (handle != null && handle.isShown()) return handle;
            }
            return null;
        }

        private int dp(int value) {
            return (int) (value * root.getResources().getDisplayMetrics().density + .5f);
        }

        boolean dispatch(MotionEvent event) {
            if (!active) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (!hitRect.contains((int) event.getX(), (int) event.getY())) return false;
            } else if (!pressed) {
                return false;
            }
            return onTouch(root, event);
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (!active) return false;
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    moved = false;
                    downX = event.getX();
                    downY = event.getY();
                    downAt = event.getEventTime();
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    moved = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(event.getX() - downX) > dp(22) ||
                            Math.abs(event.getY() - downY) > dp(22)) moved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressed && !moved && event.getEventTime() - downAt <= 380
                            && event.getEventTime() - lastBackAt > 280) {
                        lastBackAt = event.getEventTime();
                        playTapFeedback();
                        injectBack();
                    }
                    pressed = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    return true;
                default:
                    return true;
            }
        }

        private void injectBack() {
            // SystemUI already has android.permission.INJECT_EVENTS on this device.
            // No root daemon and no shell command on every tap.
            new Thread(() -> {
                try {
                    Class<?> managerClass = Class.forName("android.hardware.input.InputManagerGlobal");
                    Object manager = XposedHelpers.callStaticMethod(managerClass, "getInstance");
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_BACK, 0);
                    KeyEvent up = new KeyEvent(now, now + 10, KeyEvent.ACTION_UP,
                            KeyEvent.KEYCODE_BACK, 0);
                    down.setSource(android.view.InputDevice.SOURCE_KEYBOARD);
                    up.setSource(android.view.InputDevice.SOURCE_KEYBOARD);
                    Object deliveredDown = XposedHelpers.callMethod(manager, "injectInputEvent", down, 0);
                    Object deliveredUp = XposedHelpers.callMethod(manager, "injectInputEvent", up, 0);
                    if (Boolean.FALSE.equals(deliveredDown) || Boolean.FALSE.equals(deliveredUp)) {
                        XposedBridge.log(TAG + " inject BACK returned false");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " inject BACK failed: " + t);
                }
            }, "mback-back").start();
        }
    }
}
