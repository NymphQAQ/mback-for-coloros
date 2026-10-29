package dev.mback.oneplus;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.os.Vibrator;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

/** Three corner arcs with a gesture phase and an optional retained phase. */
final class CornerAppMenu {
    static final String ENABLE_KEY = "mback_corner_menu_enabled";
    static final int DEFAULT_ENABLED = 1;
    static final String APPS_KEY = "mback_corner_menu_apps";
    private static final String TAG = "[MBack-Corner]";

    private final Context context;
    private final WindowManager windowManager;
    private final FreeformLauncher launcher;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile List<AppItem> cachedApps = Collections.emptyList();
    private volatile String cachedCsv;
    private volatile String loadingCsv;
    private RowView view;
    private ExtraApps cachedExtra = new ExtraApps(Collections.emptyList(), Collections.emptyList());
    private boolean refreshingExtra;
    private volatile int iconGeneration;
    private final android.util.LruCache<String, AppItem> extraIcons = new android.util.LruCache<>(40);
    private Bitmap placeholderCircle, placeholderOriginal;

    private int radiusPercent() {
        return ArcConfig.radius(Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.RADIUS_KEY, ArcConfig.DEFAULT_RADIUS));
    }

    private int iconSizeDp() {
        return ArcConfig.icon(Settings.Global.getInt(context.getContentResolver(),
                ArcConfig.ICON_SIZE_KEY, ArcConfig.DEFAULT_ICON));
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
        android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        if (users != null && !users.isUserUnlocked()) return;
        refreshExtraApps();
        String csv = ShortcutStore.snapshot(context);
        if (csv.equals(cachedCsv) || csv.equals(loadingCsv)) return;
        final String requested = csv;
        final int generation = iconGeneration;
        loadingCsv = requested;
        new Thread(() -> {
            List<AppItem> prepared = loadApps(requested);
            main.post(() -> {
                if (generation == iconGeneration && requested.equals(loadingCsv)) {
                    cachedApps = Collections.unmodifiableList(prepared);
                    cachedCsv = requested;
                    loadingCsv = null;
                    XposedBridge.log(TAG + " cached circle and original icons=" + prepared.size());
                }
            });
        }, "mback-icon-cache").start();
    }

    void invalidateIcons() {
        iconGeneration++;
        cachedCsv = null;
        loadingCsv = null;
        extraIcons.evictAll();
        refreshingExtra = false;
        prepare();
    }

    boolean isOpened() { return view != null; }
    boolean isGestureActive() { return view != null && !view.latched && !view.closing; }
    boolean isLatched() { return view != null && view.latched && !view.closing; }

    void showForGesture(int side, IconFlight trail) {
        hide();
        if (context.getResources().getConfiguration().orientation
                != android.content.res.Configuration.ORIENTATION_PORTRAIT) return;
        try {
            int radius = radiusPercent();
            int icon = iconSizeDp();
            int sideMargin = ArcConfig.margin(Settings.Global.getInt(context.getContentResolver(),
                    ArcConfig.SIDE_MARGIN_KEY, ArcConfig.DEFAULT_SIDE_MARGIN));
            int bottomMargin = ArcConfig.margin(Settings.Global.getInt(context.getContentResolver(),
                    ArcConfig.BOTTOM_MARGIN_KEY, ArcConfig.DEFAULT_BOTTOM_MARGIN));
            int vibration = ArcConfig.hoverVibration(Settings.Global.getInt(
                    context.getContentResolver(), ArcConfig.HOVER_VIBRATION_KEY,
                    ArcConfig.DEFAULT_HOVER_VIBRATION));
            // shortcut: a slow refresh appears on the next gesture; use task-stack callbacks if fresher ordering is needed.
            ExtraApps extra = cachedExtra;
            RowView next = new RowView(context, side, cachedApps,
                    visibleRingApps(RingConfig.OUTER, extra.outer), visibleRingApps(RingConfig.INNER, extra.inner),
                    radius, icon, useOriginalIcons(), vibration, sideMargin, bottomMargin, trail);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
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
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("MBack Flyme Shortcut");
            windowManager.addView(next, lp);
            view = next;
            next.enter();
            XposedBridge.log(TAG + " adaptive arc shown side=" + side
                    + " radius=" + radius + "% icon=" + icon + "dp hover=" + vibration
                    + " original=" + useOriginalIcons() + " apps=" + cachedApps.size());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " show failed: " + error);
            hide();
        }
    }

    void highlightBySwipe(float rawX, float rawY) {
        RowView showing = view;
        if (showing == null || showing.closing) return;
        showing.select(rawX, rawY);
    }

    /** Called exactly once for the matching finger UP, even over empty space. */
    void finishGesture(float rawX, float rawY, long releasedAt) {
        RowView showing = view;
        if (showing == null || showing.latched || showing.closing) return;
        if (showing.getWidth() == 0) {
            showing.postOnAnimation(() -> { if (view == showing) finishGesture(rawX, rawY, releasedAt); });
            return;
        }
        int position = showing.hit(rawX, rawY);
        if (position >= 0) {
            openPosition(position);
            return;
        }
        boolean keepOpen = Settings.Global.getInt(context.getContentResolver(),
                CornerPreferences.KEEP_OPEN_KEY, 0) == 1;
        if (keepOpen) {
            try {
                showing.latch(releasedAt);
                WindowManager.LayoutParams lp = (WindowManager.LayoutParams) showing.getLayoutParams();
                lp.flags &= ~(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                windowManager.updateViewLayout(showing, lp);
                showing.setFocusableInTouchMode(true);
                showing.requestFocus();
            } catch (Throwable error) {
                XposedBridge.log(TAG + " cannot keep menu open: " + error);
                hide();
            }
            return;
        }
        dismiss();
    }

    private void openPosition(int position) {
        RowView showing = view;
        if (showing == null || showing.closing) return;
        AppItem selected = position > 0 && position < showing.positionCount() ? showing.appAt(position) : null;
        boolean more = position == FlymeArcGeometry.MORE_POSITION;
        dismiss(() -> {
            if (selected != null) {
                launcher.open(selected.target);
                XposedBridge.log(TAG + " opened shortcut " + selected.target.id());
            } else if (more) FreeformLauncher.openMore(context);
        });
    }

    void dismiss() { dismiss(null); }

    private void dismiss(Runnable action) {
        RowView showing = view;
        if (showing == null || showing.closing) return;
        Runnable finished = () -> {
            if (view != showing) return;
            hide();
            KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
            if (action != null && !ShadeVisibility.isExpanded() && (keyguard == null || !keyguard.isDeviceLocked()))
                action.run();
        };
        try {
            // Stop intercepting input now, and remove the overlay before launching the selected app.
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) showing.getLayoutParams();
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            windowManager.updateViewLayout(showing, lp);
            showing.exit(finished);
        } catch (Throwable error) {
            XposedBridge.log(TAG + " dismiss animation failed: " + error);
            finished.run();
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
        for (ShortcutStore.Item item : ShortcutStore.decode(csv)) {
            try {
                result.add(loadApp(item));
            } catch (Throwable error) {
                XposedBridge.log(TAG + " cannot rasterize " + item.target.id() + ": " + error);
            }
            if (result.size() == FlymeArcGeometry.MAX_APPS) break;
        }
        return result;
    }

    private AppItem loadApp(ShortcutStore.Item item) throws Exception {
        Bitmap original = rasterizeOriginal(ShortcutIcons.load(context, item, true));
        Bitmap circle = rasterizeOriginal(ShortcutIcons.load(context, item, false));
        return new AppItem(item.target, ShortcutStore.label(context, item), circle, original);
    }

    private static final class ExtraApps {
        final List<AppItem> outer, inner;
        ExtraApps(List<AppItem> outer, List<AppItem> inner) { this.outer = outer; this.inner = inner; }
    }

    void refreshExtraApps() {
        if (refreshingExtra) return;
        refreshingExtra = true;
        final int generation = iconGeneration;
        new Thread(() -> {
            try {
                ExtraApps prepared = resolveExtraApps();
                main.post(() -> {
                    if (generation != iconGeneration) return;
                    cachedExtra = prepared;
                    refreshingExtra = false;
                });
            } catch (Throwable error) {
                XposedBridge.log(TAG + " ring refresh failed: " + error);
                main.post(() -> { if (generation == iconGeneration) refreshingExtra = false; });
            }
        }, "mback-recent-cache").start();
    }

    private List<AppItem> visibleRingApps(RingConfig ring, List<AppItem> apps) {
        return ring.enabled(context) ? apps.subList(0, Math.min(apps.size(), ring.count(context)))
                : Collections.emptyList();
    }

    private ExtraApps resolveExtraApps() {
        java.util.HashSet<String> reserved = new java.util.HashSet<>();
        List<ShortcutStore.Item> outer = RingConfig.OUTER.items(context, reserved);
        if (RingConfig.OUTER.recent(context) && RingConfig.INNER.recent(context))
            for (ShortcutStore.Item item : outer) reserved.add(item.target.id());
        List<ShortcutStore.Item> inner = RingConfig.INNER.items(context, reserved);
        return new ExtraApps(resolveRingApps(outer), resolveRingApps(inner));
    }

    private List<AppItem> resolveRingApps(List<ShortcutStore.Item> sources) {
        final int generation = iconGeneration;
        ArrayList<AppItem> result = new ArrayList<>(), waiting = new ArrayList<>();
        ArrayList<ShortcutStore.Item> missing = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();
        for (ShortcutStore.Item source : sources) {
            String key = generation + ":" + source.target.id() + ":" + source.icon;
            AppItem item = extraIcons.get(key);
            if (item == null) {
                if (placeholderOriginal == null) {
                    Drawable fallback = context.getPackageManager().getDefaultActivityIcon();
                    placeholderOriginal = rasterizeOriginal(fallback);
                    placeholderCircle = rasterizeOriginal(new android.graphics.drawable.BitmapDrawable(
                            context.getResources(), IconRenderer.circle(fallback)));
                }
                item = new AppItem(source.target, ShortcutStore.label(context, source), placeholderCircle, placeholderOriginal);
                extraIcons.put(key, item);
                missing.add(source); waiting.add(item); keys.add(key);
            }
            result.add(item);
        }
        if (!missing.isEmpty()) new Thread(() -> {
            for (int i = 0; i < missing.size(); i++) {
                AppItem item = waiting.get(i);
                String key = keys.get(i);
                try {
                    AppItem loaded = loadApp(missing.get(i));
                    main.post(() -> {
                        if (generation != iconGeneration) return;
                        item.circle = loaded.circle;
                        item.original = loaded.original;
                        if (view != null) view.invalidate();
                    });
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " ring icon unavailable: " + error);
                    main.post(() -> { if (extraIcons.get(key) == item) extraIcons.remove(key); });
                }
            }
        }, "mback-ring-icons").start();
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

    private static final class AppItem {
        final ShortcutTarget target;
        final String label;
        Bitmap circle;
        Bitmap original;
        AppItem(ShortcutTarget target, String label, Bitmap circle, Bitmap original) {
            this.target = target;
            this.label = label;
            this.circle = circle;
            this.original = original;
        }
    }

    private final class RowView extends FrameLayout {
        final int side;
        final List<AppItem> apps, outerApps, innerApps;
        private final int outerIconDp, innerIconDp;
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint morePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private final int radiusPercent;
        private final int iconSizeDp;
        private final int sideMarginDp, bottomMarginDp;
        private final boolean originalIcons;
        private final Path moreMask;
        private final int hoverVibration;
        private final Vibrator vibrator;
        private long lastHapticAt;
        private final int[] windowPosition = new int[2];
        private CornerRingGeometry geometry;
        private int measuredWidth = -1, measuredHeight = -1;
        private ValueAnimator animation;
        private float reveal;
        private int hovered = -1;
        private final IconFlight trail;
        private final float[] flightPoint = new float[2];
        private final int dim;
        private final int animationDuration;
        private boolean latched, closing;
        private final int touchSlop;
        private long releasedAt, touchDownTime = -1;
        private float touchX, touchY;
        private boolean touchMoved;


        RowView(Context ctx, int side, List<AppItem> apps, List<AppItem> outerApps, List<AppItem> innerApps, int radius,
                int icon, boolean original, int vibration, int sideMargin, int bottomMargin, IconFlight trail) {
            super(ctx);
            setWillNotDraw(false);
            this.trail = trail;
            dim = CornerPreferences.dim(ctx);
            animationDuration = CornerPreferences.animationDuration(ctx);
            touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            this.side = side;
            this.apps = apps;
            this.outerApps = outerApps;
            this.innerApps = innerApps;
            outerIconDp = RingConfig.OUTER.icon(ctx);
            innerIconDp = RingConfig.INNER.icon(ctx);
            density = ctx.getResources().getDisplayMetrics().density;
            radiusPercent = radius;
            iconSizeDp = icon;
            sideMarginDp = sideMargin;
            bottomMarginDp = bottomMargin;
            originalIcons = original;
            AdaptiveIconDrawable shape = new AdaptiveIconDrawable(
                    new ColorDrawable(Color.BLUE), new ColorDrawable(Color.TRANSPARENT));
            int maskSize = Math.round(dp(ArcConfig.ICON_MAX));
            shape.setBounds(0, 0, maskSize, maskSize);
            moreMask = new Path(shape.getIconMask());
            moreMask.offset(-maskSize * .5f, -maskSize * .5f);
            hoverVibration = vibration;
            vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            setContentDescription("快捷小窗，选中应用后松手打开，点击空白处关闭");
        }

        private float dp(float value) { return value * density; }

        void enter() {
            if (animationDuration == 0) { reveal = 1f; invalidate(); return; }
            animation = flightAnimation();
            animation.start();
        }

        private ValueAnimator flightAnimation() {
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(animationDuration);
            animator.setInterpolator(new DecelerateInterpolator(1.7f));
            animator.addUpdateListener(a -> {
                reveal = (Float) a.getAnimatedValue();
                postInvalidateOnAnimation();
            });
            return animator;
        }

        void stop() {
            ValueAnimator old = animation;
            animation = null;
            if (old != null) old.cancel();
        }

        void exit(Runnable finished) {
            closing = true;
            if (animationDuration == 0 || reveal <= 0) { finished.run(); return; }
            if (animation == null) animation = flightAnimation();
            animation.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animator) {
                    if (animation != animator) return;
                    animation = null;
                    finished.run();
                }
            });
            animation.reverse();
        }

        void latch(long releasedAt) {
            this.releasedAt = releasedAt;
            touchDownTime = -1;
            stop();
            reveal = 1f;
            hovered = -1;
            latched = true;
            ensureArcPoints();
            for (int i = 0; i < positionCount(); i++) {
                int size = Math.round(dp(Math.max(48, geometry.icon[i] + 16)));
                final int position = i;
                Button target = new Button(getContext());
                target.setBackgroundColor(Color.TRANSPARENT);
                target.setStateListAnimator(null);
                target.setContentDescription(i == 0 ? "更多应用" : (i >= geometry.innerStart ? "内圈，" : i >= geometry.outerStart ? "外圈，" : "中圈，") + appAt(i).label);
                // Accessibility clicks bypass the physical touch-stream gate.
                target.setOnClickListener(v -> {
                    if (view == RowView.this && latched) openPosition(position);
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
                lp.leftMargin = Math.round(geometry.x[i] - size * .5f);
                lp.topMargin = Math.round(geometry.y[i] - size * .5f);
                addView(target, lp);
            }
            invalidate();
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            return latched || super.onInterceptTouchEvent(event);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!latched || closing) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                // Ignore the original stroke when the window becomes touchable.
                touchDownTime = event.getDownTime() > releasedAt ? event.getDownTime() : -1;
                if (touchDownTime < 0) return true;
                touchX = event.getRawX();
                touchY = event.getRawY();
                touchMoved = false;
                select(touchX, touchY);
                return true;
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) { dismiss(); return true; }
            if (touchDownTime < 0 || event.getDownTime() != touchDownTime) return true;
            if (Math.hypot(event.getRawX() - touchX, event.getRawY() - touchY) > touchSlop)
                touchMoved = true;
            if (action == MotionEvent.ACTION_MOVE) {
                highlightBySwipe(event.getRawX(), event.getRawY());
            } else if (action == MotionEvent.ACTION_UP) {
                int position = hit(event.getRawX(), event.getRawY());
                touchDownTime = -1;
                hovered = -1;
                invalidate();
                if (position >= 0) openPosition(position);
                else if (!touchMoved) performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                touchDownTime = -1;
                hovered = -1;
                invalidate();
            }
            return true;
        }

        @Override public boolean performClick() { super.performClick(); dismiss(); return true; }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        int positionCount() { return 1 + apps.size() + outerApps.size() + innerApps.size(); }

        AppItem appAt(int position) {
            int index = position - 1;
            if (index < apps.size()) return apps.get(index);
            index -= apps.size();
            return index < outerApps.size() ? outerApps.get(index) : innerApps.get(index - outerApps.size());
        }

        private void ensureArcPoints() {
            int width = getWidth(), height = getHeight();
            if (width == measuredWidth && height == measuredHeight) return;
            measuredWidth = width;
            measuredHeight = height;
            geometry = new CornerRingGeometry(side, width, height, density, radiusPercent, iconSizeDp,
                    apps.size(), sideMarginDp, bottomMarginDp, outerApps.size(), outerIconDp, innerApps.size(), innerIconDp);
        }

        int hit(float rawX, float rawY) {
            ensureArcPoints();
            getLocationOnScreen(windowPosition);
            float x = rawX - windowPosition[0], y = rawY - windowPosition[1];
            float closest = Float.MAX_VALUE;
            int selected = -1;
            for (int i = 0; i < positionCount(); i++) {
                float dx = x - geometry.x[i], dy = y - geometry.y[i];
                float distance = dx * dx + dy * dy;
                float radius = dp(Math.max(24, geometry.icon[i] * .5f + 8));
                if (distance <= radius * radius && distance <= closest) {
                    closest = distance;
                    selected = i;
                }
            }
            return selected;
        }

        void select(float rawX, float rawY) {
            int current = hit(rawX, rawY);
            if (current == hovered) return;
            hovered = current;
            postInvalidateOnAnimation();
            // One light tick when entering a new icon. No vibration for empty
            // space or for repeated MOVE events while staying on the same icon.
            long now = SystemClock.uptimeMillis();
            if (current >= 0 && hoverVibration > 0 && now - lastHapticAt >= 45
                    && vibrator != null && vibrator.hasVibrator()) {
                lastHapticAt = now;
                try {
                    TouchFeedback.play(vibrator, hoverVibration);
                } catch (Throwable error) {
                    XposedBridge.log(TAG + " icon haptic failed: " + error);
                }
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;
            ensureArcPoints();
            canvas.drawColor(Color.argb(Math.round(255 * dim / 100f * reveal), 0, 0, 0));
            getLocationOnScreen(windowPosition);
            final int count = positionCount();
            // All stops lie on one actual circle, rather than a straight line.
            for (int pos = 0; pos < count; pos++) {
                float delay = pos * .037f;
                float step = Math.max(0f, Math.min(1f, (reveal - delay) / (1f - delay)));
                if (step <= 0) continue;
                trail.point(step, geometry.x[pos] + windowPosition[0], geometry.y[pos] + windowPosition[1], flightPoint);
                float x = flightPoint[0] - windowPosition[0];
                float y = flightPoint[1] - windowPosition[1];
                float scale = (.45f + .55f * step) * (hovered == pos ? 1.13f : 1f)
                        * geometry.icon[pos] / (float) ArcConfig.ICON_MAX;
                int alpha = Math.round(255f * step);

                canvas.save();
                canvas.translate(x, y);
                canvas.scale(scale, scale);
                if (pos == 0) {
                    drawMore(canvas, alpha);
                } else {
                    AppItem item = appAt(pos);
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
            if (originalIcons) canvas.drawPath(moreMask, morePaint);
            else canvas.drawCircle(0f, 0f, dp(ArcConfig.ICON_MAX * .5f), morePaint);
            morePaint.setColor(Color.WHITE);
            morePaint.setAlpha(alpha);
            for (int i = -1; i <= 1; i++)
                canvas.drawCircle(dp(9.3f * i), 0f, dp(3.4f), morePaint);
        }
    }
}
