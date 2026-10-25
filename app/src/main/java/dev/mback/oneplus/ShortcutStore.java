package dev.mback.oneplus;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.ComponentName;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One setting commits targets and their icon references together. */
final class ShortcutStore {
    static final String KEY = "mback_shortcuts_v2";
    private static final String DEFAULT_SHORTCUTS = "["
            + "{\"id\":\"com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI\"},"
            + "{\"id\":\"com.tencent.mm/com.tencent.mm.plugin.offline.ui.WalletOfflineEntranceUI\"},"
            + "{\"id\":\"com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.WxTrafficCodeActivity\"},"
            + "{\"id\":\"com.eg.android.AlipayGphone/com.alipay.mobile.scan.as.main.MainCaptureActivity\"},"
            + "{\"id\":\"com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity\"},"
            + "{\"id\":\"com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.AliTrafficCodeActivity\"}]";
    private static final ExecutorService WRITES = Executors.newSingleThreadExecutor();
    static final String INNER_KEY = "mback_inner_shortcuts_v1";
    static final String OUTER_KEY = "mback_outer_shortcuts_v1";
    private static final java.util.concurrent.ConcurrentHashMap<Integer, String> pending = new java.util.concurrent.ConcurrentHashMap<>();

    static String key(int ring) {
        if (ring == 0) return KEY;
        if (ring == 1) return OUTER_KEY;
        if (ring == 2) return INNER_KEY;
        throw new IllegalArgumentException("Unknown shortcut ring");
    }

    static int limit(int ring) { key(ring); return ring == 0 ? FlymeArcGeometry.MAX_APPS : RingConfig.MAX_APPS; }

    static final class Item {
        final ShortcutTarget target;
        String icon;
        Item(ShortcutTarget target, String icon) {
            this.target = target;
            this.icon = ShortcutIcons.validKey(icon) ? icon : "";
        }
    }

    static String snapshot(Context context) { return snapshot(context, 0); }

    static String snapshot(Context context, int ring) {
        String queued = pending.get(ring);
        if (queued != null) return "v2:" + queued;
        String current = Settings.Global.getString(context.getContentResolver(), key(ring));
        if (current != null) {
            try { new JSONArray(current); return "v2:" + current; }
            catch (org.json.JSONException error) { Log.w("MBack", "Invalid saved shortcuts; using legacy entries", error); }
        }
        if (ring != 0) return "v2:[]";
        String legacy = Settings.Global.getString(context.getContentResolver(), CornerAppMenu.APPS_KEY);
        return legacy == null || legacy.isEmpty() ? "v2:" + DEFAULT_SHORTCUTS : "v1:" + legacy;
    }

    static ArrayList<Item> read(Context context) { return read(context, 0); }
    static ArrayList<Item> read(Context context, int ring) { return decode(snapshot(context, ring), limit(ring)); }

    static ArrayList<Item> decode(String saved) { return decode(saved, FlymeArcGeometry.MAX_APPS); }

    static ArrayList<Item> decode(String saved, int limit) {
        LinkedHashMap<String, Item> result = new LinkedHashMap<>();
        try {
            if (saved.startsWith("v2:")) {
                JSONArray array = new JSONArray(saved.substring(3));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.optJSONObject(i);
                    if (entry == null) continue;
                    add(result, entry.optString("id"), entry.optString("icon"), limit);
                }
            } else {
                for (String id : saved.substring(3).split(",")) add(result, id.trim(), "", limit);
            }
        } catch (Exception error) {
            Log.w("MBack", "Cannot read shortcut configuration", error);
        }
        return new ArrayList<>(result.values());
    }

    private static void add(LinkedHashMap<String, Item> values, String id, String icon, int limit) {
        ShortcutTarget target = ShortcutTarget.parse(id);
        if (target != null && values.size() < limit) {
            values.putIfAbsent(target.id(), new Item(target, icon));
        }
    }

    static String encode(List<Item> items) throws Exception {
        JSONArray array = new JSONArray();
        for (Item item : items) {
            JSONObject entry = new JSONObject();
            entry.put("id", item.target.id());
            if (ShortcutIcons.validKey(item.icon)) entry.put("icon", item.icon);
            array.put(entry);
        }
        return array.toString();
    }

    static boolean hasPending() { return hasPending(0); }
    static boolean hasPending(int ring) { return pending.containsKey(ring); }

    static void save(List<Item> items, Consumer<Boolean> result) throws Exception { save(items, 0, result); }

    static synchronized void save(List<Item> items, int ring, Consumer<Boolean> result) throws Exception {
        // Encode before queuing so later icon edits cannot mutate an earlier save.
        String json = encode(items);
        key(ring);
        pending.put(ring, json);
        WRITES.execute(() -> {
            boolean ok;
            try { ok = write(key(ring), json); }
            catch (Exception error) { Log.w("MBack", "Cannot save shortcuts", error); ok = false; }
            if (ok) pending.remove(ring, json);
            result.accept(ok);
        });
    }

    private static boolean write(String key, String json) throws Exception {
        Process process = new ProcessBuilder("su", "-c", "settings put global " + key + " " + quote(json))
                .redirectErrorStream(true).start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (output.readLine() != null) { /* Drain errors so su cannot block. */ }
        }
        return process.waitFor() == 0;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    static ActivityInfo activity(Context context, ShortcutTarget target) throws Exception {
        return context.getPackageManager().getActivityInfo(new ComponentName(
                target.packageName, target.activityName), android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS);
    }

    static String label(Context context, Item item) {
        PresetShortcut preset = PresetShortcut.find(item.target);
        if (preset != null) return preset.label;
        try {
            if (item.target.activityName != null) return activity(context, item.target).loadLabel(context.getPackageManager()).toString();
            return context.getPackageManager().getApplicationLabel(context.getPackageManager()
                    .getApplicationInfo(item.target.packageName, 0)).toString();
        } catch (Exception error) {
            return item.target.activityName == null ? item.target.packageName : item.target.activityName;
        }
    }

    private ShortcutStore() {}
}
