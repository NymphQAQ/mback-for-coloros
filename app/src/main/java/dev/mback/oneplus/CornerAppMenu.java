package dev.mback.oneplus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Flyme fan, not a bottom sheet. Icons are arranged along a rising diagonal
 * arc from either bottom corner and are selected with the same swipe.
 */
final class CornerAppMenu {
    static final String ENABLE_KEY = "mback_corner_menu_enabled";
    static final String APPS_KEY = "mback_corner_menu_apps";
    static final String PREVIEW_KEY = "mback_corner_preview";
    static final String DEFAULT_APPS =
            "com.tencent.mm,com.coloros.calculator,com.coloros.calendar,com.coloros.filemanager,com.android.settings,com.netease.cloudmusic";
    private static final String TAG = "[MBack-Corner]";

    private final Context context;
    private final WindowManager windowManager;
    private final FreeformLauncher launcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private FanView fan;
    private WindowManager.LayoutParams layout;
    private boolean opened;
    private Runnable dismiss;
    private final Runnable timeout = () -> { hide(); };

    CornerAppMenu(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.launcher = new FreeformLauncher(context);
    }

    boolean isOpened() { return opened; }

    boolean isGestureActive() {
        return opened && layout != null
                && (layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
    }

    void releaseGesture() { allowTaps(); }

    void show(int side) {
        show(side, false);
    }

    void showForGesture(int side) {
        show(side, true);
    }

    private void show(int side, boolean activeGesture) {
        hide();
        if (context.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_PORTRAIT) return;
        try {
            ArrayList<AppItem> apps = loadApps();
            FanView view = new FanView(context, side, apps);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | (activeGesture ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = 0;
            lp.y = 0;
            lp.setTitle("MBack Flyme Fan");
            windowManager.addView(view, lp);
            layout = lp;
            fan = view;
            opened = true;
            view.enter();
            if (activeGesture) main.postDelayed(() -> { if (isGestureActive()) allowTaps(); }, 1150);
            resetTimeout();
            XposedBridge.log(TAG + " Flyme fan shown side=" + side + " icons=" + apps.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " fan show error: " + t);
            hide();
        }
    }

    private void resetTimeout() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 6500);
    }

    void highlightBySwipe(float rawX, float rawY) {
        if (!opened || fan == null) return;
        fan.select(rawX, rawY);
        resetTimeout();
    }

    boolean chooseBySwipe(float rawX, float rawY) {
        if (!opened || fan == null) return false;
        AppItem item = fan.hit(rawX, rawY);
        if (item != null) {
            XposedBridge.log(TAG + " swipe-selected " + item.packageName);
            hide();
            launcher.open(item.packageName);
            return true;
        }
        // The input monitor owned the entire originating swipe. Only AFTER
        // the user releases do we let the fan receive new taps, preventing
        // the overlay from stealing a half-finished native gesture stream.
        allowTaps();
        return false;
    }

    private void allowTaps() {
        if (fan == null || layout == null
                || (layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) return;
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windowManager.updateViewLayout(fan, layout); }
        catch (Throwable t) { XposedBridge.log(TAG + " enable tap error: " + t); }
    }

    void hide() {
        main.removeCallbacks(timeout);
        FanView old = fan;
        fan = null;
        layout = null;
        opened = false;
        if (old != null) {
            old.stop();
            try { windowManager.removeViewImmediate(old); }
            catch (Throwable t) { XposedBridge.log(TAG + " remove fan error: " + t); }
        }
    }

    private ArrayList<AppItem> loadApps() {
        ArrayList<AppItem> out = new ArrayList<>();
        String csv = Settings.Global.getString(context.getContentResolver(), APPS_KEY);
        if (csv == null || csv.isEmpty()) csv = DEFAULT_APPS;
        PackageManager pm = context.getPackageManager();
        for (String part : csv.split(",")) {
            String packageName = CornerGesture.sanitize(part.trim());
            if (packageName == null || "none".equals(packageName)) continue;
            if (pm.getLaunchIntentForPackage(packageName) == null) continue;
            boolean duplicate = false;
            for (AppItem item : out) if (item.packageName.equals(packageName)) duplicate = true;
            if (duplicate) continue;
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                String name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
                out.add(new AppItem(packageName, name, icon.mutate()));
            } catch (Exception ignored) {}
            if (out.size() == 6) break;
        }
        return out;
    }

    private static final class AppItem {
        final String packageName, label;
        final Drawable icon;
        AppItem(String name, String label, Drawable drawable) {
            packageName = name;
            this.label = label;
            icon = drawable;
        }
    }

    private final class FanView extends View {
        private final int side;
        private final ArrayList<AppItem> apps;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final float density;
        private float reveal;
        private int selected = -1;
        private ValueAnimator entrance;
        private final float[] xs = new float[6];
        private final float[] ys = new float[6];

        FanView(Context context, int side, ArrayList<AppItem> apps) {
            super(context);
            this.side = side;
            this.apps = apps;
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null); // clean circular shadows
            setClickable(true);
            setContentDescription("Flyme 扇形小窗快捷菜单");
        }

        void enter() {
            entrance = ValueAnimator.ofFloat(0f, 1f);
            entrance.setDuration(210);
            entrance.setInterpolator(new DecelerateInterpolator(2f));
            entrance.addUpdateListener(a -> {
                reveal = (Float) a.getAnimatedValue();
                invalidate();
            });
            entrance.start();
        }

        void stop() {
            if (entrance != null) entrance.cancel();
        }

        private float d(float dp) { return dp * density; }

        private float originX() {
            return side == CornerGesture.LEFT ? d(34f) : getWidth() - d(34f);
        }

        private float originY() { return getHeight() - d(36f); }

        private void layoutIcons() {
            float cx = originX(), cy = originY();
            for (int i = 0; i < apps.size(); i++) {
                // Six independent rays gradually fan toward the screen center.
                // The almost-diagonal arc follows the Flyme small-window demo:
                // colored round icons coming out of a blue three-dot launcher.
                float radius = d(77f + 49f * i);
                double angle = Math.toRadians(45f + i * 1.9f);
                float horizontal = (float) (radius * Math.cos(angle));
                float vertical = (float) (radius * Math.sin(angle));
                xs[i] = cx + (side == CornerGesture.LEFT ? horizontal : -horizontal);
                ys[i] = cy - vertical;
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;
            layoutIcons();
            // Barely visible backdrop, never a rectangular sheet or title bar.
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(Color.argb(Math.round(38 * reveal), 0, 0, 0));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

            // Warm corner glow subtly links the fan to the originating thumb.
            paint.setShader(new RadialGradient(originX(), originY(), d(340),
                    new int[]{0x2BFFFFFF, 0x0CFFFFFF, 0x00FFFFFF},
                    new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(originX(), originY(), d(340), paint);
            paint.setShader(null);

            for (int i = apps.size() - 1; i >= 0; i--) drawIcon(canvas, apps.get(i), i);
            drawMoreButton(canvas);
            paint.clearShadowLayer();
        }

        private void drawIcon(Canvas canvas, AppItem item, int index) {
            float delay = index * .055f;
            float progress = Math.max(0f, Math.min(1f, (reveal - delay) / (1f - delay)));
            float cx = originX() + (xs[index] - originX()) * progress;
            float cy = originY() + (ys[index] - originY()) * progress;
            float iconRadius = d(selected == index ? 29 : 24);
            iconRadius *= .60f + .40f * progress;

            canvas.save();
            canvas.scale(progress, progress, cx, cy);
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(0x44FFFFFF);
            paint.setShadowLayer(d(5), 0f, d(3), 0x55000000);
            canvas.drawCircle(cx, cy, iconRadius + d(2), paint);
            paint.clearShadowLayer();

            // Flyme uses circular app icons with a clean white rim.
            paint.setColor(0xF5FFFFFF);
            canvas.drawCircle(cx, cy, iconRadius + d(selected == index ? 3.4f : 1.4f), paint);
            canvas.save();
            clip.reset();
            clip.addCircle(cx, cy, iconRadius, Path.Direction.CW);
            canvas.clipPath(clip);
            Drawable drawable = item.icon;
            drawable.setBounds(Math.round(cx - iconRadius), Math.round(cy - iconRadius),
                    Math.round(cx + iconRadius), Math.round(cy + iconRadius));
            drawable.draw(canvas);
            canvas.restore();
            if (selected == index) {
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(d(2));
                canvas.drawCircle(cx, cy, iconRadius + d(4), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restore();
        }

        private void drawMoreButton(Canvas canvas) {
            float cx = originX(), cy = originY();
            paint.reset();
            paint.setAntiAlias(true);
            paint.setColor(0xFF158DFF);
            paint.setShadowLayer(d(6), 0f, d(3), 0x48000000);
            canvas.drawCircle(cx, cy, d(23), paint);
            paint.clearShadowLayer();
            paint.setColor(Color.WHITE);
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(cx + d(i * 7.3f), cy, d(3.1f), paint);
            }
        }

        AppItem hit(float rawX, float rawY) {
            int[] pos = new int[2];
            getLocationOnScreen(pos);
            float x = rawX - pos[0], y = rawY - pos[1];
            layoutIcons();
            for (int i = 0; i < apps.size(); i++) {
                float dx = x - xs[i], dy = y - ys[i];
                if (dx * dx + dy * dy < d(36) * d(36)) return apps.get(i);
            }
            return null;
        }

        void select(float rawX, float rawY) {
            AppItem hit = hit(rawX, rawY);
            int next = -1;
            for (int i = 0; i < apps.size(); i++) if (apps.get(i) == hit) next = i;
            if (selected != next) {
                selected = next;
                invalidate();
            }
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                if (chooseBySwipe(e.getRawX(), e.getRawY())) return true;
                // The triple-dot origin also closes the fan. A later gesture
                // opens it again, and the MBack settings control app selection.
                hide();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                highlightBySwipe(e.getRawX(), e.getRawY());
                return true;
            }
            return true;
        }
    }
}
