package dev.mback.oneplus;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class TestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(AppUi.BACKGROUND);
        LinearLayout root = AppUi.screen(this);
        root.addView(AppUi.header(this, "MBack 测试页", this::finish, null));
        LinearLayout body = AppUi.content(this);
        AppUi.section(body, "操作说明");
        LinearLayout information = AppUi.card(body);
        TextView instructions = AppUi.text(this,
                "点小白条应返回上一页，不能误点到底下的按钮。\n\n上滑应回桌面。可切换横屏再测试。", 15, AppUi.MUTED, false);
        instructions.setPadding(0, dp(16), 0, dp(16));
        instructions.setLineSpacing(dp(3), 1);
        information.addView(instructions);
        AppUi.section(body, "屏幕方向");
        LinearLayout controls = AppUi.card(body);
        controls.setPadding(dp(18), dp(12), dp(18), dp(12));
        Button rotate = AppUi.button(this, "切换横屏 / 竖屏", false);
        controls.addView(rotate, new LinearLayout.LayoutParams(-1, dp(48)));
        rotate.setOnClickListener(v -> setRequestedOrientation(
                getResources().getConfiguration().orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        AppUi.footer(body, "底部按钮用于检查导航栏和小窗外点击是否穿透到当前页面。");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout.LayoutParams main = new FrameLayout.LayoutParams(-1, -1);
        main.bottomMargin = dp(56);
        layout.addView(root, main);
        Button underneath = AppUi.button(this, "底部点击穿透测试 · 次数 0", true);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM);
        layout.addView(underneath, params);
        final int[] count = {0};
        final int[] downs = {0};
        underneath.setContentDescription("底部触摸按下次数 0");
        underneath.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
                underneath.setContentDescription("底部触摸按下次数 " + (++downs[0]));
            return false;
        });
        underneath.setOnClickListener(v -> underneath.setText(
                "底部点击穿透测试 · 次数 " + (++count[0])));
        setContentView(layout);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
}
