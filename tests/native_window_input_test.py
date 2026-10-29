"""Exercise the real outside predicate and input filter with small framework doubles."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = Path(os.environ.get("ANDROID_HOME", Path.home() / "Library/Android/sdk")) / "platforms/android-35/android.jar"
DOUBLES = {
    "android/graphics/Rect.java": """package android.graphics; public class Rect {
        public int left, top, right, bottom;
        public Rect() {} public Rect(int l,int t,int r,int b) { left=l;top=t;right=r;bottom=b; }
        public Rect(Rect r) { this(r.left,r.top,r.right,r.bottom); }
        public boolean isEmpty() { return left>=right || top>=bottom; }
        public boolean contains(int x,int y) { return x>=left && x<right && y>=top && y<bottom; }
    }""",
    "android/graphics/Region.java": "package android.graphics; public class Region { public boolean contains(int x,int y) { return false; } }",
    "android/app/KeyguardManager.java": "package android.app; public class KeyguardManager { public boolean isDeviceLocked() { return false; } }",
    "android/util/DisplayMetrics.java": "package android.util; public class DisplayMetrics { public float density=3; }",
    "android/content/res/Resources.java": "package android.content.res; public class Resources { public android.util.DisplayMetrics getDisplayMetrics() { return new android.util.DisplayMetrics(); } }",
    "android/content/Context.java": """package android.content; public class Context {
        public <T> T getSystemService(Class<T> type) { return type.cast(new android.app.KeyguardManager()); }
        public ContentResolver getContentResolver() { return null; }
        public android.content.res.Resources getResources() { return new android.content.res.Resources(); }
    }""",
    "android/provider/Settings.java": """package android.provider; public class Settings { public static class Global {
        public static int getInt(android.content.ContentResolver r,String key,int fallback) { return 3; }
    } }""",
    "android/view/View.java": """package android.view; public class View {
        public android.graphics.Rect handle = new android.graphics.Rect(200,2350,1000,2800);
        public int action=10;
        public boolean shown=true, attached=true;
        public boolean isShown() { return shown; } public boolean isAttachedToWindow() { return attached; }
        public int getFlexibleTaskActionFlag(int x,int y) { return handle.contains(x,y) ? action : 0; }
    }""",
    "android/view/ViewGroup.java": "package android.view; public class ViewGroup extends View {}",
    "android/view/ViewConfiguration.java": """package android.view; public class ViewConfiguration {
        public static ViewConfiguration get(android.content.Context c) { return new ViewConfiguration(); }
        public int getScaledTouchSlop() { return 24; } public static int getLongPressTimeout() { return 500; }
    }""",
    "android/view/MotionEvent.java": """package android.view; public class MotionEvent {
        int action; float x,y; long time;
        public MotionEvent(int a,float x,float y,long t) { action=a;this.x=x;this.y=y;time=t; }
        public int getActionMasked() { return action; } public int getDeviceId() { return 7; }
        public int getDisplayId() { return 0; } public int getPointerCount() { return 1; }
        public boolean isFromSource(int s) { return true; }
        public float getRawX() { return x; } public float getRawY() { return y; }
        public long getEventTime() { return time; } public long getDownTime() { return 100; }
    }""",
    "android/os/Handler.java": "package android.os; public class Handler { public int queued; public boolean post(Runnable r) { queued++; return true; } }",
    "de/robv/android/xposed/XposedHelpers.java": """package de.robv.android.xposed;
        public class XposedHelpers {
            public static Object getObjectField(Object o,String name) {
                try { java.lang.reflect.Field f=o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
                catch (Exception e) { throw new IllegalStateException(name,e); }
            }
            public static Object callMethod(Object o,String name,Object... args) {
                for (java.lang.reflect.Method m:o.getClass().getDeclaredMethods())
                    if (m.getName().equals(name) && m.getParameterCount()==args.length) {
                        try { m.setAccessible(true); return m.invoke(o,args); }
                        catch (Exception e) { throw new IllegalStateException(name,e); }
                    }
                throw new IllegalStateException(name);
            }
        }""",
}

with tempfile.TemporaryDirectory(prefix="mback-input-test-") as directory:
    temp = Path(directory)
    production, doubles = temp / "production", temp / "doubles"
    production.mkdir()
    doubles.mkdir()
    sources = list((ROOT / "stubs/de").rglob("*.java"))
    sources += [ROOT / "app/src/main/java/dev/mback/oneplus" / (name + ".java") for name in (
        "NativeWindowHook", "NativeHandleGestures", "WindowPlacement", "HandleGestureConfig", "TouchFeedback", "FeedbackConfig")]
    subprocess.run(["rtk", "proxy", "javac", "-cp", str(ANDROID), "-d", str(production), *map(str, sources)], check=True)
    fixtures = []
    for name, source in DOUBLES.items():
        path = temp / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source)
        fixtures.append(path)
    subprocess.run(["rtk", "proxy", "javac", "-cp", os.pathsep.join(map(str, (production, ANDROID))),
                    "-d", str(doubles), *map(str, fixtures), str(ROOT / "tests/dev/mback/oneplus/NativeWindowInputTest.java")], check=True)
    subprocess.run(["rtk", "proxy", "java", "-ea", "-cp", os.pathsep.join(map(str, (doubles, production, ANDROID))),
                    "dev.mback.oneplus.NativeWindowInputTest"], check=True)
