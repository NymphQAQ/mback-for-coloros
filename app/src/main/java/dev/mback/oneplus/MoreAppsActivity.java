package dev.mback.oneplus;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.TreeMap;

/** Native small-window content with drag ordering and automatic shortcut saving. */
public final class MoreAppsActivity extends Activity {
    private static final int PICK_ICON = 73;
    static final String RING_EXTRA = "mback_edit_ring";
    private int editingRing;
    private final ArrayList<Entry> installed = new ArrayList<>();
    private final LinkedHashMap<String, ShortcutStore.Item> selected = new LinkedHashMap<>();
    private LinearLayout list;
    private TextView saveStatus;
    private EditText search;
    private ActivityAdapter activityAdapter;
    private String pendingIcon, pickerPackage;
    private boolean busy;
    private int saveRevision;
    private final Runnable renderSearch = () -> { if (!isDestroyed() && !installed.isEmpty()) render(); };
    private final ContentObserver iconShapeObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean selfChange) {
            if (!installed.isEmpty()) {
                render();
                if (activityAdapter != null) activityAdapter.notifyDataSetChanged();
            }
        }
    };

    private static final class Entry {
        final String pkg, label;
        final Drawable icon;
        Entry(String pkg, String label, Drawable icon) { this.pkg = pkg; this.label = label; this.icon = icon; }
    }

    private static final class ActivityEntry {
        final ShortcutTarget target;
        final String label, detail;
        final boolean enabled;
        ActivityEntry(ShortcutTarget target, String label, String detail, boolean enabled) {
            this.target = target; this.label = label; this.detail = detail; this.enabled = enabled;
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        editingRing = state == null ? getIntent().getIntExtra(RING_EXTRA, 0) : state.getInt(RING_EXTRA, 0);
        if (editingRing < 0 || editingRing > 2) editingRing = 0;
        setTitle(ringTitle());
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        for (ShortcutStore.Item item : state == null || !state.containsKey("draft") ? ShortcutStore.read(this, editingRing)
                : ShortcutStore.decode("v2:" + state.getString("draft", "[]"), ShortcutStore.limit(editingRing))) selected.put(item.target.id(), item);
        pendingIcon = state == null ? null : state.getString("pending_icon");
        String restorePicker = state == null ? null : state.getString("picker_package");
        LinearLayout panel = column();
        panel.setForceDarkAllowed(false);
        panel.setPadding(dp(15), dp(14), dp(15), dp(15));
        panel.setBackgroundColor(Color.WHITE);
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            panel.setPadding(dp(15) + insets.getSystemWindowInsetLeft(), dp(14) + insets.getSystemWindowInsetTop(),
                    dp(15) + insets.getSystemWindowInsetRight(), dp(15) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(ringTitle(), 18, MainActivity.INK);
        title.setTypeface(Typeface.create("OPPOSans", Typeface.BOLD));
        top.addView(title, new LinearLayout.LayoutParams(0, dp(42), 1));
        saveStatus = text("更改自动保存", 11, MainActivity.MUTED);
        saveStatus.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        top.addView(saveStatus, new LinearLayout.LayoutParams(-2, dp(48)));
        Button home = new Button(this);
        home.setText("主页");
        home.setTextSize(12);
        home.setAllCaps(false);
        home.setTextColor(MainActivity.GREEN);
        home.setContentDescription("进入 MBack 主页");
        home.setPadding(0, 0, 0, 0);
        home.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x2228684E), shape(MainActivity.PALE, 14), null));
        home.setStateListAnimator(null);
        home.setOnClickListener(v -> openHome());
        LinearLayout.LayoutParams homeParams = new LinearLayout.LayoutParams(dp(60), dp(48));
        homeParams.leftMargin = dp(8);
        top.addView(home, homeParams);
        panel.addView(top);
        search = search("搜索名称或包名（含无桌面入口）");
        panel.addView(search, spaced(-1, 42));
        watch(search, () -> { search.removeCallbacks(renderSearch); search.postDelayed(renderSearch, 160); });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = column();
        list.setPadding(dp(1), dp(3), dp(1), dp(8));
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView hint = text("长按已添加图标排序 · 更改自动保存", 11, MainActivity.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        panel.addView(hint);
        setContentView(panel);
        getContentResolver().registerContentObserver(Settings.Global.getUriFor(ArcConfig.ORIGINAL_ICON_KEY),
                false, iconShapeObserver);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        if (ShortcutStore.hasPending(editingRing)) saveChanges();
        list.addView(text("正在加载应用…", 14, MainActivity.MUTED));
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            TreeMap<String, Entry> apps = new TreeMap<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                if (info.activityInfo == null) continue;
                String pkg = info.activityInfo.packageName;
                if (apps.containsKey(pkg)) continue;
                try { apps.put(pkg, new Entry(pkg, info.loadLabel(pm).toString(), pm.getApplicationIcon(pkg))); }
                catch (Exception ignored) { /* An app may be removed while loading. */ }
            }
            for (android.content.pm.ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (!apps.containsKey(info.packageName)) apps.put(info.packageName,
                        new Entry(info.packageName, pm.getApplicationLabel(info).toString(), null));
            }
            ArrayList<Entry> entries = new ArrayList<>(apps.values());
            java.text.Collator collator = java.text.Collator.getInstance(Locale.getDefault());
            Collections.sort(entries, (a, b) -> collator.compare(a.label, b.label));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                installed.addAll(entries);
                render();
                if (restorePicker != null) showActivities(restorePicker);
            });
        }, "mback-list-apps").start();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        int requested = intent.getIntExtra(RING_EXTRA, 0);
        if (requested < 0 || requested > 2) requested = 0;
        if (requested != editingRing) {
            editingRing = requested;
            selected.clear();
            for (ShortcutStore.Item item : ShortcutStore.read(this, editingRing)) selected.put(item.target.id(), item);
            pendingIcon = null;
            pickerPackage = null;
            saveRevision++;
            recreate();
        }
    }

    private String ringTitle() { return editingRing == 1 ? "外圈应用" : editingRing == 2 ? "内圈应用" : "更多应用"; }

    private void openHome() {
        if (busy) {
            Toast.makeText(this, "正在处理图标，请稍后", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            Bundle options = ActivityOptions.makeBasic().toBundle();
            // Do not inherit the More window's ColorOS floating mode.
            options.putInt("android.activity.windowingMode", 1);
            startActivity(intent, options);
            finishAndRemoveTask();
        } catch (Exception error) {
            android.util.Log.w("MBack", "Cannot open home", error);
            Toast.makeText(this, "无法打开主页，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onDestroy() {
        getContentResolver().unregisterContentObserver(iconShapeObserver);
        search.removeCallbacks(renderSearch);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt(RING_EXTRA, editingRing);
        try { state.putString("draft", ShortcutStore.encode(new ArrayList<>(selected.values()))); }
        catch (Exception error) { throw new IllegalStateException(error); }
        state.putString("pending_icon", pendingIcon);
        state.putString("picker_package", pickerPackage);
        super.onSaveInstanceState(state);
    }

    private void render() {
        if (list == null) return;
        list.removeAllViews();
        list.addView(section((editingRing == 0 ? "已添加  ·  " : ringTitle().substring(0, 2) + "已添加  ·  ")
                + selected.size() + "/" + ShortcutStore.limit(editingRing)));
        if (editingRing != 0) list.addView(text("关闭本圈最近应用后使用此列表，按本圈数量设置显示。", 11, MainActivity.MUTED));
        if (selected.isEmpty()) list.addView(text("还没有快捷项，点击下方应用选择。", 13, MainActivity.MUTED));
        else {
            GridLayout grid = grid();
            for (ShortcutStore.Item item : selected.values()) {
                String label = ShortcutStore.label(this, item);
                if (item.target.activityName != null && PresetShortcut.find(item.target) == null) {
                    String name = item.target.activityName;
                    label += "\n" + name.substring(name.lastIndexOf('.') + 1);
                }
                addTile(grid, item.target.packageName, label, item, null);
            }
            enableReordering(grid);
            addGrid(grid);
        }
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        GridLayout presets = grid();
        for (PresetShortcut preset : PresetShortcut.ALL) {
            if (!query.isEmpty() && !preset.label.toLowerCase(Locale.ROOT).contains(query)
                    && !preset.target.packageName.toLowerCase(Locale.ROOT).contains(query)) continue;
            addTile(presets, preset.target.packageName, preset.label, selected.get(preset.target.id()), preset);
        }
        if (presets.getChildCount() > 0) {
            list.addView(section("预置功能"));
            addGrid(presets);
        }
        list.addView(section(query.isEmpty() ? "桌面应用" : "搜索结果"));
        GridLayout grid = grid();
        int matches = 0;
        for (Entry app : installed) {
            if (query.isEmpty() && app.icon == null) continue;
            if (!query.isEmpty() && !app.label.toLowerCase(Locale.ROOT).contains(query)
                    && !app.pkg.toLowerCase(Locale.ROOT).contains(query)) continue;
            addTile(grid, app.pkg, app.label, null, null);
            matches++;
        }
        addGrid(grid);
        if (matches == 0) list.addView(text("未找到匹配应用", 13, MainActivity.MUTED));
    }

    private GridLayout grid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(4);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        return grid;
    }

    private void addGrid(GridLayout grid) {
        while (grid.getChildCount() % 4 != 0) {
            GridLayout.LayoutParams space = new GridLayout.LayoutParams();
            space.width = 0;
            space.height = 0;
            space.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            grid.addView(new View(this), space);
        }
        list.addView(grid);
    }

    private Drawable appIcon(String pkg) {
        for (Entry app : installed) if (app.pkg.equals(pkg)) {
            if (app.icon == null) break;
            Drawable.ConstantState state = app.icon.getConstantState();
            return ShortcutIcons.display(this, state == null ? app.icon : state.newDrawable(getResources()));
        }
        try { return ShortcutIcons.display(this, getPackageManager().getApplicationIcon(pkg)); }
        catch (Exception error) { return ShortcutIcons.display(this, getDrawable(android.R.drawable.sym_def_app_icon)); }
    }

    private Drawable icon(ShortcutStore.Item item) {
        try { return ShortcutIcons.load(this, item); }
        catch (Exception error) { return appIcon(item.target.packageName); }
    }

    private void addTile(GridLayout grid, String pkg, String label, ShortcutStore.Item added, PresetShortcut preset) {
        boolean available = preset == null || preset.available(this);
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(2), dp(2), dp(2), dp(2));
        item.setContentDescription(preset != null ? "预置功能：" + preset.label
                : added == null ? "选择应用：" + pkg : "快捷项：" + added.target.id());
        if (added != null && preset == null) {
            item.setTag(added.target.id());
            item.setOnLongClickListener(v -> !busy && item.startDragAndDrop(
                    ClipData.newPlainText("shortcut", added.target.id()),
                    new View.DragShadowBuilder(item), added.target.id(), 0));
        }
        FrameLayout holder = new FrameLayout(this);
        holder.setClipChildren(false);
        ImageView image = new ImageView(this);
        image.setImageDrawable(added != null ? icon(added) : preset != null ? preset.icon(this) : appIcon(pkg));
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        holder.addView(image, new FrameLayout.LayoutParams(dp(37), dp(37), Gravity.CENTER));
        TextView badge = text(added == null ? "+" : "−", 13, Color.WHITE);
        badge.setIncludeFontPadding(false);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(added == null ? 0xFF32B95C : 0xFFE94C55, 30));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.RIGHT);
        holder.addView(badge, badgeParams);
        item.addView(holder, new LinearLayout.LayoutParams(dp(63), dp(63)));
        TextView name = text(label + (available ? "" : "\n（不可用）"), 10, MainActivity.INK);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        item.addView(name, new LinearLayout.LayoutParams(-1, dp(32)));
        item.setOnClickListener(v -> {
            if (preset != null) toggleShortcut(preset.target, available);
            else if (!busy) showActivities(pkg);
        });
        item.setAlpha(available ? 1f : .5f);
        badge.setContentDescription(preset != null ? (added == null ? "添加预置功能：" : "移除预置功能：") + preset.label
                : added == null ? "选择应用：" + pkg : "移除快捷项：" + added.target.id());
        badge.setOnClickListener(v -> {
            if (busy) return;
            if (preset != null) toggleShortcut(preset.target, available);
            else if (added == null) showActivities(pkg);
            else { selected.remove(added.target.id()); refresh(); }
        });
        if (added != null && added.target.activityName != null && preset == null) {
            TextView edit = text("修改图标", 10, MainActivity.GREEN);
            edit.setGravity(Gravity.CENTER);
            edit.setContentDescription("修改图标：" + added.target.id());
            edit.setOnClickListener(v -> { if (!busy) editIcon(added); });
            item.addView(edit, new LinearLayout.LayoutParams(-1, dp(24)));
        }
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = dp(added == null || preset != null ? 101 : 125);
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        grid.addView(item, lp);
    }

    private int dropPosition(GridLayout grid, DragEvent event) {
        int columns = grid.getColumnCount();
        int column = Math.max(0, Math.min(columns - 1, (int) (event.getX() * columns / grid.getWidth())));
        int row = Math.max(0, (int) (event.getY() / grid.getChildAt(0).getHeight()));
        return Math.min(selected.size() - 1, row * columns + column);
    }

    private void enableReordering(GridLayout grid) {
        int[] hovered = {-1};
        grid.setOnDragListener((view, event) -> {
            Object source = event.getLocalState();
            if (!(source instanceof String) || !selected.containsKey(source)) return false;
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    for (int i = 0; i < selected.size(); i++) {
                        View tile = grid.getChildAt(i);
                        tile.setAlpha(source.equals(tile.getTag()) ? .35f : 1f);
                    }
                    return true;
                case DragEvent.ACTION_DRAG_ENTERED:
                case DragEvent.ACTION_DRAG_LOCATION:
                    int position = dropPosition(grid, event);
                    if (position != hovered[0]) {
                        hovered[0] = position;
                        for (int i = 0; i < selected.size(); i++) grid.getChildAt(i).setBackground(
                                i == position ? shape(MainActivity.PALE, 12) : null);
                    }
                    return true;
                case DragEvent.ACTION_DROP:
                    ArrayList<String> ids = new ArrayList<>(selected.keySet());
                    int from = ids.indexOf(source), to = dropPosition(grid, event);
                    if (from != to && to >= 0) {
                        ArrayList<ShortcutStore.Item> ordered = new ArrayList<>(selected.values());
                        ordered.add(to, ordered.remove(from));
                        selected.clear();
                        for (ShortcutStore.Item item : ordered) selected.put(item.target.id(), item);
                        refresh();
                    }
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                case DragEvent.ACTION_DRAG_ENDED:
                    hovered[0] = -1;
                    for (int i = 0; i < grid.getChildCount(); i++) {
                        grid.getChildAt(i).setBackground(null);
                        if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) grid.getChildAt(i).setAlpha(1f);
                    }
                    return true;
                default:
                    return true;
            }
        });
    }

    private void showActivities(String pkg) {
        if (busy) return;
        pickerPackage = pkg;
        Dialog dialog = new Dialog(this);
        LinearLayout panel = column();
        panel.setPadding(dp(18), dp(16), dp(18), dp(10));
        panel.setBackground(shape(Color.WHITE, 20));
        String appName = pkg;
        for (Entry app : installed) if (app.pkg.equals(pkg)) appName = app.label;
        panel.addView(text(appName, 19, MainActivity.INK));
        TextView subtitle = text("启动应用或添加活动 · 已添加 " + selected.size() + "/" + ShortcutStore.limit(editingRing), 12, MainActivity.MUTED);
        subtitle.setPadding(0, dp(8), 0, dp(8));
        panel.addView(subtitle);
        EditText filter = search("搜索活动名称或类名");
        filter.setContentDescription("搜索活动");
        panel.addView(filter, spaced(-1, 44));
        ListView rows = new ListView(this);
        rows.setDividerHeight(dp(1));
        ActivityAdapter adapter = new ActivityAdapter(pkg, subtitle);
        rows.setAdapter(adapter);
        panel.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView close = text("返回", 14, MainActivity.GREEN);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> dialog.dismiss());
        panel.addView(close, new LinearLayout.LayoutParams(-1, dp(48)));
        watch(filter, () -> adapter.filter(filter.getText().toString()));
        dialog.setContentView(panel);
        dialog.setOnDismissListener(ignored -> { if (activityAdapter == adapter) { activityAdapter = null; pickerPackage = null; } });
        dialog.show();
        sizeWindow(dialog.getWindow(), .91f, .78f);
        activityAdapter = adapter;
        new Thread(() -> {
            ArrayList<ActivityEntry> entries = new ArrayList<>();
            boolean launchable = getPackageManager().getLaunchIntentForPackage(pkg) != null;
            entries.add(new ActivityEntry(ShortcutTarget.parse(pkg), "启动应用",
                    launchable ? "打开应用主界面" : "此应用没有桌面启动入口", launchable));
            try {
                ActivityInfo[] activities = getPackageManager().getPackageInfo(pkg,
                        PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS).activities;
                if (activities != null) for (ActivityInfo info : activities) {
                    ShortcutTarget target = ShortcutTarget.parse(pkg + "/" + info.name);
                    if (target != null) entries.add(new ActivityEntry(target, info.loadLabel(getPackageManager()).toString(),
                            info.name, info.enabled && info.applicationInfo.enabled));
                }
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "无法读取此应用的活动", Toast.LENGTH_LONG).show());
            }
            runOnUiThread(() -> { if (!isDestroyed() && dialog.isShowing()) { adapter.all.addAll(entries); adapter.filter(filter.getText().toString()); } });
        }, "mback-list-activities").start();
    }

    private final class ActivityAdapter extends BaseAdapter {
        final ArrayList<ActivityEntry> all = new ArrayList<>(), shown = new ArrayList<>();
        final String pkg;
        final TextView subtitle;
        ActivityAdapter(String pkg, TextView subtitle) { this.pkg = pkg; this.subtitle = subtitle; }
        void filter(String query) {
            shown.clear();
            String match = query.trim().toLowerCase(Locale.ROOT);
            for (ActivityEntry row : all) if (row.label.toLowerCase(Locale.ROOT).contains(match)
                    || row.detail.toLowerCase(Locale.ROOT).contains(match)) shown.add(row);
            notifyDataSetChanged();
        }
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public void notifyDataSetChanged() {
            subtitle.setText(all.isEmpty() ? "正在读取活动…" : "共 " + (all.size() - 1)
                    + " 个活动 · 已添加 " + selected.size() + "/" + ShortcutStore.limit(editingRing));
            super.notifyDataSetChanged();
        }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(MoreAppsActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(8), 0, dp(8));
                ImageView image = new ImageView(MoreAppsActivity.this);
                image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                row.addView(image, new LinearLayout.LayoutParams(dp(40), dp(40)));
                LinearLayout labels = column();
                labels.setPadding(dp(10), 0, dp(4), 0);
                labels.addView(text("", 14, MainActivity.INK));
                TextView detail = text("", 10, MainActivity.MUTED);
                detail.setMaxLines(3);
                detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(detail);
                row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                TextView edit = text("图标", 12, MainActivity.GREEN);
                edit.setGravity(Gravity.CENTER);
                row.addView(edit, new LinearLayout.LayoutParams(dp(44), dp(48)));
                TextView pick = text("", 22, MainActivity.GREEN);
                pick.setGravity(Gravity.CENTER);
                row.addView(pick, new LinearLayout.LayoutParams(dp(44), dp(48)));
            }
            ActivityEntry entry = shown.get(position);
            ShortcutStore.Item picked = selected.get(entry.target.id());
            ImageView image = (ImageView) row.getChildAt(0);
            image.setImageDrawable(picked == null ? appIcon(pkg) : icon(picked));
            LinearLayout labels = (LinearLayout) row.getChildAt(1);
            ((TextView) labels.getChildAt(0)).setText(entry.label + (entry.enabled ? "" : "（已停用）"));
            ((TextView) labels.getChildAt(1)).setText(entry.detail);
            TextView edit = (TextView) row.getChildAt(2), pick = (TextView) row.getChildAt(3);
            edit.setVisibility(picked != null && entry.target.activityName != null ? View.VISIBLE : View.GONE);
            edit.setContentDescription("修改图标：" + entry.target.id());
            edit.setOnClickListener(v -> { if (!busy && picked != null) editIcon(picked); });
            pick.setText(picked == null ? "+" : "−");
            pick.setContentDescription((picked == null ? "添加快捷项：" : "移除快捷项：") + entry.target.id());
            View.OnClickListener toggle = v -> {
                toggleShortcut(entry.target, entry.enabled);
            };
            pick.setOnClickListener(toggle);
            row.setOnClickListener(toggle);
            image.setOnClickListener(v -> {
                if (busy) return;
                if (picked != null && entry.target.activityName != null) editIcon(picked);
                else toggle.onClick(v);
            });
            row.setContentDescription("活动：" + entry.target.id());
            row.setAlpha(entry.enabled ? 1f : .5f);
            return row;
        }
    }

    private void toggleShortcut(ShortcutTarget target, boolean available) {
        if (busy) return;
        if (!selected.containsKey(target.id())) {
            if (!available) {
                Toast.makeText(this, "此功能不可用，请确认应用已安装且活动已启用", Toast.LENGTH_SHORT).show();
                return;
            }
            if (selected.size() >= ShortcutStore.limit(editingRing)) {
                Toast.makeText(this, "最多选择 " + ShortcutStore.limit(editingRing) + " 个快捷项", Toast.LENGTH_SHORT).show();
                return;
            }
            selected.put(target.id(), new ShortcutStore.Item(target, ""));
        } else selected.remove(target.id());
        refresh();
    }

    private void refresh() {
        render();
        if (activityAdapter != null) activityAdapter.notifyDataSetChanged();
        saveChanges();
    }

    private void editIcon(ShortcutStore.Item item) {
        new AlertDialog.Builder(this).setTitle("活动图标")
                .setItems(new String[]{"选择图片", PresetShortcut.find(item.target) == null ? "恢复应用图标" : "恢复预置图标"}, (dialog, choice) -> {
                    if (choice == 1) { item.icon = ""; refresh(); return; }
                    pendingIcon = item.target.id();
                    Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                            .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                    try { startActivityForResult(pick, PICK_ICON); }
                    catch (android.content.ActivityNotFoundException error) {
                        pendingIcon = null;
                        Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_LONG).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_ICON) return;
        ShortcutStore.Item item = selected.get(pendingIcon);
        pendingIcon = null;
        if (result != RESULT_OK || data == null || data.getData() == null || item == null) return;
        busy = true;
        new Thread(() -> {
            String key = null;
            try { key = ShortcutIcons.importImage(this, data.getData()); }
            catch (Exception error) { android.util.Log.w("MBack", "Cannot import activity icon", error); }
            final String imported = key;
            runOnUiThread(() -> {
                busy = false;
                if (isDestroyed()) return;
                if (imported != null) { item.icon = imported; refresh(); }
                else Toast.makeText(this, "无法读取这张图片，请选择其他图片", Toast.LENGTH_LONG).show();
            });
        }, "mback-import-icon").start();
    }

    private void saveChanges() {
        int revision = ++saveRevision;
        saveStatus.setText("保存中…");
        saveStatus.setTextColor(MainActivity.MUTED);
        saveStatus.setOnClickListener(null);
        try {
            ShortcutStore.save(new ArrayList<>(selected.values()), editingRing, success -> runOnUiThread(() -> {
                if (!isDestroyed() && revision == saveRevision) showSaveResult(success);
            }));
        } catch (Exception error) {
            android.util.Log.w("MBack", "Cannot prepare shortcuts for saving", error);
            showSaveResult(false);
        }
    }

    private void showSaveResult(boolean success) {
        saveStatus.setText(success ? "已自动保存" : "保存失败 · 重试");
        saveStatus.setTextColor(success ? MainActivity.GREEN : 0xFFC34F51);
        saveStatus.setOnClickListener(success ? null : v -> saveChanges());
        if (!success) Toast.makeText(this, "保存失败，请检查 Root 授权后点击重试", Toast.LENGTH_LONG).show();
    }

    private void sizeWindow(Window window, float width, float height) {
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.28f);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        WindowManager.LayoutParams lp = window.getAttributes();
        View content = getWindow().getDecorView();
        lp.width = Math.round(content.getWidth() * width);
        lp.height = Math.round(content.getHeight() * height);
        lp.gravity = Gravity.CENTER;
        window.setAttributes(lp);
    }

    private EditText search(String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setHint(hint);
        input.setPadding(dp(12), dp(6), dp(12), dp(6));
        input.setBackground(shape(MainActivity.BACKGROUND, 13));
        return input;
    }

    private void watch(EditText input, Runnable changed) {
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
            @Override public void afterTextChanged(Editable text) {}
        });
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView section(String label) { TextView text = text(label, 12, MainActivity.MUTED); text.setPadding(0, dp(14), 0, dp(6)); return text; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private LinearLayout.LayoutParams spaced(int width, int height) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(height)); lp.setMargins(0, dp(5), 0, dp(9)); return lp; }
    private TextView text(String label, int size, int color) { TextView view = new TextView(this); view.setText(label); view.setTextSize(size); view.setTextColor(color); view.setTypeface(Typeface.create("OPPOSans", Typeface.NORMAL)); return view; }
    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
