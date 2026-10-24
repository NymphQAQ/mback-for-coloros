package dev.mback.oneplus;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private TextView status;
    private Switch enabledSwitch;
    private Switch notificationSwitch;
    private SeekBar scaleSlider;
    private SeekBar durationSlider;
    private SeekBar vibrationSlider;
    private boolean switching;
    private int vibrationPreviewLevel;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(248, 249, 252));
        layout.setPadding(dp(22), dp(28), dp(22), dp(30));
        scroll.addView(layout);

        layout.addView(text("MBack  ·  Xposed", 27));
        layout.addView(text("一加 15 / ColorOS\n轻点小白条返回，支持缩放动画和 10 档震动。", 15));
        status = text("正在读取配置…", 14);
        layout.addView(status);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用轻触返回");
        layout.addView(enabledSwitch);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (!switching) writeGlobal(MBackHook.SETTING, checked ? 1 : 0, "轻触返回");
        });

        layout.addView(section("小白条缩放"));
        scaleSlider = addSlider(layout, "缩放目标比例", "50%～95% 缩小，100% 保持，105%～150% 放大",
                FeedbackConfig.SCALE_KEY, FeedbackConfig.SCALE_MIN,
                FeedbackConfig.SCALE_MAX, FeedbackConfig.SCALE_STEP,
                FeedbackConfig.DEFAULT_SCALE, "%",
                value -> {});
        durationSlider = addSlider(layout, "缩放总时长", "缩小后恢复原尺寸的总动画时间",
                FeedbackConfig.DURATION_KEY, FeedbackConfig.DURATION_MIN,
                FeedbackConfig.DURATION_MAX, FeedbackConfig.DURATION_STEP,
                FeedbackConfig.DEFAULT_DURATION, " ms",
                value -> {});

        layout.addView(section("触觉反馈"));
        vibrationSlider = addSlider(layout, "震动强度（0 关闭 / 1–10 级）",
                "独立调节振幅，震动仅在成功轻点返回时触发",
                FeedbackConfig.VIBRATION_KEY, FeedbackConfig.VIBRATION_MIN,
                FeedbackConfig.VIBRATION_MAX, 1,
                FeedbackConfig.DEFAULT_VIBRATION, " 级",
                value -> vibrationPreviewLevel = value);
        Button preview = new Button(this);
        preview.setText("试听当前震动");
        layout.addView(preview);
        preview.setOnClickListener(v -> previewVibration());

        layout.addView(section("其他"));
        notificationSwitch = new Switch(this);
        notificationSwitch.setText("显示常驻状态通知");
        notificationSwitch.setChecked(getSharedPreferences("mback", MODE_PRIVATE)
                .getBoolean("show_notification", false));
        layout.addView(notificationSwitch);
        notificationSwitch.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("mback", MODE_PRIVATE).edit()
                    .putBoolean("show_notification", checked).apply();
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 42);
            } else {
                StatusNotification.sync(this);
                status.setText(checked ? "通知已请求显示" : "通知已关闭");
            }
        });

        Button test = new Button(this);
        test.setText("打开返回测试页面");
        layout.addView(test);
        test.setOnClickListener(v -> startActivity(new Intent(this, TestActivity.class)));

        layout.addView(text("动画仅作用于系统真实小白条，不增加触摸遮罩；上滑及长按不触发反馈。\n\n设置写入需要 root 授权；改动会立即同步到 SystemUI，无需重启。", 13));
        setContentView(scroll);
        refresh();
    }

    private SeekBar addSlider(LinearLayout parent, String title, String hint,
                             String key, int min, int max, int step, int fallback,
                             String suffix, IntConsumer onPreview) {
        TextView valueLabel = text(title, 17);
        parent.addView(valueLabel);
        SeekBar slider = new SeekBar(this);
        slider.setMax((max - min) / step);
        int initial = readGlobal(key, fallback);
        final int[] value = {Math.max(min, Math.min(max, initial))};
        slider.setProgress((value[0] - min) / step);
        valueLabel.setText(title + "  ·  " + value[0] + suffix);
        parent.addView(slider);
        TextView subtitle = text(hint, 12);
        parent.addView(subtitle);
        onPreview.accept(value[0]);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value[0] = min + progress * step;
                valueLabel.setText(title + "  ·  " +
                        (key.equals(FeedbackConfig.VIBRATION_KEY) && value[0] == 0
                                ? "关闭" : value[0] + suffix));
                onPreview.accept(value[0]);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                writeGlobal(key, value[0], title);
            }
        });
        return slider;
    }

    private int readGlobal(String key, int fallback) {
        return Settings.Global.getInt(getContentResolver(), key, fallback);
    }

    private void refresh() {
        boolean enabled = readGlobal(MBackHook.SETTING, 0) == 1;
        switching = true;
        enabledSwitch.setChecked(enabled);
        switching = false;
        status.setText(enabled ? "轻触返回已开启 · 调节设置后立即生效"
                : "当前已关闭轻触返回");
    }

    private void writeGlobal(String key, int value, String label) {
        status.setText("正在保存 " + label + "…");
        new Thread(() -> {
            boolean success = false;
            String message = "";
            try {
                Process process = new ProcessBuilder("su", "-c",
                        "settings put global " + key + " " + value)
                        .redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append(' ');
                }
                success = process.waitFor() == 0;
                message = output.toString();
            } catch (Exception error) {
                message = error.toString();
            }
            boolean ok = success;
            String error = message;
            runOnUiThread(() -> {
                refresh();
                status.setText(ok ? label + " 已保存 · 立即生效" :
                        label + " 保存失败：" + error);
            });
        }, "mback-preference").start();
    }

    private void previewVibration() {
        int level = FeedbackConfig.vibration(vibrationPreviewLevel);
        if (level <= 0) {
            status.setText("震动为 0 级：已关闭");
            return;
        }
        try {
            Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                status.setText("设备没有可用的震动器");
                return;
            }
            vibrator.vibrate(VibrationEffect.createOneShot(
                    FeedbackConfig.vibrationDuration(level),
                    vibrator.hasAmplitudeControl()
                            ? FeedbackConfig.vibrationAmplitude(level)
                            : VibrationEffect.DEFAULT_AMPLITUDE));
            status.setText("已试听 " + level + " 级震动");
        } catch (Exception error) {
            status.setText("震动试听失败：" + error.getMessage());
        }
    }

    private TextView section(String label) {
        TextView view = text(label, 20);
        view.setPadding(0, dp(23), 0, dp(8));
        view.setTextColor(Color.rgb(32, 70, 125));
        return view;
    }

    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(36, 45, 59));
        view.setPadding(0, dp(9), 0, dp(10));
        return view;
    }

    private int dp(int value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                notificationSwitch.setChecked(false);
                status.setText("通知权限被拒绝，可在系统设置中开启");
            } else {
                StatusNotification.sync(this);
                status.setText("通知权限已授予");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (enabledSwitch != null) refresh();
        if (scaleSlider != null) {
            int value = FeedbackConfig.scale(readGlobal(
                    FeedbackConfig.SCALE_KEY, FeedbackConfig.DEFAULT_SCALE));
            scaleSlider.setProgress((value - FeedbackConfig.SCALE_MIN)
                    / FeedbackConfig.SCALE_STEP);
        }
        if (durationSlider != null) {
            int value = FeedbackConfig.duration(readGlobal(
                    FeedbackConfig.DURATION_KEY, FeedbackConfig.DEFAULT_DURATION));
            durationSlider.setProgress((value - FeedbackConfig.DURATION_MIN)
                    / FeedbackConfig.DURATION_STEP);
        }
        if (vibrationSlider != null) {
            vibrationSlider.setProgress(FeedbackConfig.vibration(readGlobal(
                    FeedbackConfig.VIBRATION_KEY, FeedbackConfig.DEFAULT_VIBRATION)));
        }
        StatusNotification.sync(this);
    }
}
