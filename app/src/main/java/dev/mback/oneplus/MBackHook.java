package dev.mback.oneplus;

import android.database.ContentObserver;
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
        if (!"com.android.systemui".equals(packageParam.packageName)) return;
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
        XposedBridge.log(TAG + " v0.3-full-hit-region registered: " + cls.getName()
                + " dispatch=" + dispatchOwner.getName());
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
                    if (!active) pressed = false;
                    root.requestLayout();
                    root.invalidate();
                }
            };
        }

        void attach() throws Exception {
            active = readEnabled();
            root.addView(cover, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(SETTING), false, settingObserver);
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

        private void updateInsets(Object info) {
            try {
                if (!active || !root.isShown()) {
                    hitRect.setEmpty();
                    return;
                }
                Rect hit = calculateHitRect();
                hitRect.set(hit);
                if (hit.isEmpty()) return;
                Field regionField = info.getClass().getField("touchableRegion");
                Region region = (Region) regionField.get(info);
                // Preserve ColorOS' existing touchable targets; only add the handle region.
                region.op(hit, Region.Op.UNION);
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
                // Use the SAME region the input dispatcher uses. Recalculating a
                // narrower handle rect here previously discarded valid downs.
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
