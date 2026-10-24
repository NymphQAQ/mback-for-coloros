package dev.mback.oneplus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
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
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Small-window shortcuts based on the diagonal arrangement in the supplied
 * sketch and the Flyme 8/9 illustrations. No white sheet or full-screen fan.
 *
 * Performance: app icons are resolved and rasterized ONCE off the SystemUI UI
 * thread. Per-frame rendering uses only hardware-accelerated bitmap blits,
 * reusable Paints and float transforms, with no shadows/clips/allocations.
 */
final class CornerAppMenu {
    static final String ENABLE_KEY = "mback_corner_menu_enabled";
    static final String APPS_KEY = "mback_corner_menu_apps";
    static final String PREVIEW_KEY = "mback_corner_preview";
    static final String DEFAULT_APPS =
            "com.tencent.mm,com.coloros.calculator,com.coloros.calendar,com.coloros.filemanager,com.android.settings,com.netease.cloudmusic";
    private static final String TAG = "[MBack-Corner]";
    private static final long DISMISS_MS = 6000L;

    private final Context context;
    private final WindowManager windowManager;
    private final FreeformLauncher launcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::hide;
    private volatile List<AppItem> cachedApps = Collections.emptyList();
    private volatile String cachedCsv;
    private volatile String loadingCsv;

    private MenuView view;
    private WindowManager.LayoutParams layout;

    CornerAppMenu(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.launcher = new FreeformLauncher(context);
        prepare();
    }

    /**
     * Called at boot and after app-list changes, never from onDraw or
     * touch dispatch. PackageManager and icon drawing can take many ms.
     */
    void prepare() {
        String csv = Settings.Global.getString(context.getContentResolver(), APPS_KEY);
        if (csv == null || csv.isEmpty()) csv = DEFAULT_APPS;
        if (csv.equals(cachedCsv) || csv.equals(loadingCsv)) return;
        loadingCsv = csv;
        final String requested = csv;
        new Thread(() -> {
            List<AppItem> result = loadApps(requested);
            main.post(() -> {
                if (requested.equals(loadingCsv)) {
                    cachedApps = Collections.unmodifiableList(result);
                    cachedCsv = requested;
                    loadingCsv = null;
                    XposedBridge.log(TAG + " pre-rendered icons=" + result.size());
                }
            });
        }, "mback-icon-cache").start();
    }

    boolean isOpened() { return view != null; }

    boolean isGestureActive() {
        return view != null && layout != null
                && (layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
    }

    void releaseGesture() { allowTaps(); }

    void show(int side) { show(side, false); }
    void showForGesture(int side) { show(side, true); }

    private void show(int side, boolean activeGesture) {
        hide();
        if (context.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_PORTRAIT) return;
        try {
            // Zero icon loading or Bitmap drawing on this gesture path.
            MenuView next = new MenuView(context, side, cachedApps);
            android.graphics.Point display = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(display);
            // All four shortcuts live within the bottom 0.45 screen widths.
            // A compact overlay costs far less GPU composition than drawing
            // a transparent layer over the whole physical display.
            int overlayHeight = Math.min(display.y, Math.round(display.x * .58f));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                            | (activeGesture ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0),
                    PixelFormat.TRANSLUCENT);
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            lp.setTitle("MBack Flyme Shortcut");
            windowManager.addView(next, lp);
            view = next;
            layout = lp;
            next.enter();
            if (activeGesture) {
                main.postDelayed(() -> {
                    if (isGestureActive()) allowTaps();
                }, 3000);
            }
            resetTimeout();
            XposedBridge.log(TAG + " diagonal row shown side=" + side
                    + " apps=" + cachedApps.size());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " row show failed: " + error);
            hide();
        }
    }

    void highlightBySwipe(float rawX, float rawY) {
        MenuView showing = view;
        if (showing == null) return;
        if (showing.select(rawX, rawY)) resetTimeout();
    }

    boolean chooseBySwipe(float rawX, float rawY) {
        MenuView showing = view;
        if (showing == null) return false;
        AppItem chosen = showing.hit(rawX, rawY);
        if (chosen != null) {
            hide();
            launcher.open(chosen.packageName);
            XposedBridge.log(TAG + " shortcut launched: " + chosen.packageName);
            return true;
        }
        if (showing.hitMore(rawX, rawY)) {
            showing.nextPage();
            resetTimeout();
        }
        // If no icon was selected, keep the row for a subsequent tap.
        allowTaps();
        return false;
    }

    private void resetTimeout() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, DISMISS_MS);
    }

    private void allowTaps() {
        if (view == null || layout == null
                || (layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0) return;
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windowManager.updateViewLayout(view, layout); }
        catch (Throwable error) { XposedBridge.log(TAG + " allow taps failed: " + error); }
    }

    void hide() {
        main.removeCallbacks(timeout);
        MenuView old = view;
        view = null;
        layout = null;
        if (old == null) return;
        old.stop();
        try { windowManager.removeViewImmediate(old); }
        catch (Throwable error) { XposedBridge.log(TAG + " remove row failed: " + error); }
    }

    private List<AppItem> loadApps(String csv) {
        ArrayList<AppItem> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String value : csv.split(",")) {
            String packageName = CornerGesture.sanitize(value.trim());
            if (packageName == null || "none".equals(packageName)
                    || pm.getLaunchIntentForPackage(packageName) == null) continue;
            boolean duplicate = false;
            for (AppItem item : apps) if (packageName.equals(item.packageName)) duplicate = true;
            if (duplicate) continue;
            try {
                Drawable icon = pm.getApplicationIcon(packageName);
                // Pre-rasterize adaptive icons and circular clipping once.
                int size = Math.round(39f * context.getResources().getDisplayMetrics().density);
                Bitmap rendered = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(rendered);
                Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
                white.setColor(Color.WHITE);
                float center = size / 2f, radius = center - 2f;
                canvas.drawCircle(center, center, radius, white);
                Path mask = new Path();
                mask.addCircle(center, center, radius - 1.5f, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(mask);
                icon.setBounds(2, 2, size - 2, size - 2);
                icon.draw(canvas);
                canvas.restore();
                apps.add(new AppItem(packageName, rendered));
            } catch (Throwable error) {
                XposedBridge.log(TAG + " icon prerender skipped: " + packageName + " " + error);
            }
            if (apps.size() == 6) break;
        }
        return apps;
    }

    private static final class AppItem {
        final String packageName;
        final Bitmap bitmap;
        AppItem(String packageName, Bitmap bitmap) {
            this.packageName = packageName;
            this.bitmap = bitmap;
        }
    }

    private final class MenuView extends View {
        private final int side;
        private final List<AppItem> apps;
        private final Paint iconsPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final float iconHalf;
        private final float dotRadius;
        private ValueAnimator animator;
        private float reveal = 0f;
        private int selected = -1;
        private int page;
        private final int[] screenLocation = new int[2];

        MenuView(Context context, int side, List<AppItem> apps) {
            super(context);
            this.side = side;
            this.apps = apps;
            density = context.getResources().getDisplayMetrics().density;
            iconHalf = dp(19.5f);
            dotRadius = dp(16f);
            ringPaint.setColor(Color.WHITE);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(dp(1.25f));
            setClickable(true);
            // No LAYER_TYPE_SOFTWARE: Android's hardware renderer keeps
            // four small bitmaps on GPU and performs cheap transforms.
            setContentDescription("Flyme 斜列小窗快捷启动");
        }

        float dp(float n) { return n * density; }

        void enter() {
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(185);
            animator.setInterpolator(new DecelerateInterpolator(1.45f));
            animator.addUpdateListener(a -> {
                reveal = (Float) a.getAnimatedValue();
                postInvalidateOnAnimation();
            });
            animator.start();
        }

        void stop() {
            if (animator != null) animator.cancel();
        }

        private int visibleCount() {
            return FlymeArcGeometry.visibleCount(apps.size(), page);
        }

        void nextPage() {
            int pages = Math.max(1, (apps.size() + FlymeArcGeometry.VISIBLE_SHORTCUTS - 1)
                    / FlymeArcGeometry.VISIBLE_SHORTCUTS);
            page = (page + 1) % pages;
            selected = -1;
            reveal = 1f;
            postInvalidateOnAnimation();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int count = visibleCount();
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            for (int i = 0; i < count; i++) {
                float delay = i * .055f;
                float step = Math.max(0f, Math.min(1f, (reveal - delay) / (1f - delay)));
                // Appear in place with a small upward travel, rather than
                // scaling all icons through a huge diagonal path.
                float cx = FlymeArcGeometry.x(side, i, w);
                float cy = FlymeArcGeometry.y(i, w, h) + (1f - step) * dp(12f);
                AppItem item = apps.get(page * FlymeArcGeometry.VISIBLE_SHORTCUTS + i);
                float scale = (.86f + .14f * step) * (selected == i ? 1.13f : 1f);
                int alpha = Math.round(step * 255f);
                iconsPaint.setAlpha(alpha);
                canvas.save();
                canvas.translate(cx, cy);
                canvas.scale(scale, scale);
                canvas.drawBitmap(item.bitmap, -item.bitmap.getWidth() / 2f,
                        -item.bitmap.getHeight() / 2f, iconsPaint);
                if (selected == i) {
                    ringPaint.setAlpha(alpha);
                    canvas.drawCircle(0f, 0f, iconHalf + dp(2f), ringPaint);
                }
                canvas.restore();
            }
            drawMoreButton(canvas);
        }

        private float moreX() {
            return side == CornerGesture.LEFT ? dp(28) : getWidth() - dp(28);
        }

        private float moreY() {
            return getHeight() - dp(32);
        }

        private void drawMoreButton(Canvas canvas) {
            float x = moreX(), y = moreY();
            circlePaint.setColor(0xFF228EFF);
            circlePaint.setAlpha(Math.round(255f * reveal));
            canvas.drawCircle(x, y, dotRadius, circlePaint);
            circlePaint.setColor(Color.WHITE);
            circlePaint.setAlpha(Math.round(255f * reveal));
            for (int i = -1; i <= 1; i++) {
                canvas.drawCircle(x + dp(5.5f * i), y, dp(2.1f), circlePaint);
            }
        }

        private void locate() {
            getLocationOnScreen(screenLocation);
        }

        AppItem hit(float rawX, float rawY) {
            locate();
            int which = FlymeArcGeometry.hit(
                    rawX - screenLocation[0], rawY - screenLocation[1],
                    side, getWidth(), getHeight(), visibleCount(), dp(24f));
            return which == -1 ? null
                    : apps.get(page * FlymeArcGeometry.VISIBLE_SHORTCUTS + which);
        }

        boolean hitMore(float rawX, float rawY) {
            locate();
            float dx = rawX - screenLocation[0] - moreX();
            float dy = rawY - screenLocation[1] - moreY();
            return dx * dx + dy * dy <= dp(25f) * dp(25f);
        }

        boolean select(float rawX, float rawY) {
            locate();
            int next = FlymeArcGeometry.hit(
                    rawX - screenLocation[0], rawY - screenLocation[1],
                    side, getWidth(), getHeight(), visibleCount(), dp(24f));
            if (next == selected) return false;
            selected = next;
            postInvalidateOnAnimation();
            return true;
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE) {
                highlightBySwipe(event.getRawX(), event.getRawY());
                return true;
            }
            if (action == MotionEvent.ACTION_UP) {
                if (!chooseBySwipe(event.getRawX(), event.getRawY())
                        && !hitMore(event.getRawX(), event.getRawY())) hide();
                return true;
            }
            return true;
        }
    }
}
