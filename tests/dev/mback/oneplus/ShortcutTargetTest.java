package dev.mback.oneplus;

public final class ShortcutTargetTest {
    public static void main(String[] args) {
        int count = 0;
        ShortcutTarget app = ShortcutTarget.parse("com.example.app");
        check(app != null && app.activityName == null && "com.example.app".equals(app.id())); count++;
        ShortcutTarget activity = ShortcutTarget.parse("com.example.app/.Screen");
        check(activity != null && "com.example.app.Screen".equals(activity.activityName)); count++;
        check("com.example.app/com.example.app.Screen".equals(activity.id())); count++;
        check(activity.id().equals(ShortcutTarget.parse(activity.id()).id())); count++;
        check(ShortcutTarget.parse("com.example.app/other.package.Screen") != null); count++;
        check(ShortcutTarget.parse("com.example.app/com.example.app.Settings$Nested") != null); count++;
        check(!app.id().equals(activity.id())); count++;
        for (String invalid : new String[]{null, "", "none", "com.app/", "/Screen", "com.app/a/b",
                "com.app;id", "com.app/$(id)", "com.app/Screen'", "com.app/Screen\n", "com.app/../Screen",
                "com.app/Screen,Other", "com.app/Screen Name", "com.app/../../x", "com.app/7Screen"}) {
            check(ShortcutTarget.parse(invalid) == null); count++;
        }
        System.out.println("ShortcutTargetTest passed (" + count + " assertions)");
    }

    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
