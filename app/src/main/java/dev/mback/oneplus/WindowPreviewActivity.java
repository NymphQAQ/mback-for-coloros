package dev.mback.oneplus;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A real native task: the preview uses precisely the same policy as other apps. */
public final class WindowPreviewActivity extends Activity {
    private int count;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        count = state == null ? 0 : state.getInt("count");
        LinearLayout root = AppUi.screen(this);
        root.addView(AppUi.header(this, "系统小窗位置预览", this::finishAndRemoveTask, null));
        LinearLayout body = AppUi.content(this);
        AppUi.section(body, "预览说明");
        LinearLayout information = AppUi.card(body);
        TextView hint = AppUi.text(this, "这是实际的系统小窗。\n\n调整位置后，关闭并重新打开预览。可在窗外轻点，检查当前关闭方式。\n\n下方按钮可验证收起后页面是否保留。", 14, AppUi.MUTED, false);
        hint.setPadding(0, dp(16), 0, dp(16));
        hint.setLineSpacing(dp(3), 1);
        information.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        AppUi.section(body, "页面保留检查");
        LinearLayout controls = AppUi.card(body);
        controls.setPadding(dp(18), dp(12), dp(18), dp(4));
        Button counter = AppUi.button(this, "页面计数 " + count, true);
        counter.setOnClickListener(v -> counter.setText("页面计数 " + (++count)));
        LinearLayout.LayoutParams counterLayout = new LinearLayout.LayoutParams(-1, dp(48));
        counterLayout.bottomMargin = dp(12);
        controls.addView(counter, counterLayout);
        Button close = AppUi.button(this, "关闭预览", false);
        close.setOnClickListener(v -> finishAndRemoveTask());
        LinearLayout.LayoutParams closeLayout = new LinearLayout.LayoutParams(-1, dp(48));
        closeLayout.bottomMargin = dp(12);
        controls.addView(close, closeLayout);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("count", count);
        super.onSaveInstanceState(state);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
