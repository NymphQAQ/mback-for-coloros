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

    private int radiusPercent() {
        return ArcConfig.radius(Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.RADIUS_KEY, ArcConfig.DEFAULT_RADIUS));
    }

    private int iconSizeDp() {
        return ArcConfig.icon(Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.ICON_SIZE_KEY, ArcConfig.DEFAULT_ICON));
    }

    private int spacingDp() {
        return ArcConfig.spacing(Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.SPACING_KEY, ArcConfig.DEFAULT_SPACING));
    }

    private boolean useOriginalIcons() {
        return Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.ORIGINAL_ICON_KEY, 0) == 1;
    }

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
                    XposedBridge.log(TAG + " cached circle and original icons=" + prepared.size());
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
            int radius = radiusPercent();
            int icon = iconSizeDp();
            int spacing = spacingDp();
            RowView next = new RowView(context, side, cachedApps,
                    radius, icon, spacing, useOriginalIcons());
            android.graphics.Point size = new android.graphics.Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            // Make the overlay tall enough for the largest allowed circular arc.
            // Still keep it limited to the lower part of the physical display.
            int overlayHeight = Math.min(size.y, Math.round(size.x * .97f));
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
            XposedBridge.log(TAG + " circular arc shown side=" + side
                    + " radius=" + radius + "% icon=" + icon + "dp gap=" + spacing
                    + "dp original=" + useOriginalIcons() + " apps=" + cachedApps.size());
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
                Drawable drawable = pm.getApplicationIcon(pkg);
                Bitmap original = rasterizeOriginal(drawable);
                Bitmap circle = createCircularIcon(drawable);
                result.add(new AppItem(pkg, circle, original));
            } catch (Throwable error) {
                XposedBridge.log(TAG + " cannot rasterize " + pkg + ": " + error);
            }
            if (result.size() == FlymeArcGeometry.MAX_APPS) break;
        }
        return result;
    }

    private Bitmap rasterizeOriginal(Drawable icon) {
        int size = Math.round(ArcConfig.ICON_MAX
                * context.getResources().getDisplayMetrics().density);
        Bitmap original = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        icon.setBounds(0, 0, size, size);
        icon.draw(new Canvas(original));
        return original;
    }

    /** Direct circular clipping, no artificial white backdrop or outline. */
    private Bitmap createCircularIcon(Drawable icon) {
        int size = Math.round(ArcConfig.ICON_MAX
                * context.getResources().getDisplayMetrics().density);
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
        final Bitmap circle;
        final Bitmap original;
        AppItem(String packageName, Bitmap circle, Bitmap original) {
            this.packageName = packageName;
            this.circle = circle;
            this.original = original;
        }
    }

    private final class RowView extends View {
        final int side;
        final List<AppItem> apps;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint morePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int radiusPercent;
        private final int iconSizeDp;
        private final int spacingDp;
        private final boolean originalIcons;
        private final int[] windowPosition = new int[2];
        private final float[] arcX = new float[FlymeArcGeometry.MAX_POSITIONS];
        private final float[] arcY = new float[FlymeArcGeometry.MAX_POSITIONS];
        private int measuredWidth = -1, measuredHeight = -1;
        private ValueAnimator animation;
        private float reveal;
        private int hovered = -1;

        RowView(Context ctx, int side, List<AppItem> apps, int radius,
                int icon, int spacing, boolean original) {
            super(ctx);
            this.side = side;
            this.apps = apps;
            density = ctx.getResources().getDisplayMetrics().density;
            radiusPercent = radius;
            iconSizeDp = icon;
            spacingDp = spacing;
            originalIcons = original;
            setContentDescription("Flyme 可调圆弧小窗菜单");
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

        private void ensureArcPoints() {
            int width = getWidth(), height = getHeight();
            if (width == measuredWidth && height == measuredHeight) return;
            measuredWidth = width;
            measuredHeight = height;
            int count = FlymeArcGeometry.visiblePositions(apps.size());
            for (int pos = 0; pos < count; pos++) {
                arcX[pos] = FlymeArcGeometry.x(side, pos, width, density,
                        radiusPercent, iconSizeDp, spacingDp, count);
                arcY[pos] = FlymeArcGeometry.y(pos, width, height, density,
                        radiusPercent, iconSizeDp, spacingDp, count);
            }
        }

        int hit(float rawX, float rawY) {
            ensureArcPoints();
            getLocationOnScreen(windowPosition);
            float x = rawX - windowPosition[0], y = rawY - windowPosition[1];
            float radius = dp(iconSizeDp * .5f + 8f);
            float closest = radius * radius;
            int selected = -1;
            for (int i = 0; i < FlymeArcGeometry.visiblePositions(apps.size()); i++) {
                float dx = x - arcX[i], dy = y - arcY[i];
                float distance = dx * dx + dy * dy;
                if (distance <= closest) {
                    closest = distance;
                    selected = i;
                }
            }
            return selected;
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
            ensureArcPoints();
            final int count = FlymeArcGeometry.visiblePositions(apps.size());
            // All stops lie on one actual circle, rather than a straight line.
            for (int pos = 0; pos < count; pos++) {
                float delay = pos * .037f;
                float step = Math.max(0f, Math.min(1f, (reveal - delay) / (1f - delay)));
                if (step <= 0) continue;
                float x = arcX[pos];
                float y = arcY[pos] + (1f - step) * dp(9f);
                float scale = (.84f + .16f * step) * (hovered == pos ? 1.13f : 1f)
                        * iconSizeDp / (float) ArcConfig.ICON_MAX;
                int alpha = Math.round(255f * step);

                canvas.save();
                canvas.translate(x, y);
                canvas.scale(scale, scale);
                if (pos == 0) {
                    drawMore(canvas, alpha);
                } else {
                    AppItem item = apps.get(pos - 1);
                    Bitmap icon = originalIcons ? item.original : item.circle;
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
            canvas.drawCircle(0f, 0f, dp(ArcConfig.ICON_MAX * .5f), morePaint);
            morePaint.setColor(Color.WHITE);
            morePaint.setAlpha(alpha);
            for (int i = -1; i <= 1; i++)
                canvas.drawCircle(dp(9.3f * i), 0f, dp(3.4f), morePaint);
        }
    }
}
