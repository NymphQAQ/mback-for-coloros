package dev.mback.oneplus;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {
    private TextView status;
    private Switch enabledSwitch;
    private Switch notificationSwitch;
    private volatile boolean switching;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(248, 249, 252));
        int padding = dp(22);
        layout.setPadding(padding, dp(36), padding, padding);
        layout.addView(text("MBack  ·  Xposed", 27));
        layout.addView(text("一加 15 / ColorOS\n轻点导航手势条返回，系统原生手势保持不变。", 15));
        status = text("正在读取配置…", 14);
        layout.addView(status);

        enabledSwitch = new Switch(this);
        enabledSwitch.setText("启用轻触返回（需要 Vector / LSPosed）");
        layout.addView(enabledSwitch);
        enabledSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (switching) return;
            writeEnabled(checked);
        });

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

        layout.addView(text("工作原理：Xposed 注入 SystemUI 导航栏，扩展小白条的可触区域并消费单击。\n\n开启模块后必须为其设置 SystemUI 作用域，并重新启动 SystemUI 或重启手机。\n\n关闭通知不影响轻触返回。发生手势异常时可在 Vector 中禁用模块。", 14));
        setContentView(layout);
        refresh();
    }

    private void refresh() {
        boolean enabled = Settings.Global.getInt(getContentResolver(),
                MBackHook.SETTING, 0) == 1;
        switching = true;
        enabledSwitch.setChecked(enabled);
        switching = false;
        status.setText(enabled ? "轻触返回配置已开启；检查 Xposed 作用域是否已生效。" : "当前关闭轻触返回。");
    }

    private void writeEnabled(boolean enabled) {
        enabledSwitch.setEnabled(false);
        status.setText("正在写入全局配置，等待 root 授权…");
        new Thread(() -> {
            boolean success = false;
            String detail = "";
            try {
                Process process = new ProcessBuilder("su", "-c",
                        "settings put global " + MBackHook.SETTING + " " + (enabled ? "1" : "0"))
                        .redirectErrorStream(true).start();
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append(' ');
                }
                success = process.waitFor() == 0;
                detail = output.toString();
            } catch (Exception e) {
                detail = e.toString();
            }
            boolean ok = success;
            String message = detail;
            runOnUiThread(() -> {
                enabledSwitch.setEnabled(true);
                refresh();
                if (!ok) status.setText("root 写入失败：" + message);
            });
        }, "mback-settings").start();
    }

    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(Color.rgb(36, 45, 59));
        view.setPadding(0, dp(10), 0, dp(16));
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
                status.setText("通知权限被拒绝，可以在系统设置中开启");
            } else {
                StatusNotification.sync(this);
                status.setText("通知权限已授予");
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (enabledSwitch != null && enabledSwitch.isEnabled()) refresh();
        StatusNotification.sync(this);
    }
}
