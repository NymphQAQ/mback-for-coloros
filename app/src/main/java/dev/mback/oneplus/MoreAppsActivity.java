package dev.mback.oneplus;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Flyme-like "More apps" dialog launched by releasing a corner swipe over
 * the lowest (blue three-dots) icon. It is deliberately separate from the
 * gesture overlay, which must ALWAYS close on finger release.
 */
public final class MoreAppsActivity extends Activity {
    private final ArrayList<Entry> installed = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private LinearLayout list;
    private TextView title;
    private TextView action;
    private EditText search;
    private boolean busy;

    private static final class Entry {
        final String pkg;
        final String label;
        Entry(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.28f);
        window.setBackgroundDrawableResource(android.R.color.transparent);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(15), dp(14), dp(15), dp(15));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(19));
        panel.setBackground(background);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = text("更多应用", 18, 0xFF202020);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1f));
        action = text("完成", 15, 0xFF268AEB);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(9), 0, dp(4), 0);
        top.addView(action);
        action.setOnClickListener(v -> saveAndClose());
        panel.addView(top);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint("搜索应用名称或包名");
        search.setCompoundDrawablePadding(dp(8));
        search.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable searchBackground = new GradientDrawable();
        searchBackground.setColor(0xFFF2F5F8);
        searchBackground.setCornerRadius(dp(13));
        search.setBackground(searchBackground);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(42));
        searchParams.setMargins(0, dp(5), 0, dp(9));
        panel.addView(search, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!installed.isEmpty()) render();
            }
            @Override public void afterTextChanged(Editable value) {}
        });

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(1), dp(3), dp(1), dp(8));
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView footnote = text("点击添加或移除 · 最多 6 个快捷应用", 11, 0xFF879098);
        footnote.setGravity(Gravity.CENTER);
        panel.addView(footnote);
        setContentView(panel);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = Math.round(getResources().getDisplayMetrics().widthPixels * .79f);
        lp.height = Math.round(getResources().getDisplayMetrics().heightPixels * .72f);
        lp.gravity = Gravity.CENTER;
        window.setAttributes(lp);

        String saved = Settings.Global.getString(getContentResolver(), CornerAppMenu.APPS_KEY);
        if (saved == null || saved.isEmpty()) saved = CornerAppMenu.DEFAULT_APPS;
        for (String value : saved.split(",")) {
            String pkg = CornerGesture.sanitize(value.trim());
            if (pkg != null && !"none".equals(pkg) && selected.size() < FlymeArcGeometry.MAX_APPS) {
                selected.add(pkg);
            }
        }
        list.addView(text("正在加载应用…", 14, 0xFF68737D));

        new Thread(() -> {
            PackageManager pm = getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN);
            query.addCategory(Intent.CATEGORY_LAUNCHER);
            TreeMap<String, String> all = new TreeMap<>();
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = CornerGesture.sanitize(info.activityInfo.packageName);
                if (pkg == null || pkg.equals(getPackageName())) continue;
                all.putIfAbsent(pkg, String.valueOf(info.loadLabel(pm)));
            }
            ArrayList<Entry> entries = new ArrayList<>();
            for (Map.Entry<String, String> e : all.entrySet()) {
                entries.add(new Entry(e.getKey(), e.getValue()));
            }
            java.text.Collator collator = java.text.Collator.getInstance(Locale.getDefault());
            Collections.sort(entries, (a, b) -> {
                int compared = collator.compare(a.label, b.label);
                return compared == 0 ? a.pkg.compareTo(b.pkg) : compared;
            });
            runOnUiThread(() -> {
                installed.clear();
                installed.addAll(entries);
                render();
            });
        }, "mback-list-installed-apps").start();
    }

    private void render() {
        list.removeAllViews();
        list.addView(section("已添加  ·  " + selected.size() + "/" + FlymeArcGeometry.MAX_APPS));
        ArrayList<Entry> added = new ArrayList<>();
        for (String name : selected) {
            for (Entry e : installed) if (e.pkg.equals(name)) {
                added.add(e);
                break;
            }
        }
        if (added.isEmpty()) {
            TextView empty = text("滑选下方应用添加到快捷栏", 12, 0xFF9BA3AA);
            empty.setPadding(0, dp(24), 0, dp(24));
            list.addView(empty);
        } else {
            addGrid(added, true);
        }
        String query = search == null ? "" : search.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        list.addView(section(query.isEmpty() ? "全部应用" : "搜索结果"));
        String currentLetter = "";
        ArrayList<Entry> group = new ArrayList<>();
        for (Entry e : installed) {
            if (!query.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(query)
                    && !e.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            String letter = e.label.isEmpty() ? "#" :
                    Character.toString(Character.toUpperCase(e.label.charAt(0)));
            if (!letter.matches("[A-Z]")) letter = "#";
            if (!currentLetter.equals(letter) && !group.isEmpty()) {
                addGroup(currentLetter, group);
                group = new ArrayList<>();
            }
            currentLetter = letter;
            group.add(e);
        }
        if (!group.isEmpty()) addGroup(currentLetter, group);
        if (!query.isEmpty() && group.isEmpty() && currentLetter.isEmpty()) {
            list.addView(text("未找到匹配应用", 13, 0xFF8D959A));
        }
    }

    private void addGroup(String letter, ArrayList<Entry> entries) {
        list.addView(section(letter));
        addGrid(entries, false);
    }

    private void addGrid(ArrayList<Entry> entries, boolean added) {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        for (Entry e : entries) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(2), dp(10), dp(2), dp(9));

            FrameLayout iconHolder = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            try { icon.setImageDrawable(getPackageManager().getApplicationIcon(e.pkg)); }
            catch (Exception ignored) { icon.setImageResource(android.R.drawable.sym_def_app_icon); }
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(37), dp(37),
                    Gravity.CENTER);
            iconHolder.addView(icon, iconLp);
            boolean picked = selected.contains(e.pkg);
            TextView badge = text(picked ? "−" : "+", 13, Color.WHITE);
            badge.setGravity(Gravity.CENTER);
            badge.setTypeface(null, android.graphics.Typeface.BOLD);
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setColor(picked ? 0xFFE94C55 : 0xFF32B95C);
            badgeBg.setShape(GradientDrawable.OVAL);
            badge.setBackground(badgeBg);
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    dp(18), dp(18), Gravity.TOP | Gravity.RIGHT);
            badgeLp.setMargins(0, 0, dp(1), 0);
            iconHolder.addView(badge, badgeLp);
            item.addView(iconHolder, new LinearLayout.LayoutParams(dp(49), dp(45)));
            TextView name = text(e.label, 10, 0xFF3E4750);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(name, new LinearLayout.LayoutParams(-1, dp(24)));

            if (picked && !added) {
                name.setTextColor(0xFF238CFA);
                item.setAlpha(.8f);
            }
            item.setOnClickListener(v -> {
                if (busy) return;
                if (selected.remove(e.pkg)) {
                    render();
                } else if (selected.size() >= FlymeArcGeometry.MAX_APPS) {
                    Toast.makeText(this, "最多选择 6 个快捷应用", Toast.LENGTH_SHORT).show();
                } else {
                    selected.add(e.pkg);
                    render();
                }
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = dp(86);
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(item, params);
        }
        list.addView(grid);
    }

    private void saveAndClose() {
        if (busy) return;
        busy = true;
        action.setText("保存中…");
        StringBuilder csv = new StringBuilder();
        for (String pkg : selected) {
            if (csv.length() > 0) csv.append(',');
            csv.append(pkg);
        }
        if (csv.length() == 0) csv.append("none");
        final String safe = csv.toString();
        new Thread(() -> {
            boolean ok = false;
            try {
                Process process = new ProcessBuilder("su", "-c",
                        "settings put global " + CornerAppMenu.APPS_KEY + " " + safe).start();
                ok = process.waitFor() == 0;
            } catch (Exception ignored) {}
            final boolean success = ok;
            runOnUiThread(() -> {
                busy = false;
                if (success) finish();
                else {
                    action.setText("完成");
                    Toast.makeText(this, "保存失败，请检查 Root 授权", Toast.LENGTH_LONG).show();
                }
            });
        }, "mback-save-shortcuts").start();
    }

    private TextView section(String label) {
        TextView labelView = text(label, 12, 0xFF8D959A);
        labelView.setPadding(0, dp(14), 0, dp(6));
        return labelView;
    }

    private TextView text(String label, int size, int color) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private int dp(float dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }
}
