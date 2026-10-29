package de.robv.android.xposed;
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public class Unhook {}
    public static class MethodHookParam {
        public Object thisObject;
        public java.lang.reflect.Member method;
        public Object[] args;
        public boolean hasThrowable() { return false; }
        public void setResult(Object result) {}
        public void setObjectExtra(String key, Object value) {}
        public Object getObjectExtra(String key) { return null; }
    }
}
