package dev.mback.oneplus;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.BitmapShader;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/**
 * Flyme-style gesture-only diagonal row.
 * Seven positions maximum, with "More" fixed in the LOWEST position.
 * This view NEVER takes touches and is always removed at UP/CANCEL.
 * Application icons are pre-rasterized off the SystemUI UI thread.
 */
final class CornerAppMenu {
    static final String ENABLE_KEY = "mback_corner_menu_enabled";
    static final String APPS_KEY = "mback_corner_menu_apps";
    static final String DEFAULT_APPS =
            "com.tencent.mm,com.coloros.calculator,com.coloros.calendar,com.coloros.filemanager,com.android.settings,com.netease.cloudmusic";
    private static final String TAG = "[MBack-Corner]";

    private final Context context;
    private final WindowManager windowManager;
    private final FreeformLauncher launcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<AppItem> cachedApps = Collections.emptyList();
    private volatile String cachedCsv;
    private volatile String loadingCsv;
    private RowView view;

    CornerAppMenu(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.launcher = new FreeformLauncher(context);
        prepare();
    }

    void prepare() {
        String csv = Settings.Global.getString(context.getContentResolver(), APPS_KEY);
        if (csv == null || csv.isEmpty()) csv = DEFAULT_APPS;
        if (csv.equals(cachedCsv) || csv.equals(loadingCsv)) return;
        final String requested = csv;
        loadingCsv = requested;
        new Thread(() -> {
            List<AppItem> prepared = loadApps(requested);
            main.post(() -> {
                if (requested.equals(loadingCsv)) {
                    cachedApps = Collections.unmodifiableList(prepared);
                    cachedCsv = requested;
                    loadingCsv = null;
                    XposedBridge.log(TAG + " cached circular icons=" + prepared.size());
                }
            });
        }, "mback-icon-cache").start();
    }

    boolean isOpened() { return view != null; }
    boolean isGestureActive() { return view != null; }

    void showForGesture(int side) {
        hide();
        if (context.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_PORTRAIT) return;
        try {
            RowView next = new RowView(context, side, cachedApps);
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            // 0.745w for top icon plus headroom. Avoid a fullscreen overlay.
            int overlayHeight = Math.min(size.y, Math.round(size.x * .87f));
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayHeight,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.LEFT | Gravity.BOTTOM;
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
            lp.setTitle("MBack Flyme Shortcut");
            windowManager.addView(next, lp);
            view = next;
            next.enter();
            XposedBridge.log(TAG + " gesture-only diagonal shown side=" + side
                    + " apps=" + cachedApps.size());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " show failed: " + error);
            hide();
        }
    }

    void highlightBySwipe(float rawX, float rawY) {
        RowView showing = view;
        if (showing != null) showing.select(rawX, rawY);
    }

    /** Called exactly once for the matching finger UP, even over empty space. */
    void finishGesture(float rawX, float rawY) {
        RowView showing = view;
        if (showing == null) return;
        int position = showing.hit(rawX, rawY);
        AppItem selected = position > 0 && position <= showing.apps.size()
                ? showing.apps.get(position - 1) : null;
        boolean more = position == FlymeArcGeometry.MORE_POSITION;
        // Release the window BEFORE starting any activity to prevent overlays
        // from sitting on top of native ColorOS window transitions.
        hide();
        if (selected != null) {
            launcher.open(selected.packageName);
            XposedBridge.log(TAG + " released on shortcut " + selected.packageName);
        } else if (more) {
            try {
                Intent intent = new Intent();
                intent.setClassName("dev.mback.oneplus",
                        "dev.mback.oneplus.MoreAppsActivity");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                XposedBridge.log(TAG + " released on More -> app management");
            } catch (Throwable error) {
                XposedBridge.log(TAG + " cannot open app management: " + error);
            }
        } else {
            XposedBridge.log(TAG + " released on empty space; row dismissed");
        }
    }

    void hide() {
        RowView old = view;
        view = null;
        if (old != null) {
            old.stop();
            try { windowManager.removeViewImmediate(old); }
            catch (Throwable error) { XposedBridge.log(TAG + " remove failed: " + error); }
        }
    }

    private List<AppItem> loadApps(String csv) {
        ArrayList<AppItem> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        for (String item : csv.split(",")) {
            String pkg = CornerGesture.sanitize(item.trim());
            if (pkg == null || "none".equals(pkg)
                    || pm.getLaunchIntentForPackage(pkg) == null) continue;
            boolean alreadyAdded = false;
            for (AppItem app : result) if (pkg.equals(app.packageName)) alreadyAdded = true;
            if (alreadyAdded) continue;
            try {
                Bitmap bitmap = createCircularIcon(pm.getApplicationIcon(pkg));
                result.add(new AppItem(pkg, bitmap));
            } catch (Throwable error) {
                XposedBridge.log(TAG + " cannot rasterize " + pkg + ": " + error);
            }
            if (result.size() == FlymeArcGeometry.MAX_APPS) break;
        }
        return result;
    }

    /** Direct circular clipping, NO white disk, inset, or inner white ring. */
    private Bitmap createCircularIcon(Drawable icon) {
        int size = Math.round(44f * context.getResources().getDisplayMetrics().density);
        Bitmap source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas sourceCanvas = new Canvas(source);
        // Draw the actual artwork larger than its safe-area padding. Crop
        // square app backgrounds rather than shrinking them inside a circle.
        int overscan = Math.round(size * .18f);
        icon.setBounds(-overscan, -overscan, size + overscan, size + overscan);
        icon.draw(sourceCanvas);

        Bitmap circle = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        fill.setShader(new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        float center = size * .5f;
        new Canvas(circle).drawCircle(center, center, center, fill);
        source.recycle();
        return circle;
    }

    private static final class AppItem {
        final String packageName;
        final Bitmap bitmap;
        AppItem(String packageName, Bitmap bitmap) {
            this.packageName = packageName;
            this.bitmap = bitmap;
        }
    }

    private final class RowView extends View {
        final int side;
        final List<AppItem> apps;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint morePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int[] windowPosition = new int[2];
        private ValueAnimator animation;
        private float reveal;
        private int hovered = -1;

        RowView(Context ctx, int side, List<AppItem> apps) {
            super(ctx);
            this.side = side;
            this.apps = apps;
            density = ctx.getResources().getDisplayMetrics().density;
            setContentDescription("Flyme 七位置斜列小窗菜单");
        }

        private float dp(float value) { return value * density; }

        void enter() {
            animation = ValueAnimator.ofFloat(0f, 1f);
            animation.setDuration(190);
            animation.setInterpolator(new DecelerateInterpolator(1.7f));
            animation.addUpdateListener(a -> {
                reveal = (Float) a.getAnimatedValue();
                postInvalidateOnAnimation();
            });
            animation.start();
        }

        void stop() {
            if (animation != null) animation.cancel();
        }

        int hit(float rawX, float rawY) {
            getLocationOnScreen(windowPosition);
            return FlymeArcGeometry.hit(rawX - windowPosition[0],
                    rawY - windowPosition[1], side, getWidth(), getHeight(),
                    FlymeArcGeometry.visiblePositions(apps.size()), dp(30f));
        }

        void select(float rawX, float rawY) {
            int current = hit(rawX, rawY);
            if (current != hovered) {
                hovered = current;
                postInvalidateOnAnimation();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;

            final int count = FlymeArcGeometry.visiblePositions(apps.size());
            // Paint ascending app row, with More as the lowest stop.
            for (int pos = 0; pos < count; pos++) {
                float delay = pos * .037f;
                float step = Math.max(0f, Math.min(1f, (reveal - delay) / (1f - delay)));
                if (step <= 0) continue;
                float x = FlymeArcGeometry.x(side, pos, width);
                float y = FlymeArcGeometry.y(pos, width, height)
                        + (1f - step) * dp(9f);
                float scale = (.84f + .16f * step) * (hovered == pos ? 1.13f : 1f);
                int alpha = Math.round(255f * step);

                canvas.save();
                canvas.translate(x, y);
                canvas.scale(scale, scale);
                if (pos == 0) {
                    drawMore(canvas, alpha);
                } else {
                    Bitmap icon = apps.get(pos - 1).bitmap;
                    iconPaint.setAlpha(alpha);
                    canvas.drawBitmap(icon, -icon.getWidth() * .5f,
                            -icon.getHeight() * .5f, iconPaint);
                }
                canvas.restore();
            }
        }

        private void drawMore(Canvas canvas, int alpha) {
            morePaint.setColor(0xFF2189FE);
            morePaint.setAlpha(alpha);
            canvas.drawCircle(0f, 0f, dp(22f), morePaint);
            morePaint.setColor(Color.WHITE);
            morePaint.setAlpha(alpha);
            for (int i = -1; i <= 1; i++)
                canvas.drawCircle(dp(6.4f * i), 0f, dp(2.35f), morePaint);
        }
    }
}
