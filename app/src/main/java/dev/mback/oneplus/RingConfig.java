package dev.mback.oneplus;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

final class RingConfig {
    static final RingConfig OUTER = new RingConfig(1, "outer", "外圈", 8, 0);
    static final RingConfig INNER = new RingConfig(2, "inner", "内圈", 6, 1);
    static final int DEFAULT_ICON = 40, MAX_APPS = 8;
    final int ring;
    final int defaultCount, defaultRecent;
    final String label, enableKey, recentKey, countKey, iconKey;

    boolean enabled(Context context) { return read(context, enableKey, 0) == 1; }
    boolean recent(Context context) { return read(context, recentKey, defaultRecent) == 1; }
    int count(Context context) { return Math.max(1, Math.min(MAX_APPS, read(context, countKey, defaultCount))); }
    int icon(Context context) { return ArcConfig.icon(read(context, iconKey, DEFAULT_ICON)); }
    private static int read(Context context, String key, int fallback) {
        return Settings.Global.getInt(context.getContentResolver(), key, fallback);
    }

    List<ShortcutStore.Item> items(Context context, java.util.Set<String> reserved) {
        ArrayList<ShortcutStore.Item> result = new ArrayList<>();
        if (!enabled(context)) return result;
        int limit = count(context);
        if (!recent(context)) {
            List<ShortcutStore.Item> saved = ShortcutStore.read(context, ring);
            result.addAll(saved.subList(0, Math.min(limit, saved.size())));
            return result;
        }
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return result;
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo launcher = context.getPackageManager().resolveActivity(home, 0);
            String homePackage = launcher == null || launcher.activityInfo == null ? "" : launcher.activityInfo.packageName;
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (ActivityManager.RecentTaskInfo task : manager.getRecentTasks(40, ActivityManager.RECENT_IGNORE_UNAVAILABLE)) {
                ComponentName component = task.baseIntent == null ? null : task.baseIntent.getComponent();
                if (component == null) component = task.baseActivity;
                if (component == null) continue;
                String pkg = component.getPackageName();
                if (pkg.equals("dev.mback.oneplus") || pkg.equals("com.android.systemui")
                        || pkg.equals(homePackage) || !seen.add(pkg)) continue;
                if (context.getPackageManager().getLaunchIntentForPackage(pkg) == null) continue;
                ShortcutTarget target = ShortcutTarget.parse(pkg);
                if (target != null && reserved.contains(target.id())) continue;
                if (target != null) result.add(new ShortcutStore.Item(target, ""));
                if (result.size() == limit) break;
            }
        } catch (Exception error) { Log.w("MBack", "Cannot read recent applications", error); }
        return result;
    }

    private RingConfig(int ring, String name, String label, int defaultCount, int defaultRecent) {
        this.ring = ring; this.label = label;
        this.defaultCount = defaultCount; this.defaultRecent = defaultRecent;
        enableKey = "mback_" + name + "_ring_enabled";
        recentKey = "mback_" + name + "_ring_recent";
        countKey = "mback_" + name + "_ring_count";
        iconKey = "mback_" + name + "_ring_icon_dp";
    }
}
