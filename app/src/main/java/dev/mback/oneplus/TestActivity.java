package dev.mback.oneplus;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

public final class TestActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.rgb(239, 246, 251));
        TextView title = new TextView(this);
        title.setText("MBack 测试页\n\n点小白条应返回上一页，不能误点到底下的按钮。\n上滑应回桌面。\n\n可切换横屏再测试。");
        title.setTextSize(20);
        title.setTextColor(Color.BLACK);
        title.setPadding(40, 150, 40, 40);
        layout.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        Button rotate = new Button(this);
        rotate.setText("切换横屏 / 竖屏");
        FrameLayout.LayoutParams rotateParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        layout.addView(rotate, rotateParams);
        rotate.setOnClickListener(v -> setRequestedOrientation(
                getResources().getConfiguration().orientation ==
                android.content.res.Configuration.ORIENTATION_PORTRAIT
                    ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        Button underneath = new Button(this);
        underneath.setText("底部点击穿透测试 · 次数 0");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(56), Gravity.BOTTOM);
        layout.addView(underneath, params);
        final int[] count = {0};
        underneath.setOnClickListener(v -> underneath.setText(
                "底部点击穿透测试 · 次数 " + (++count[0])));
        setContentView(layout);
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
}
