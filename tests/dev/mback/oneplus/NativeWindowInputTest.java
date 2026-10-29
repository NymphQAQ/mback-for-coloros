package dev.mback.oneplus;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;

/** Calls the production outside predicate and filter, not a copy of their decisions. */
public final class NativeWindowInputTest {
    static class Extension {
        View caption = new View();
        float scale = .5f;
        Rect ime = new Rect();
        Object getFlexibleCaptionView() { return caption; }
        float getScale() { return scale; }
        Rect getFlexibleTaskPosAfterIme() { return ime; }
    }
    static class Wrapper { Extension ext = new Extension(); Object getExtImpl() { return ext; } }
    static class Task {
        Wrapper wrapper = new Wrapper(); boolean visible = true;
        Object getWrapper() { return wrapper; }
        boolean isVisible() { return visible; }
        boolean inTransition() { return false; }
        int getDisplayId() { return 0; }
    }
    static class Display { Rect getBounds() { return new Rect(0, 0, 1200, 2600); } }
    static class Root { Object getDefaultDisplay() { return new Display(); } }
    static class Atms {
        Object mGlobalLock = new Object(); Root mRootWindowContainer = new Root(); Handler mH = new Handler();
    }
    static class Controller {
        Context mContext = new Context(); Atms mAtms = new Atms(); Object mFlexibleScaleGaussBlur;
        ArrayList<Task> mFlexibleTasks = new ArrayList<>();
        boolean isCanRespondEvent() { return true; }
        boolean hasTaskInHandlingTouch() { return false; }
        boolean hasFlexibleTaskMenuShow() { return false; }
        boolean isIgnoreExpandRegion(Object display) { return false; }
        boolean hasVisibleFlexibleTask() { return true; }
        boolean isTaskInDefaultFlexible(Object task) { return true; }
        boolean isTaskInFlexibleState(Object task, int state) { return state == 1; }
        Rect getFlexibleTaskVisibleBounds(Object task) { return new Rect(100, 200, 700, 1400); }
        boolean hasFlexibleTaskInTouchableRegion(int x, int y) {
            return getFlexibleTaskVisibleBounds(null).contains(x, y);
        }
        Region updateWindowTapExcludeRegion(Object display, Object previous) { return new Region(); }
    }

    static void set(String name, Object value) throws Exception {
        Field field = NativeWindowHook.class.getDeclaredField(name);
        field.setAccessible(true); field.set(null, value);
    }

    public static void main(String[] args) throws Exception {
        Controller controller = new Controller(); Task task = new Task(); controller.mFlexibleTasks.add(task);
        Extension ext = task.wrapper.ext;
        Method outside = NativeWindowHook.class.getDeclaredMethod("outside", Object.class, float.class, float.class);
        Method filter = NativeWindowHook.class.getDeclaredMethod("blockTouch", MotionEvent.class);
        outside.setAccessible(true); filter.setAccessible(true);
        assert !(Boolean) outside.invoke(null, controller, 400f, 1200f) : "App body must remain inside";
        assert !(Boolean) outside.invoke(null, controller, 400f, 1500f) : "Expanded bar was treated as outside";
        ext.caption.action = 8;
        assert !(Boolean) outside.invoke(null, controller, 400f, 1500f) : "Hidden-frame bottom handle must remain inside";
        ext.caption.action = 11;
        assert (Boolean) outside.invoke(null, controller, 400f, 1500f) : "Inactive expansion area must stay outside";
        ext.caption.action = 10;
        assert (Boolean) outside.invoke(null, controller, 750f, 1500f) : "Real outside area must remain blocked";
        assert (Boolean) outside.invoke(null, controller, 400f, 1600f) : "Bottom edge is exclusive";
        ext.caption.attached = false;
        assert (Boolean) outside.invoke(null, controller, 400f, 1500f) : "Detached bar must not exempt touches";
        ext.caption.attached = true; task.visible = false;
        assert (Boolean) outside.invoke(null, controller, 400f, 1500f) : "Hidden task must not exempt touches";
        task.visible = true; ext.scale = 0;
        assert (Boolean) outside.invoke(null, controller, 400f, 1500f) : "Invalid scale must not exempt touches";
        ext.scale = Float.NaN;
        assert (Boolean) outside.invoke(null, controller, 400f, 1500f) : "Nonfinite scale must not exempt touches";
        ext.scale = .5f; ext.ime = new Rect(150, 300, 750, 1500);
        assert !(Boolean) outside.invoke(null, controller, 400f, 1650f) : "Bar must follow IME position";
        ext.ime = new Rect();
        set("windowController", controller); set("blockOutside", true);
        assert !(Boolean) filter.invoke(null, new MotionEvent(0, 400, 1500, 100)) : "Bar DOWN was swallowed";
        assert !(Boolean) filter.invoke(null, new MotionEvent(2, 900, 1800, 800)) : "Bar drag was interrupted";
        assert !(Boolean) filter.invoke(null, new MotionEvent(1, 900, 1800, 900));
        assert (Boolean) filter.invoke(null, new MotionEvent(0, 750, 1700, 100));
        assert (Boolean) filter.invoke(null, new MotionEvent(2, 400, 1500, 150)) : "Outside stream leaked into bar";
        assert (Boolean) filter.invoke(null, new MotionEvent(1, 400, 1500, 200));
        assert controller.mAtms.mH.queued == 0 : "Outside drag dismissed the window";
        assert (Boolean) filter.invoke(null, new MotionEvent(0, 750, 1700, 100));
        assert (Boolean) filter.invoke(null, new MotionEvent(1, 750, 1700, 200));
        assert controller.mAtms.mH.queued == 1 : "Real outside tap stopped dismissing";
        System.out.println("Native input: shared bar hit region, scale/IME/visibility, uninterrupted bar drag and blocked outside streams passed");
    }
}
