package de.robv.android.xposed;
import java.lang.reflect.Member;
import java.util.Set;
public final class XposedBridge {
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) { return null; }
    public static void log(String text) {}
}
