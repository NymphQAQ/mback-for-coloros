package dev.mback.oneplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The home page's visual language, shared by settings, floating pages and dialogs. */
final class AppUi {
    static final int INK = 0xFF183F32, MUTED = 0xFF5D6C62, BACKGROUND = 0xFFF4F6F3;
    static final int GREEN = 0xFF28684E, PALE = 0xFFE1F0E7, BORDER = 0xFFE6EBE5;
    static final int ERROR = 0xFFA32D27, ERROR_PALE = 0xFFFBE8E4;

    private AppUi() {}

    static int dp(Context context, float value) {
        return Math.round(context.getResources().getDisplayMetrics().density * value);
    }

    static void window(Activity activity) {
        window(activity.getWindow());
    }

    static void window(Window window) {
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarContrastEnforced(false);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        window.getDecorView().setForceDarkAllowed(false);
    }

    static LinearLayout screen(Activity activity) {
        window(activity);
        LinearLayout root = column(activity);
        root.setBackgroundColor(BACKGROUND);
        root.setForceDarkAllowed(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        return root;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout content(Context context) {
        LinearLayout body = column(context);
        body.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 24));
        return body;
    }

    static LinearLayout header(Context context, String title, Runnable back, View trailing) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 4));
        row.setMinimumHeight(dp(context, 60));
        if (back != null) {
            Button arrow = button(context, "‹", false);
            arrow.setTextSize(30);
            arrow.setPadding(0, 0, 0, 0);
            arrow.setBackground(ripple(context, BACKGROUND, 16));
            arrow.setContentDescription("返回");
            arrow.setOnClickListener(v -> back.run());
            row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        }
        TextView heading = text(context, title, 22, INK, true);
        heading.setLetterSpacing(-.025f);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setAccessibilityHeading(true);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) row.addView(trailing);
        return row;
    }

    static LinearLayout card(LinearLayout parent) {
        Context context = parent.getContext();
        LinearLayout card = column(context);
        card.setPadding(dp(context, 18), dp(context, 4), dp(context, 18), dp(context, 4));
        card.setBackground(cardBackground(context));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    static GradientDrawable cardBackground(Context context) {
        GradientDrawable background = shape(context, Color.WHITE, 24);
        background.setStroke(dp(context, 1), BORDER);
        return background;
    }

    static void section(LinearLayout parent, String title) {
        Context context = parent.getContext();
        TextView label = text(context, title, 13, MUTED, true);
        label.setPadding(dp(context, 3), dp(context, 18), 0, dp(context, 9));
        label.setAccessibilityHeading(true);
        parent.addView(label);
    }

    static void footer(LinearLayout parent, String value) {
        Context context = parent.getContext();
        TextView hint = text(context, value, 12, MUTED, false);
        hint.setPadding(dp(context, 3), dp(context, 18), dp(context, 3), dp(context, 8));
        parent.addView(hint);
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        text.setIncludeFontPadding(false);
        return text;
    }

    static Button button(Context context, String title, boolean filled) {
        Button button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{MUTED, filled ? Color.WHITE : INK}));
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{-android.R.attr.state_enabled}, shape(context, BORDER, 16));
        background.addState(new int[]{}, ripple(context, filled ? INK : PALE, 16));
        button.setBackgroundTintList(null);
        button.setBackground(background);
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(context, 48));
        button.setMinHeight(dp(context, 48));
        button.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        return button;
    }

    static EditText search(Context context, String hint) {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setMinimumHeight(dp(context, 48));
        input.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        GradientDrawable background = shape(context, Color.WHITE, 16);
        background.setStroke(dp(context, 1), BORDER);
        input.setBackgroundTintList(null);
        input.setBackground(background);
        return input;
    }

    static GradientDrawable shape(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    static RippleDrawable ripple(Context context, int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2228684E), shape(context, color, radius),
                shape(context, Color.WHITE, radius));
    }

    static AlertDialog show(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            Context context = dialog.getContext();
            dialog.getWindow().setBackgroundDrawable(shape(context, BACKGROUND, 24));
            dialog.getWindow().setDimAmount(.28f);
            dialog.getWindow().getDecorView().setForceDarkAllowed(false);
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) { message.setTextColor(MUTED); message.setTextSize(14); message.setLineSpacing(dp(context, 3), 1); }
            android.widget.ListView choices = dialog.getListView();
            if (choices != null) {
                choices.setDivider(new android.graphics.drawable.ColorDrawable(BORDER));
                choices.setDividerHeight(dp(context, 1));
                choices.setSelector(ripple(context, BACKGROUND, 12));
                choices.post(() -> {
                    for (int i = 0; i < choices.getChildCount(); i++) {
                        View item = choices.getChildAt(i);
                        if (item instanceof TextView) {
                            TextView label = (TextView) item;
                            label.setTextColor(INK);
                            label.setTextSize(15);
                            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                            label.setIncludeFontPadding(false);
                        }
                    }
                });
            }
            for (int which : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
                Button button = dialog.getButton(which);
                if (button == null || button.getVisibility() == View.GONE) continue;
                boolean primary = which == AlertDialog.BUTTON_POSITIVE;
                button.setAllCaps(false);
                button.setTextSize(14);
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                button.setTextColor(primary ? Color.WHITE : INK);
                button.setBackgroundTintList(null);
                button.setBackground(ripple(context, primary ? INK : PALE, 16));
                button.setStateListAnimator(null);
                button.setMinHeight(dp(context, 48));
                button.setMinimumHeight(dp(context, 48));
                button.setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10));
            }
        });
        dialog.show();
        return dialog;
    }
}
