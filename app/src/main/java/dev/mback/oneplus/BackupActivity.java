package dev.mback.oneplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Document-picker backup UI. All file and root operations run off the UI thread. */
public final class BackupActivity extends Activity {
    private static final int EXPORT = 1, IMPORT = 2;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<Button> actions = new ArrayList<>();
    private TextView status;
    private SettingsArchive.Snapshot pending;
    private boolean busy;
    private int previousOrientation;
    private boolean orientationLocked;
    private android.window.OnBackInvokedCallback backCallback;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = AppUi.screen(this);
        root.addView(AppUi.header(this, "备份与恢复", this::goBack, null));
        LinearLayout content = AppUi.content(this);
        AppUi.section(content, "备份内容");
        LinearLayout information = AppUi.card(content);
        TextView description = text("保存 MBack 设置、三圈快捷入口与顺序、自定义图标、图标库和已导入图标包。换机或重装后可从文件恢复。", 15, false);
        description.setPadding(0, dp(16), 0, dp(16));
        information.addView(description);
        AppUi.section(content, "备份文件");
        LinearLayout files = AppUi.card(content);
        files.setPadding(dp(18), dp(12), dp(18), dp(4));
        addAction(files, "导出备份", () -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "MBack-"
                            + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip");
            startActivityForResult(intent, EXPORT);
        });
        addAction(files, "从文件恢复", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"}), IMPORT));
        AppUi.footer(content, "恢复前会先检查文件并显示内容。确认后覆盖当前设置与图标；恢复失败会回退到原数据。备份最多包含 64 MB 图标数据。Root 授权、系统权限和模块运行状态不包含在备份中。");
        status = text("", 14, false);
        status.setBackground(AppUi.shape(this, AppUi.PALE, 16));
        status.setPadding(dp(14), dp(14), dp(14), dp(14));
        status.setVisibility(View.GONE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            backCallback = this::goBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    private void addAction(LinearLayout parent, String label, Runnable action) {
        Button button = AppUi.button(this, label, "导出备份".equals(label));
        button.setOnClickListener(v -> { if (!busy) action.run(); });
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, dp(48));
        layout.bottomMargin = dp(12);
        parent.addView(button, layout);
        actions.add(button);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = AppUi.text(this, value, size, bold ? AppUi.INK : AppUi.MUTED, bold);
        view.setLineSpacing(dp(4), 1);
        view.setGravity(Gravity.START);
        return view;
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
        Uri uri = data.getData();
        if (request == EXPORT) run("正在导出…", () -> {
            SettingsArchive.Snapshot snapshot;
            synchronized (ShortcutIcons.class) {
                checkPending();
                snapshot = SettingsArchive.capture(getFilesDir(), preferences());
                validateContents(snapshot);
            }
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("无法写入所选文件");
                SettingsArchive.write(output, snapshot);
            }
            return "备份已导出：" + snapshot.icons() + " 个图标，" + snapshot.packs() + " 套图标包";
        });
        else if (request == IMPORT) run("正在检查备份…", () -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("无法读取所选文件");
                pending = SettingsArchive.read(input);
                validateContents(pending);
            }
            return null;
        });
    }

    private void confirmRestore() {
        SettingsArchive.Snapshot snapshot = pending;
        if (snapshot == null) return;
        String summary = "备份时间：" + DateFormat.getDateTimeInstance().format(new Date(snapshot.created))
                + "\n用户设置：" + snapshot.settings.size() + " 项"
                + "\n快捷入口：" + shortcutCount(snapshot) + " 个（三圈合计）"
                + "\n图标库：" + snapshot.icons() + " 个图标"
                + "\n图标包：" + snapshot.packs() + " 套"
                + "\n\n将覆盖当前 MBack 设置和图标库，未备份的设置恢复为默认值。是否继续？";
        AppUi.show(new AlertDialog.Builder(this).setTitle("恢复此备份？").setMessage(summary)
                .setNegativeButton("取消", (dialog, which) -> cancelRestore())
                .setOnCancelListener(dialog -> cancelRestore())
                .setPositiveButton("恢复", (dialog, which) -> run("正在恢复…", () -> {
                    synchronized (ShortcutIcons.class) {
                        checkPending();
                        SettingsArchive.restore(getFilesDir(), snapshot, preferences());
                        ShortcutIcons.clearCache();
                    }
                    pending = null;
                    return "已恢复。图标和设置已更新；模块作用域及系统授权需在当前手机保留或重新授予。";
                })));
    }

    private void run(String label, Operation operation) {
        busy = true;
        if (!orientationLocked) {
            previousOrientation = getRequestedOrientation();
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
            orientationLocked = true;
        }
        status.setText(label);
        status.setTextColor(AppUi.MUTED);
        status.setBackground(AppUi.shape(this, AppUi.PALE, 16));
        status.setVisibility(View.VISIBLE);
        for (Button button : actions) button.setEnabled(false);
        io.execute(() -> {
            String message;
            boolean success;
            try { message = operation.execute(); success = true; }
            catch (Exception error) {
                android.util.Log.w("MBack", "Backup operation failed", error);
                message = "操作失败：" + (error.getMessage() == null ? "无法访问备份文件" : error.getMessage());
                pending = null;
                success = false;
            }
            String result = message;
            boolean ok = success;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                for (Button button : actions) button.setEnabled(true);
                status.setTextColor(ok ? AppUi.MUTED : AppUi.ERROR);
                status.setBackground(AppUi.shape(this, ok ? AppUi.PALE : AppUi.ERROR_PALE, 16));
                status.setText(result == null ? "备份检查通过" : result);
                if (ok && result == null) confirmRestore();
                else unlockOrientation();
                if (ok) StatusNotification.sync(this);
            });
        });
    }

    private SettingsArchive.Preferences preferences() {
        return new SettingsArchive.Preferences() {
            @Override public Map<String, String> read() {
                Map<String, String> result = new LinkedHashMap<>();
                for (String key : SettingsArchive.KEYS) {
                    String value = Settings.Global.getString(getContentResolver(), key);
                    if (value != null) result.put(key, value);
                }
                return result;
            }
            @Override public boolean notification() {
                return getSharedPreferences("mback", MODE_PRIVATE).getBoolean("show_notification", false);
            }
            @Override public void write(Map<String, String> values, boolean notification) throws Exception {
                StringBuilder script = new StringBuilder("set -e\n");
                for (String key : SettingsArchive.KEYS) {
                    String value = values.get(key);
                    script.append("settings ").append(value == null ? "delete" : "put").append(" global ")
                            .append(key);
                    if (value != null) script.append(' ').append(ShortcutStore.quote(value));
                    script.append('\n');
                }
                script.append("settings put global mback_icon_assets_revision ")
                        .append(System.currentTimeMillis()).append('\n');
                Process process = new ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start();
                Thread drain = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        while (reader.readLine() != null) { /* Root settings output can otherwise fill its pipe. */ }
                    } catch (Exception ignored) { }
                }, "MBack-backup-output");
                drain.setDaemon(true);
                drain.start();
                try (OutputStream commands = process.getOutputStream()) {
                    commands.write(script.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!process.waitFor(30, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new java.io.IOException("Root 保存超时，请检查授权");
                }
                if (process.exitValue() != 0) throw new java.io.IOException("无法写入设置，请检查 Root 授权");
                for (String key : SettingsArchive.KEYS)
                    if (!java.util.Objects.equals(values.get(key), Settings.Global.getString(getContentResolver(), key)))
                        throw new java.io.IOException("部分设置未能保存");
                if (!getSharedPreferences("mback", MODE_PRIVATE).edit().putBoolean("show_notification", notification).commit())
                    throw new java.io.IOException("通知设置未能保存");
            }
        };
    }

    private static void checkPending() throws Exception {
        for (int ring = 0; ring < 3; ring++)
            if (ShortcutStore.hasPending(ring)) throw new java.io.IOException("快捷入口正在保存，请稍后重试");
    }

    private static int shortcutCount(SettingsArchive.Snapshot snapshot) {
        int result = 0;
        for (int ring = 0; ring < 3; ring++) {
            String value = snapshot.settings.get(ShortcutStore.key(ring));
            if (value != null) try { result += new JSONArray(value).length(); } catch (Exception ignored) { }
        }
        return result;
    }

    private static void validateContents(SettingsArchive.Snapshot snapshot) throws Exception {
        for (Map.Entry<String, String> entry : snapshot.settings.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            if (key.equals(IconPackStore.MAP_KEY)) {
                if (value.getBytes(StandardCharsets.UTF_8).length > IconPackRules.MAX_MAP_BYTES)
                    throw new java.io.IOException("当前图标映射超过上限");
                JSONObject pack = new JSONObject(value);
                validatePack(pack, snapshot);
                if (pack.length() != 0 && !snapshot.files.containsKey("icon-packs/" + pack.getString("id") + ".json"))
                    throw new java.io.IOException("备份缺少当前图标包");
                continue;
            }
            if (key.equals("mback_corner_menu_apps")) {
                if (value.length() > 16 * 1024) throw new java.io.IOException("旧版快捷入口数据过大");
                if (!value.isEmpty()) for (String id : value.split(",", -1))
                    if (id.length() > 1024 || ShortcutTarget.parse(id.trim()) == null)
                        throw new java.io.IOException("旧版快捷入口无效");
                continue;
            }
            if (key.equals(ShortcutStore.KEY) || key.equals(ShortcutStore.INNER_KEY) || key.equals(ShortcutStore.OUTER_KEY)) {
                if (value.length() > 32 * 1024) throw new java.io.IOException("快捷入口数据过大");
                JSONArray array = new JSONArray(value);
                int limit = key.equals(ShortcutStore.KEY) ? FlymeArcGeometry.MAX_APPS : RingConfig.MAX_APPS;
                if (array.length() > limit) throw new java.io.IOException("快捷入口数量超出上限");
                java.util.HashSet<String> ids = new java.util.HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    String id = item.getString("id");
                    ShortcutTarget target = ShortcutTarget.parse(id);
                    if (id.length() > 1024 || target == null || !ids.add(target.id()))
                        throw new java.io.IOException("快捷入口无效或重复");
                    String icon = item.optString("icon", "");
                    if (!icon.isEmpty()) checkIcon(icon, snapshot);
                }
                continue;
            }
            int number;
            try { number = Integer.parseInt(value); }
            catch (NumberFormatException error) { throw new java.io.IOException("备份设置数值无效", error); }
            int min = 0, max = 1;
            if (key.equals(FeedbackConfig.SCALE_KEY)) { min = FeedbackConfig.SCALE_MIN; max = FeedbackConfig.SCALE_MAX; }
            else if (key.equals(FeedbackConfig.DURATION_KEY)) { min = FeedbackConfig.DURATION_MIN; max = FeedbackConfig.DURATION_MAX; }
            else if (key.equals(FeedbackConfig.VIBRATION_KEY) || key.equals(ArcConfig.HOVER_VIBRATION_KEY)
                    || key.equals(HandleGestureConfig.HOLD_VIBRATION)) max = 10;
            else if (key.equals(ArcConfig.RADIUS_KEY)) { min = ArcConfig.RADIUS_MIN; max = ArcConfig.RADIUS_MAX; }
            else if (key.equals(ArcConfig.ICON_SIZE_KEY) || key.endsWith("_ring_icon_dp")) { min = ArcConfig.ICON_MIN; max = ArcConfig.ICON_MAX; }
            else if (key.equals(ArcConfig.SIDE_MARGIN_KEY) || key.equals(ArcConfig.BOTTOM_MARGIN_KEY)) max = ArcConfig.MARGIN_MAX;
            else if (key.equals(CornerPreferences.DIM_KEY)) max = 70;
            else if (key.equals(CornerPreferences.ANIMATION_KEY)) max = CornerPreferences.MAX_ANIMATION;
            else if (key.endsWith("_ring_count")) { min = 1; max = RingConfig.MAX_APPS; }
            else if (key.equals(WindowPlacement.X) || key.equals(WindowPlacement.Y)) max = 100;
            else if (key.equals(WindowPlacement.OUTSIDE)) max = WindowPlacement.BACKGROUND;
            else if (key.equals("mback_window_handle_double_tap")) max = 2;
            else for (int index = 0; index < CornerRegion.KEYS.length; index++)
                if (key.equals(CornerRegion.KEYS[index])) { max = CornerRegion.MAX[index]; break; }
            if (number < min || number > max) throw new java.io.IOException("备份设置数值超出范围：" + key);
        }
        for (Map.Entry<String, byte[]> file : snapshot.files.entrySet())
            if (file.getKey().startsWith("icon-packs/")) {
                JSONObject pack = new JSONObject(new String(file.getValue(), StandardCharsets.UTF_8));
                validatePack(pack, snapshot);
                if (!file.getKey().equals("icon-packs/" + pack.getString("id") + ".json"))
                    throw new java.io.IOException("图标包编号不匹配");
            }
    }

    private static void validatePack(JSONObject pack, SettingsArchive.Snapshot snapshot) throws Exception {
        // An empty object represents the system-icon selection.
        if (pack.length() == 0) return;
        if (!IconPackStore.validPack(pack)) throw new java.io.IOException("图标包信息无效");
        JSONObject items = pack.getJSONObject("items");
        Iterator<String> names = items.keys();
        while (names.hasNext()) {
            String id = names.next();
            checkIcon(items.getString(id), snapshot);
        }
    }

    private static void checkIcon(String key, SettingsArchive.Snapshot snapshot) throws Exception {
        if (!ShortcutIcons.validKey(key) || !snapshot.files.containsKey("shortcut-icons/" + key + ".png"))
            throw new java.io.IOException("备份缺少所引用的图标");
    }

    private void cancelRestore() {
        pending = null;
        status.setText("已取消恢复");
        unlockOrientation();
    }
    private void unlockOrientation() {
        if (orientationLocked) {
            orientationLocked = false;
            setRequestedOrientation(previousOrientation);
        }
    }
    private void goBack() {
        if (busy) { status.setText("正在处理，请等待完成"); return; }
        finish();
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onDestroy() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && backCallback != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        io.shutdown();
        super.onDestroy();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private interface Operation { String execute() throws Exception; }
}
