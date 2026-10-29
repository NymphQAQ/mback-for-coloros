package dev.mback.oneplus;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    static final int INK = AppUi.INK, MUTED = AppUi.MUTED, BACKGROUND = AppUi.BACKGROUND;
    static final int GREEN = AppUi.GREEN, PALE = AppUi.PALE, BORDER = AppUi.BORDER;
    private static final int MOTION = 1, HAPTICS = 2, INTERACTION = 3, LAYOUT = 4,
            ICONS = 5, OUTER = 6, INNER = 7, TRANSIT = 8, WINDOWS = 9, HANDLES = 10;
    private static final String[] DETAIL_TITLES = {"", "动作反馈", "触觉反馈", "呼出与交互",
            "圆弧布局", "图标与触感", "外圈设置", "内圈设置", "乘车码启动方式", "系统小窗设置", "小窗底部白条"};
    private final ArrayList<Runnable> readers = new ArrayList<>();
    private final ScrollView[] pages = new ScrollView[2];
    private final Button[] tabs = new Button[2];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView title, status, message;
    private FrameLayout content;
    private LinearLayout root, navigation;
    private Button back;
    private ScrollView detailPage;
    private ArrayList<Runnable> detailReaders;
    private android.window.OnBackInvokedCallback detailBack;
    private final Runnable hideMessage = () -> message.setVisibility(View.GONE);
    private Dialog rangeDialog;
    private CornerPreview cornerPreview;
    private int currentTab, detailId, vibrationPreviewLevel;
    private boolean switching, tracking;
    private int pendingWrites;
    private final java.util.concurrent.ExecutorService preferenceWrites =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppUi.window(this);
        root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setForceDarkAllowed(false);
        root.addView(header());
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        buildMBack(content);
        buildShortcuts(content);

        message = text("", 13, INK, false);
        message.setPadding(dp(20), dp(12), dp(20), dp(12));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(View.GONE);
        root.addView(message);
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        navigation = new LinearLayout(this);
        navigation.setBackgroundColor(Color.WHITE);
        navigation.setPadding(dp(20), dp(8), dp(20), 0);
        String[] labels = {"MBack", "快捷小窗"};
        for (int i = 0; i < labels.length; i++) {
            final int page = i;
            Button tab = button(labels[i], false);
            tab.setTextSize(12);
            tab.setCompoundDrawablePadding(dp(3));
            tab.setPadding(0, dp(7), 0, dp(7));
            tab.setContentDescription(labels[i]);
            tab.setOnClickListener(v -> selectTab(page));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1);
            lp.setMargins(i == 0 ? 0 : dp(5), 0, i == 0 ? dp(5) : 0, 0);
            navigation.addView(tab, lp);
            tabs[i] = tab;
        }
        root.addView(navigation);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), detailPage == null ? 0 : insets.getSystemWindowInsetBottom());
            navigation.setPadding(dp(20), dp(8), dp(20), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) detailBack = this::closeSettings;
        selectTab(state == null ? 0 : Math.max(0, Math.min(1, state.getInt("tab"))));
        if (state != null) for (int i = 0; i < pages.length; i++) {
            final ScrollView page = pages[i];
            final int scrollY = state.getInt("scroll_" + i);
            page.post(() -> page.scrollTo(0, scrollY));
        }
        refresh();
        if (state != null) {
            int page = state.getInt("detail");
            if (page >= MOTION && page <= HANDLES) {
                openSettings(page);
                int scrollY = state.getInt("detail_scroll");
                ScrollView restored = detailPage;
                restored.post(() -> restored.scrollTo(0, scrollY));
            }
        }
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(4));
        back = button("‹", false);
        back.setTextSize(30);
        back.setPadding(0, 0, 0, 0);
        back.setBackground(ripple(BACKGROUND, 16));
        back.setContentDescription("返回主页");
        back.setVisibility(View.GONE);
        back.setOnClickListener(v -> closeSettings());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        title = text("MBack", 26, INK, true);
        title.setLetterSpacing(-.025f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setAccessibilityHeading(true);
        row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        row.setMinimumHeight(dp(60));
        status = text("", 12, GREEN, true);
        status.setPadding(dp(12), dp(7), dp(12), dp(7));
        status.setBackground(shape(PALE, 18));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        row.addView(status);
        return row;
    }

    private LinearLayout page(FrameLayout parent, int index) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(8), dp(20), dp(16));
        scroll.addView(body);
        parent.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        pages[index] = scroll;
        return body;
    }

    private void buildMBack(FrameLayout parent) {
        LinearLayout body = page(parent, 0);
        LinearLayout enable = card(body);
        toggle(enable, "轻触返回", "轻点屏幕底部小白条即可返回", MBackHook.SETTING, MBackHook.DEFAULT_ENABLED);

        section(body, "手感设置");
        LinearLayout feedback = card(body);
        entry(feedback, "动作反馈", () -> "缩放 " + FeedbackConfig.scale(readGlobal(FeedbackConfig.SCALE_KEY,
                FeedbackConfig.DEFAULT_SCALE)) + "% · " + FeedbackConfig.duration(readGlobal(
                FeedbackConfig.DURATION_KEY, FeedbackConfig.DEFAULT_DURATION)) + " ms", () -> openSettings(MOTION));
        divider(feedback);
        entry(feedback, "触觉反馈", () -> {
            int level = FeedbackConfig.vibration(readGlobal(FeedbackConfig.VIBRATION_KEY, FeedbackConfig.DEFAULT_VIBRATION));
            return level == 0 ? "震动已关闭 · 支持试听" : level + " 级震动 · 支持试听";
        }, () -> openSettings(HAPTICS));

        section(body, "日常使用");
        LinearLayout extras = card(body);
        Switch notificationSwitch = toggleRow(extras, "状态通知", "在通知栏保留一个设置入口");
        readers.add(() -> notificationSwitch.setChecked(getSharedPreferences("mback", MODE_PRIVATE)
                .getBoolean("show_notification", false)));
        notificationSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (switching) return;
            getSharedPreferences("mback", MODE_PRIVATE).edit().putBoolean("show_notification", checked).apply();
            if (checked && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
            } else {
                StatusNotification.sync(this);
                showMessage(checked ? "状态通知已开启" : "状态通知已关闭", false);
            }
        });
        divider(extras);
        entry(extras, "打开返回测试页", () -> "检查轻触返回与底部触控",
                () -> startActivity(new Intent(this, TestActivity.class)));
        divider(extras);
        entry(extras, "备份与恢复", () -> "设置 · 快捷项 · 图标库与图标包",
                () -> {
                    if (pendingWrites > 0) { showMessage("正在保存，请稍后打开备份", false); return; }
                    startActivity(new Intent(this, BackupActivity.class));
                });
        footer(body, "更改自动保存，调整后立即生效。");
    }

    private void motionControls(LinearLayout body) {
        LinearLayout motion = card(body);
        slider(motion, "缩放比例", "100% 保持原尺寸，小于 100% 为缩小", FeedbackConfig.SCALE_KEY,
                FeedbackConfig.SCALE_MIN, FeedbackConfig.SCALE_MAX, FeedbackConfig.SCALE_STEP,
                FeedbackConfig.DEFAULT_SCALE, "%");
        divider(motion);
        slider(motion, "动画时长", "从轻触到恢复的完整时间", FeedbackConfig.DURATION_KEY,
                FeedbackConfig.DURATION_MIN, FeedbackConfig.DURATION_MAX, FeedbackConfig.DURATION_STEP,
                FeedbackConfig.DEFAULT_DURATION, " ms");
    }

    private void hapticControls(LinearLayout body) {
        LinearLayout haptics = card(body);
        slider(haptics, "震动强度", "0 为关闭，1–10 级逐级增强", FeedbackConfig.VIBRATION_KEY,
                0, 10, 1, FeedbackConfig.DEFAULT_VIBRATION, " 级");
        Button preview = button("试听当前震动", false);
        preview.setOnClickListener(v -> previewVibration());
        haptics.addView(preview, actionParams());
    }

    private void buildShortcuts(FrameLayout parent) {
        LinearLayout body = page(parent, 1);
        LinearLayout enable = card(body);
        toggle(enable, "底角快捷小窗", "竖屏下，从左下角或右下角斜向内滑", CornerAppMenu.ENABLE_KEY, CornerAppMenu.DEFAULT_ENABLED);

        section(body, "快捷应用");
        LinearLayout apps = card(body);
        entry(apps, "管理快捷应用", () -> "中圈 · 已添加 " + ShortcutStore.read(this).size() + "/6 个快捷项",
                () -> FreeformLauncher.openMore(this));
        divider(apps);
        entry(apps, "外圈设置", () -> ringSummary(RingConfig.OUTER), () -> openSettings(OUTER));
        divider(apps);
        entry(apps, "内圈设置", () -> ringSummary(RingConfig.INNER), () -> openSettings(INNER));
        divider(apps);
        entry(apps, "乘车码启动方式", () -> PresetShortcut.directTransit(this) ? "直接小窗启动" : "负一屏启动（默认）",
                () -> openSettings(TRANSIT));

        section(body, "手势与外观");
        LinearLayout settings = card(body);
        entry(settings, "呼出与交互", () -> "呼出范围 · 压暗 " + CornerPreferences.dim(this) + "% · "
                + "动画 " + CornerPreferences.animationDuration(this) + " ms",
                () -> openSettings(INTERACTION));
        divider(settings);
        entry(settings, "圆弧布局", () -> "半径 " + ArcConfig.radius(readGlobal(ArcConfig.RADIUS_KEY,
                ArcConfig.DEFAULT_RADIUS)) + "% · 边距与三圈预览", () -> openSettings(LAYOUT));
        divider(settings);
        entry(settings, "图标与触感", () -> (readGlobal(ArcConfig.ORIGINAL_ICON_KEY, 0) == 1 ? "原始形状" : "完整圆形")
                + " · 中圈 " + ArcConfig.icon(readGlobal(ArcConfig.ICON_SIZE_KEY, ArcConfig.DEFAULT_ICON))
                + " dp · 滑选震动", () -> openSettings(ICONS));
        divider(settings);
        entry(settings, "系统小窗设置", () -> "位置 · 窗外点击 · 底部白条手势", () -> openSettings(WINDOWS));
        footer(body, "选中图标后松手打开 · 更改自动保存");
    }

    private void windowControls(LinearLayout body) {
        LinearLayout gestures = card(body);
        entry(gestures, "小窗底部白条", () -> "双击动作 · 长按移动 · 就绪震动", () -> openSettings(HANDLES));
        section(body, "打开位置");
        LinearLayout position = card(body);
        TextView state = text("", 12, GREEN, true);
        state.setPadding(0, dp(12), 0, dp(12));
        position.addView(state);
        readers.add(() -> {
            int boot = readGlobal(Settings.Global.BOOT_COUNT, -1);
            boolean ready = (WindowPlacement.HOOK_VERSION + ":" + boot).equals(
                    Settings.Global.getString(getContentResolver(), WindowPlacement.READY));
            state.setText(ready ? "系统小窗控制已就绪" : "请在模块作用域勾选“系统框架”，然后重启手机");
        });
        toggle(position, "统一默认位置", "应用于本机屏幕上的 ColorOS 原生应用小窗，关闭则由系统决定位置", WindowPlacement.ENABLED);
        divider(position);
        slider(position, "横向位置", "0% 靠左，50% 居中，100% 靠右；保留系统安全边距", WindowPlacement.X,
                0, 100, 1, WindowPlacement.DEFAULT_X, "%");
        divider(position);
        slider(position, "纵向位置", "0% 靠上，50% 居中，100% 靠下；避开状态栏与导航栏", WindowPlacement.Y,
                0, 100, 1, WindowPlacement.DEFAULT_Y, "%");
        Button preview = button("打开位置预览小窗", true);
        preview.setOnClickListener(v -> {
            if (pendingWrites > 0) { showMessage("正在保存，请稍后打开预览", false); return; }
            new FreeformLauncher(this).open(ShortcutTarget.parse(
                    "dev.mback.oneplus/dev.mback.oneplus.WindowPreviewActivity"));
        });
        position.addView(preview, actionParams());
        footer(body, "使用真实系统小窗预览。位置在下次打开或恢复小窗时应用，已展开的小窗仍可手动拖动；窗口大小由系统与应用决定。");

        section(body, "点击小窗外");
        LinearLayout choices = card(body);
        RadioGroup modes = new RadioGroup(this);
        String[] labels = {"关闭此功能（默认）", "收起并保留页面", "结束小窗任务", "转至后台"};
        String[] hints = {"保留系统原有行为。", "收起到系统悬浮入口，再打开可继续原来的页面。",
                "移除当前小窗任务；不强制停止整个应用。未保存的内容可能丢失。",
                "将小窗任务转入后台，保留任务，不显示悬浮入口。"};
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton choice = new RadioButton(this);
            ids[i] = View.generateViewId();
            choice.setId(ids[i]);
            choice.setText(labels[i] + "\n" + hints[i]);
            choice.setTextSize(14);
            choice.setTextColor(INK);
            choice.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{GREEN, MUTED}));
            choice.setPadding(0, dp(16), 0, dp(16));
            choice.setMinimumHeight(dp(88));
            modes.addView(choice, new RadioGroup.LayoutParams(-1, -2));
        }
        choices.addView(modes);
        readers.add(() -> modes.check(ids[WindowPlacement.outside(readGlobal(WindowPlacement.OUTSIDE, 0))]));
        modes.setOnCheckedChangeListener((group, id) -> {
            if (switching) return;
            for (int i = 0; i < ids.length; i++) if (id == ids[i])
                writeGlobal(WindowPlacement.OUTSIDE, i, "窗外点击方式");
        });
        divider(choices);
        toggle(choices, "拦截窗外点击", "选择收起、结束任务或转至后台后生效，阻止窗外触摸操作底下的应用，默认关闭", WindowPlacement.BLOCK_OUTSIDE);
        footer(body, "轻点所有小窗以外的区域时作用于当前展开的小窗。开启拦截后，窗外滑动、长按和多指也不会操作底下的应用，且不执行所选的窗外点击操作。键盘、通知栏、锁屏与屏幕边缘手势不受影响。悬浮入口、画中画和普通悬浮控件不受影响。");
    }

    private void handleControls(LinearLayout body) {
        footer(body, "作用于应用小窗自身底部的白条。首次安装更新需在“系统框架”作用域重启手机；之后更改自动保存。");
        section(body, "双击白条");
        LinearLayout actions = card(body);
        RadioGroup modes = new RadioGroup(this);
        String[] labels = {"保留系统行为（默认）", "转至后台", "右上角预览"};
        String[] hints = {"不增加双击动作。", "保留当前任务与页面，转到后台。", "将当前小窗缩为系统预览窗。"};
        int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton choice = new RadioButton(this);
            ids[i] = View.generateViewId();
            choice.setId(ids[i]);
            choice.setText(labels[i] + "\n" + hints[i]);
            choice.setTextSize(14);
            choice.setTextColor(INK);
            choice.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{GREEN, MUTED}));
            choice.setPadding(0, dp(14), 0, dp(14));
            choice.setMinimumHeight(dp(80));
            modes.addView(choice, new RadioGroup.LayoutParams(-1, -2));
        }
        actions.addView(modes);
        readers.add(() -> modes.check(ids[Math.max(0, Math.min(2,
                readGlobal(HandleGestureConfig.DOUBLE_TAP, 0)))]));
        modes.setOnCheckedChangeListener((group, id) -> {
            if (switching) return;
            for (int i = 0; i < ids.length; i++) if (id == ids[i])
                writeGlobal(HandleGestureConfig.DOUBLE_TAP, i, "双击白条动作");
        });
        section(body, "滑动与移动");
        LinearLayout gestures = card(body);
        toggle(gestures, "长按白条移动", "按住约 0.5 秒，震动后可拖动移动小窗；直接拖动保持系统行为",
                HandleGestureConfig.HOLD_MOVE);
        divider(gestures);
        slider(gestures, "长按就绪震动", "进入可拖动状态时震动一次；0 为关闭，1–10 级逐级增强",
                HandleGestureConfig.HOLD_VIBRATION, 0, 10, 1, HandleGestureConfig.DEFAULT_VIBRATION, " 级");
    }

    private String ringSummary(RingConfig ring) {
        if (readGlobal(ring.enableKey, 0) != 1) return "未开启 · 可独立设置应用、数量与大小";
        String source = readGlobal(ring.recentKey, ring.defaultRecent) == 1 ? "最近应用"
                : "手动已选 " + ShortcutStore.read(this, ring.ring).size() + " 个";
        int count = Math.max(1, Math.min(RingConfig.MAX_APPS, readGlobal(ring.countKey, ring.defaultCount)));
        return source + " · 最多显示 " + count + " 个";
    }

    private void transitControls(LinearLayout body) {
        LinearLayout choices = card(body);
        RadioGroup modes = new RadioGroup(this);
        String[] labels = {"负一屏启动（默认）", "直接小窗启动"};
        String[] hints = {"需要负一屏正常工作。使用负一屏提供的入口，打开后再转为系统小窗，可能先短暂全屏。",
                "无需负一屏，直接以系统小窗打开。微信使用内部启动协议，更新后可能需要适配；当前已验证微信 8.0.79。"};
        int[] ids = new int[2];
        for (int i = 0; i < labels.length; i++) {
            RadioButton choice = new RadioButton(this);
            ids[i] = View.generateViewId();
            choice.setId(ids[i]);
            choice.setText(labels[i] + "\n" + hints[i]);
            choice.setTextSize(14);
            choice.setTextColor(INK);
            choice.setButtonTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{GREEN, MUTED}));
            choice.setPadding(0, dp(20), 0, dp(20));
            choice.setMinimumHeight(dp(96));
            modes.addView(choice, new RadioGroup.LayoutParams(-1, -2));
        }
        choices.addView(modes);
        readers.add(() -> modes.check(ids[PresetShortcut.directTransit(this) ? 1 : 0]));
        modes.setOnCheckedChangeListener((group, id) -> {
            if (!switching) writeGlobal(PresetShortcut.TRANSIT_MODE_KEY, id == ids[1] ? 1 : 0, "乘车码启动方式");
        });
        footer(body, "同时应用于微信、支付宝乘车码；下次打开生效，无需重启。扫一扫和付款码不受影响。直接启动失败时，可切回负一屏启动。");
    }

    private void interactionControls(LinearLayout body) {
        LinearLayout interaction = card(body);
        toggle(interaction, "松手后保持展开", "松手选中图标才打开；未选中时保持展开",
                CornerPreferences.KEEP_OPEN_KEY);
        divider(interaction);
        slider(interaction, "屏幕压暗", "呼出时降低背景亮度，0 为关闭", CornerPreferences.DIM_KEY,
                0, 70, 5, CornerPreferences.DEFAULT_DIM, "%");
        divider(interaction);
        animationSlider(interaction);
        Button range = button("呼出范围与预览", true);
        range.setOnClickListener(v -> openRangePreview());
        interaction.addView(range, actionParams());
    }

    private void animationSlider(LinearLayout parent) {
        slider(parent, "呼出动画时长", "数值越小越快；0 为立即显示。范围预览可播放当前速度。",
                CornerPreferences.ANIMATION_KEY, 0, CornerPreferences.MAX_ANIMATION,
                CornerPreferences.ANIMATION_STEP, CornerPreferences.DEFAULT_ANIMATION, " ms");
    }

    private void layoutControls(LinearLayout body) {
        LinearLayout layout = card(body);
        slider(layout, "圆弧半径", "以屏幕宽度为基准，越小弧度越明显", ArcConfig.RADIUS_KEY,
                ArcConfig.RADIUS_MIN, ArcConfig.RADIUS_MAX, ArcConfig.RADIUS_STEP, ArcConfig.DEFAULT_RADIUS, "%");
        divider(layout);
        slider(layout, "侧面边距", "左右对称，让图标离屏幕侧边更远", ArcConfig.SIDE_MARGIN_KEY,
                ArcConfig.MARGIN_MIN, ArcConfig.MARGIN_MAX, ArcConfig.MARGIN_STEP, ArcConfig.DEFAULT_SIDE_MARGIN, " dp");
        divider(layout);
        slider(layout, "底部边距", "增大后整组图标上移", ArcConfig.BOTTOM_MARGIN_KEY,
                ArcConfig.MARGIN_MIN, ArcConfig.MARGIN_MAX, ArcConfig.MARGIN_STEP, ArcConfig.DEFAULT_BOTTOM_MARGIN, " dp");
        previewButton(body);
    }

    private void iconControls(LinearLayout body) {
        LinearLayout assets = card(body);
        entry(assets, "图标包", () -> IconPackStore.activeName(this) + " · 导入与切换",
                () -> startActivity(new Intent(this, IconLibraryActivity.class).putExtra(IconLibraryActivity.EXTRA_TAB, 1)));
        divider(assets);
        entry(assets, "图标库", () -> "导入过的图标保存在这里，可再次选用",
                () -> startActivity(new Intent(this, IconLibraryActivity.class)));
        section(body, "显示与触感");
        LinearLayout icons = card(body);
        slider(icons, "图标大小", "快捷项和更多入口一起调整", ArcConfig.ICON_SIZE_KEY,
                ArcConfig.ICON_MIN, ArcConfig.ICON_MAX, ArcConfig.ICON_STEP, ArcConfig.DEFAULT_ICON, " dp");
        divider(icons);
        toggle(icons, "原始图标形状", "关闭后所有应用与活动图标统一为完整图案圆形", ArcConfig.ORIGINAL_ICON_KEY);
        divider(icons);
        slider(icons, "滑选震动", "进入新的图标时轻震一次，0 为关闭", ArcConfig.HOVER_VIBRATION_KEY,
                0, 10, 1, ArcConfig.DEFAULT_HOVER_VIBRATION, " 级");
    }

    private void ringControls(LinearLayout body, RingConfig ring) {
        LinearLayout card = card(body);
        toggle(card, "角落手势" + ring.label, ring.ring == 1 ? "在现有圆弧外侧增加一圈" : "在现有圆弧靠近屏幕底角的位置增加一圈", ring.enableKey);
        divider(card);
        toggle(card, ring.label + "显示最近使用应用", "开启时显示最近应用，关闭时使用手动选择的快捷项", ring.recentKey, ring.defaultRecent);
        TextView count = text("", 12, MUTED, false);
        count.setPadding(0, dp(12), 0, dp(6));
        card.addView(count);
        readers.add(() -> count.setText(ring.label + "手动已选 " + ShortcutStore.read(this, ring.ring).size() + " 个 · 各圈分别保存"));
        Button manage = button(ring.label + "应用 / 功能", true);
        manage.setOnClickListener(v -> FreeformLauncher.openMore(this, ring.ring));
        card.addView(manage, actionParams());
        divider(card);
        slider(card, ring.label + "应用数量", "最多显示 8 个，不足时按实际数量显示", ring.countKey,
                1, RingConfig.MAX_APPS, 1, ring.defaultCount, " 个");
        divider(card);
        slider(card, ring.label + "图标大小", "空间不足时会缩小图标，避免各圈重叠", ring.iconKey,
                ArcConfig.ICON_MIN, ArcConfig.ICON_MAX, ArcConfig.ICON_STEP, RingConfig.DEFAULT_ICON, " dp");
        previewButton(body);
    }

    private void previewButton(LinearLayout parent) {
        Button preview = button("预览三圈布局", false);
        preview.setOnClickListener(v -> { openRangePreview(); if (cornerPreview != null) cornerPreview.replay(); });
        LinearLayout.LayoutParams lp = actionParams();
        lp.topMargin = dp(16);
        parent.addView(preview, lp);
    }

    private void openSettings(int page) {
        if (detailPage != null) closeSettings();
        int firstReader = readers.size();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = column();
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        switch (page) {
            case MOTION: motionControls(body); break;
            case HAPTICS: hapticControls(body); break;
            case INTERACTION: interactionControls(body); break;
            case LAYOUT: layoutControls(body); break;
            case ICONS: iconControls(body); break;
            case OUTER: ringControls(body, RingConfig.OUTER); break;
            case INNER: ringControls(body, RingConfig.INNER); break;
            case TRANSIT: transitControls(body); break;
            case WINDOWS: windowControls(body); break;
            case HANDLES: handleControls(body); break;
        }
        footer(body, "更改自动保存，返回无需确认。");
        scroll.addView(body);
        detailReaders = new ArrayList<>(readers.subList(firstReader, readers.size()));
        detailPage = scroll;
        detailId = page;
        content.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        for (ScrollView home : pages) home.setVisibility(View.GONE);
        navigation.setVisibility(View.GONE);
        root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), navigation.getPaddingBottom());
        back.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, detailBack);
        title.setText(DETAIL_TITLES[page]);
        title.setTextSize(22);
        refresh();
        title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    private void closeSettings() {
        if (detailPage == null) return;
        content.removeView(detailPage);
        readers.removeAll(detailReaders);
        detailPage = null;
        detailReaders = null;
        detailId = 0;
        tracking = false;
        back.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(detailBack);
        navigation.setVisibility(View.VISIBLE);
        root.setPadding(root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight(), 0);
        selectTab(currentTab);
        refresh();
    }

    private void entry(LinearLayout parent, String label, java.util.function.Supplier<String> summary, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(64));
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(ripple(Color.WHITE, 12));
        row.setFocusable(true);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout copy = column();
        TextView name = text(label, 15, INK, true);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(name);
        TextView value = text("", 12, MUTED, false);
        value.setPadding(0, dp(5), dp(12), 0);
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        copy.addView(value);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 26, MUTED, false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        row.setOnClickListener(v -> action.run());
        readers.add(() -> {
            String valueText = summary.get();
            value.setText(valueText);
            row.setContentDescription(label + "，" + valueText);
        });
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openRangePreview() {
        if (rangeDialog != null) return;
        Dialog dialog = new Dialog(this, getApplicationInfo().theme);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout body = column();
        body.setBackgroundColor(BACKGROUND);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            body.setPadding(dp(20) + insets.getSystemWindowInsetLeft(), dp(8) + insets.getSystemWindowInsetTop(),
                    dp(20) + insets.getSystemWindowInsetRight(), dp(8) + insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        Button close = button("关闭", false);
        close.setOnClickListener(v -> dialog.dismiss());
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(72), dp(48)));
        LinearLayout heading = AppUi.header(this, "呼出范围与预览", null, close);
        heading.setPadding(0, 0, 0, dp(4));
        body.addView(heading);
        CornerPreview preview = new CornerPreview(this);
        cornerPreview = preview;
        body.addView(preview, new LinearLayout.LayoutParams(-1, dp(210)));
        TextView hint = text("蓝色：底部  ·  橙色：侧边  ·  紫色：三角区域\n在彩色范围向内斜滑，可试用压暗与飞入动画。", 12, MUTED, false);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(4), 0, dp(10));
        body.addView(hint);
        LinearLayout actions = new LinearLayout(this);
        String[] labels = {"左下角", "右下角", "播放动画"};
        for (int i = 0; i < 3; i++) {
            final int choice = i;
            Button action = button(labels[i], false);
            action.setOnClickListener(v -> { if (choice == 2) preview.replay(); else preview.side(choice + 1); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMargins(dp(3), 0, dp(3), dp(10));
            actions.addView(action, lp);
        }
        body.addView(actions);
        ScrollView scroll = new ScrollView(this);
        LinearLayout controls = column();
        scroll.addView(controls);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        int firstReader = readers.size();
        animationSlider(controls);
        android.graphics.Point screen = new android.graphics.Point();
        getWindowManager().getDefaultDisplay().getRealSize(screen);
        String[] titles = {"底部长度", "底部高度", "侧边长度", "侧边宽度", "三角区域宽度", "三角区域高度"};
        String[] hints = {"从底角沿底边向内延伸", "从底边向上延伸", "从底角沿侧边向上延伸",
                "从侧边向屏幕内延伸", "三角形沿底边的直角边", "三角形沿侧边的直角边"};
        float density = getResources().getDisplayMetrics().density;
        LinearLayout area = card(controls);
        for (int i = 0; i < 6; i++) {
            if (i > 0) divider(area);
            int maximum = (i == 0 || i == 4) ? Math.min(CornerRegion.MAX[i],
                    (int) (screen.x * .45f / density / 2) * 2) : CornerRegion.MAX[i];
            slider(area, titles[i], hints[i] + "；0 为关闭该区域", CornerRegion.KEYS[i],
                    0, maximum, 2, CornerPreferences.fallback(i), " dp");
        }
        slider(controls, "屏幕压暗", "预览中也会同步显示，0 为关闭", CornerPreferences.DIM_KEY,
                0, 70, 5, CornerPreferences.DEFAULT_DIM, "%");
        footer(controls, "左右对称生效。彩色范围取并集，三角区域用于避开圆角难触位置。调整自动保存。");
        ArrayList<Runnable> dialogReaders = new ArrayList<>(readers.subList(firstReader, readers.size()));
        dialog.setOnDismissListener(d -> {
            readers.removeAll(dialogReaders);
            cornerPreview = null;
            rangeDialog = null;
            tracking = false;
        });
        dialog.setContentView(body);
        rangeDialog = dialog;
        dialog.show();
        android.view.Window window = dialog.getWindow();
        AppUi.window(window);
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setLayout(-1, -1);
        refresh();
    }

    private void selectTab(int tab) {
        currentTab = tab;
        title.setText(tab == 0 ? "MBack" : "快捷小窗");
        title.setTextSize(26);
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == tab;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabs[i].setSelected(selected);
            tabs[i].setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
            tabs[i].setTextColor(selected ? INK : MUTED);
            tabs[i].setBackground(ripple(selected ? PALE : Color.WHITE, 20));
            tabs[i].setCompoundDrawablesWithIntrinsicBounds(null,
                    new HomeArtwork.NavIcon(this, i == 1, selected ? INK : MUTED), null, null);
        }
        updateStatus();
    }

    private void updateStatus() {
        if (detailPage != null) {
            status.setText("自动保存");
            status.setTextColor(GREEN);
            status.setBackground(shape(PALE, 18));
            return;
        }
        boolean enabled = readGlobal(currentTab == 0 ? MBackHook.SETTING : CornerAppMenu.ENABLE_KEY,
                currentTab == 0 ? MBackHook.DEFAULT_ENABLED : CornerAppMenu.DEFAULT_ENABLED) == 1;
        status.setText(enabled ? "已开启" : "未开启");
        status.setTextColor(enabled ? GREEN : MUTED);
        status.setBackground(shape(enabled ? PALE : BORDER, 18));
    }

    private Switch toggleRow(LinearLayout parent, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(80));
        LinearLayout labels = column();
        TextView name = text(title, 15, INK, true);
        name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.addView(name);
        TextView hint = text(subtitle, 12, MUTED, false);
        hint.setPadding(0, dp(5), dp(10), 0);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.addView(hint);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this);
        toggle.setMinimumWidth(dp(52));
        toggle.setMinimumHeight(dp(48));
        toggle.setThumbTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{GREEN, 0xFF86938C}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{0xFF9DC3AA, 0xFFDDE3DD}));
        toggle.setContentDescription(title + "，" + subtitle);
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(row);
        return toggle;
    }

    private void toggle(LinearLayout parent, String title, String subtitle, String key) {
        toggle(parent, title, subtitle, key, 0);
    }

    private void toggle(LinearLayout parent, String title, String subtitle, String key, int fallback) {
        Switch toggle = toggleRow(parent, title, subtitle);
        readers.add(() -> toggle.setChecked(readGlobal(key, fallback) == 1));
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (!switching) writeGlobal(key, checked ? 1 : 0, title);
        });
    }

    private void slider(LinearLayout parent, String title, String hint, String key,
                        int min, int max, int step, int fallback, String suffix) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, 0);
        row.addView(text(title, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text("", 13, GREEN, true);
        value.setPadding(dp(10), dp(5), dp(10), dp(5));
        value.setBackground(shape(BACKGROUND, 10));
        row.addView(value);
        parent.addView(row);
        SeekBar slider = new SeekBar(this);
        slider.setMax((max - min) / step);
        slider.setProgressTintList(ColorStateList.valueOf(GREEN));
        slider.setThumbTintList(ColorStateList.valueOf(GREEN));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));
        slider.setPadding(dp(6), 0, dp(6), 0);
        parent.addView(slider, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView explanation = text(hint, 11, MUTED, false);
        explanation.setPadding(0, 0, 0, dp(16));
        parent.addView(explanation);
        Runnable render = () -> {
            int number = min + slider.getProgress() * step;
            boolean coordinate = WindowPlacement.X.equals(key) || WindowPlacement.Y.equals(key);
            value.setText(number == 0 && !coordinate ? "关闭" : number + suffix);
            slider.setContentDescription(title + "，" + value.getText());
            if (FeedbackConfig.VIBRATION_KEY.equals(key)) vibrationPreviewLevel = number;
            if (cornerPreview != null) cornerPreview.setValue(key, number);
        };
        readers.add(() -> {
            int saved = Math.max(min, Math.min(max, readGlobal(key, fallback)));
            slider.setProgress(Math.round((saved - min) / (float) step));
            render.run();
        });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar view, int progress, boolean fromUser) { render.run(); }
            @Override public void onStartTrackingTouch(SeekBar view) { tracking = true; }
            @Override public void onStopTrackingTouch(SeekBar view) {
                tracking = false;
                writeGlobal(key, min + view.getProgress() * step, title);
            }
        });
    }

    private int readGlobal(String key, int fallback) {
        return Settings.Global.getInt(getContentResolver(), key, fallback);
    }

    private void refresh() {
        switching = true;
        try {
            if (!tracking) for (Runnable reader : readers) reader.run();
        } finally {
            switching = false;
        }
        updateStatus();
    }

    private void writeGlobal(String key, int value, String label) {
        showMessage("正在保存…", false);
        pendingWrites++;
        preferenceWrites.execute(() -> {
            boolean success = false;
            String message = "";
            try {
                Process process = new ProcessBuilder("su", "-c", "settings put global " + key + " " + value)
                        .redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append(' ');
                }
                success = process.waitFor() == 0;
                message = output.toString();
            } catch (Exception error) {
                message = error.toString();
            }
            boolean ok = success;
            if (!ok) Log.w("MBack", "Saving " + key + " failed: " + message);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                pendingWrites--;
                if (pendingWrites == 0) refresh();
                showMessage(ok ? label + "已保存，立即生效" : "保存失败，请检查 Root 授权", !ok);
            });
        });
    }

    private void showMessage(String content, boolean error) {
        handler.removeCallbacks(hideMessage);
        message.setText(content);
        message.setTextColor(error ? 0xFFA32D27 : INK);
        message.setBackgroundColor(error ? 0xFFFBE8E4 : PALE);
        message.setVisibility(View.VISIBLE);
        if (!error) handler.postDelayed(hideMessage, 2400);
    }

    private void previewVibration() {
        int level = FeedbackConfig.vibration(vibrationPreviewLevel);
        if (level <= 0) { showMessage("震动已关闭", false); return; }
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (!TouchFeedback.play(vibrator, level)) {
                showMessage("设备没有可用的震动器", true);
                return;
            }
            showMessage("已试听 " + level + " 级震动", false);
        } catch (Exception error) {
            showMessage("暂时无法试听震动，请稍后重试", true);
            Log.w("MBack", "Vibration preview failed", error);
        }
    }

    private LinearLayout column() {
        return AppUi.column(this);
    }

    private LinearLayout card(LinearLayout parent) {
        return AppUi.card(parent);
    }

    private void section(LinearLayout parent, String title) {
        AppUi.section(parent, title);
    }

    private void footer(LinearLayout parent, String content) {
        AppUi.footer(parent, content);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private TextView text(String content, int size, int color, boolean bold) {
        return AppUi.text(this, content, size, color, bold);
    }

    private Button button(String title, boolean filled) {
        return AppUi.button(this, title, filled);
    }

    private LinearLayout.LayoutParams actionParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(4), 0, dp(12));
        return lp;
    }

    private GradientDrawable shape(int color, int radius) {
        return AppUi.shape(this, color, radius);
    }

    private RippleDrawable ripple(int color, int radius) {
        return AppUi.ripple(this, color, radius);
    }

    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 42) {
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                getSharedPreferences("mback", MODE_PRIVATE).edit().putBoolean("show_notification", false).apply();
                refresh();
            }
            StatusNotification.sync(this);
            showMessage(granted ? "状态通知已开启" : "通知权限未开启，可稍后在系统设置中授权", !granted);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", currentTab);
        for (int i = 0; i < pages.length; i++) state.putInt("scroll_" + i, pages[i].getScrollY());
        state.putInt("detail", detailId);
        if (detailPage != null) state.putInt("detail_scroll", detailPage.getScrollY());
    }

    @Override public void onBackPressed() {
        if (detailPage != null) closeSettings();
        else super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
        StatusNotification.sync(this);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(hideMessage);
        if (rangeDialog != null) rangeDialog.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && detailPage != null)
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(detailBack);
        preferenceWrites.shutdown();
        super.onDestroy();
    }
}
