package dev.mback.oneplus;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XposedBridge;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Temporary SystemUI-owned launcher sheet.
 * It never alters the real mBack touch geometry and is removed after selection.
 */
final class CornerAppMenu {
    static final String ENABLE_KEY = "mback_corner_menu_enabled";
    static final String APPS_KEY = "mback_corner_menu_apps";
    static final String PREVIEW_KEY = "mback_corner_preview";
    private static final String TAG = "[MBack-Corner]";
    static final String DEFAULT_APPS =
            "com.tencent.mm,com.coloros.calculator,com.coloros.calendar,com.coloros.filemanager,com.android.settings,com.netease.cloudmusic";

    private final Context context;
    private final WindowManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FreeformLauncher launcher;
    private View window;
    private boolean opened;
    private final Map<View, String> selectableTiles = new LinkedHashMap<>();

    CornerAppMenu(Context context) {
        this.context = context;
        this.manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.launcher = new FreeformLauncher(context);
    }

    boolean isOpened() { return opened; }

    void show(int side) {
        if (opened) hide();
        selectableTiles.clear();
        if (context.getResources().getConfiguration().orientation !=
                android.content.res.Configuration.ORIENTATION_PORTRAIT) return;
        try {
            final int d = dp(1);
            LinearLayout sheet = new LinearLayout(context);
            sheet.setOrientation(LinearLayout.VERTICAL);
            sheet.setPadding(dp(12), dp(12), dp(12), dp(10));
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0xF2F7F9FF, 0xEDF2F4FB});
            bg.setCornerRadius(dp(28));
            bg.setStroke(dp(1), 0x99FFFFFF);
            sheet.setBackground(bg);
            sheet.setElevation(dp(16));

            LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(context);
            label.setText("小窗快捷启动   ↗");
            label.setTextSize(16f);
            label.setTextColor(0xFF172B40);
            label.setPadding(dp(10), 0, 0, 0);
            header.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1));
            TextView close = new TextView(context);
            close.setText("✕");
            close.setTextSize(20f);
            close.setGravity(Gravity.CENTER);
            close.setTextColor(0xFF5A6470);
            header.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));
            close.setOnClickListener(v -> hide());
            sheet.addView(header);

            List<String> apps = availableApps();
            if (apps.isEmpty()) {
                TextView empty = new TextView(context);
                empty.setText("还没有可用应用，请到 MBack 设置添加");
                empty.setTextSize(14);
                empty.setTextColor(Color.DKGRAY);
                empty.setGravity(Gravity.CENTER);
                sheet.addView(empty, new LinearLayout.LayoutParams(-1, dp(106)));
            } else {
                LinearLayout row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, dp(8), 0, dp(4));
                PackageManager pm = context.getPackageManager();
                for (int i = 0; i < apps.size(); i++) {
                    String pkg = apps.get(i);
                    LinearLayout tile = new LinearLayout(context);
                    tile.setGravity(Gravity.CENTER);
                    tile.setOrientation(LinearLayout.VERTICAL);
                    ImageView icon = new ImageView(context);
                    try { icon.setImageDrawable(pm.getApplicationIcon(pkg)); }
                    catch (Exception ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
                    tile.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
                    TextView name = new TextView(context);
                    try { name.setText(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); }
                    catch (Exception e) { name.setText(pkg); }
                    name.setTextSize(10);
                    name.setMaxLines(1);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    name.setTextColor(0xFF32465F);
                    name.setGravity(Gravity.CENTER);
                    name.setPadding(dp(1), dp(7), dp(1), 0);
                    tile.addView(name, new LinearLayout.LayoutParams(dp(51), dp(24)));
                    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(102), 1);
                    tile.setPadding(dp(2), 0, dp(2), 0);
                    row.addView(tile, tileParams);
                    // A shallow arc that echoes the Flyme corner shortcut fan.
                    int[] lifts = {0, 10, 18, 18, 10, 0};
                    tile.setTranslationY(-dp(lifts[Math.min(i, lifts.length - 1)]));
                    selectableTiles.put(tile, pkg);
                    final String target = pkg;
                    tile.setOnClickListener(v -> {
                        hide();
                        launcher.open(target);
                    });
                }
                sheet.addView(row, new LinearLayout.LayoutParams(-1, dp(120)));
            }

            TextView footer = new TextView(context);
            footer.setGravity(Gravity.CENTER);
            footer.setText("点击以 ColorOS 小窗打开 · 应用可在 MBack 中调整");
            footer.setTextColor(0xFF647388);
            footer.setTextSize(11);
            sheet.addView(footer, new LinearLayout.LayoutParams(-1, dp(30)));

            FrameLayout outer = new FrameLayout(context);
            FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            card.setMargins(dp(16), 0, dp(16), dp(28));
            outer.addView(sheet, card);
            outer.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hide(); return true; }
                return false;
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, dp(265),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.BOTTOM;
            lp.setTitle("MBack Corner Apps");
            window = outer;
            manager.addView(outer, lp);
            opened = true;
            outer.setAlpha(0f);
            outer.setTranslationY(dp(40));
            outer.animate().alpha(1f).translationY(0f).setDuration(190).start();
            XposedBridge.log(TAG + " menu opened apps=" + apps.size() + " side=" + side);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " menu show failed: " + error);
            hide();
        }
    }

    void highlightBySwipe(float rawX, float rawY) {
        if (!opened) return;
        for (View tile : selectableTiles.keySet()) {
            boolean over = inside(tile, rawX, rawY);
            tile.setScaleX(over ? 1.09f : 1f);
            tile.setScaleY(over ? 1.09f : 1f);
            tile.setAlpha(over ? 1f : .87f);
        }
    }

    private boolean inside(View view, float x, float y) {
        if (!view.isShown() || view.getWidth() <= 0) return false;
        int[] pos = new int[2];
        view.getLocationOnScreen(pos);
        return x >= pos[0] && x < pos[0] + view.getWidth()
                && y >= pos[1] && y < pos[1] + view.getHeight();
    }

    boolean chooseBySwipe(float rawX, float rawY) {
        if (!opened) return false;
        for (Map.Entry<View, String> entry : selectableTiles.entrySet()) {
            View tile = entry.getKey();
            if (inside(tile, rawX, rawY)) {
                final String pkg = entry.getValue();
                XposedBridge.log(TAG + " swipe-selected: " + pkg);
                hide();
                launcher.open(pkg);
                return true;
            }
        }
        return false;
    }

    void hide() {
        selectableTiles.clear();
        View old = window;
        window = null;
        opened = false;
        if (old != null) {
            try { manager.removeViewImmediate(old); }
            catch (Throwable error) { XposedBridge.log(TAG + " hide failed: " + error); }
        }
    }

    private List<String> availableApps() {
        String raw = Settings.Global.getString(context.getContentResolver(), APPS_KEY);
        if (raw == null || raw.isEmpty()) raw = DEFAULT_APPS;
        ArrayList<String> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String piece : raw.split(",")) {
            String pkg = CornerGesture.sanitize(piece.trim());
            if (pkg == null || result.contains(pkg)) continue;
            if (pm.getLaunchIntentForPackage(pkg) == null) continue;
            result.add(pkg);
            if (result.size() >= 6) break;
        }
        return result;
    }

    private int dp(float count) {
        return Math.round(context.getResources().getDisplayMetrics().density * count);
    }
}
