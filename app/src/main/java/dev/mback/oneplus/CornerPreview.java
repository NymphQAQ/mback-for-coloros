package dev.mback.oneplus;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import java.util.HashMap;

/** Uses the real recognition geometry and flight path without launching an app. */
final class CornerPreview extends View {
    private final HashMap<String, Integer> values = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF phone = new RectF();
    private final Point screen = new Point();
    private final IconFlight trail = new IconFlight();
    private final float[] point = new float[2];
    private final float density;
    private final int count, manualOuterCount, manualInnerCount;
    private CornerRingGeometry arc;
    private float scale, left, top, crop, reveal, downX, downY, cornerRadius;
    private int side = CornerGesture.RIGHT, armed;
    private boolean opened, held, closing;
    private ValueAnimator animation;
    private final Runnable dismiss = this::close;

    CornerPreview(Context context) {
        super(context);
        count = FlymeArcGeometry.visiblePositions(ShortcutStore.read(context).size());
        manualOuterCount = ShortcutStore.read(context, 1).size();
        manualInnerCount = ShortcutStore.read(context, 2).size();
        density = context.getResources().getDisplayMetrics().density;
        ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(screen);
        setContentDescription("呼出范围预览，彩色区域可斜滑，播放呼出与收回动画");
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> replay());
    }

    void setValue(String key, int value) { values.put(key, value); arc = null; invalidate(); }
    private int read(String key, int fallback) {
        Integer value = values.get(key);
        return value == null ? Settings.Global.getInt(getContext().getContentResolver(), key, fallback) : value;
    }
    private CornerRegion region() {
        int[] sizes = new int[6];
        for (int i = 0; i < 6; i++) sizes[i] = read(CornerRegion.KEYS[i],
                CornerPreferences.fallback(i));
        return new CornerRegion(screen.x, screen.y, density, sizes);
    }
    void side(int value) { side = value; arc = null; replay(); }

    void replay() {
        held = false;
        CornerRegion r = region();
        float x, up;
        if (r.bottomLength > 0 && r.bottomHeight > 0) { x = r.bottomLength * .65f; up = r.bottomHeight * .5f; }
        else if (r.sideLength > 0 && r.sideWidth > 0) { x = r.sideWidth * .5f; up = r.sideLength * .65f; }
        else if (r.triangleWidth > 0 && r.triangleHeight > 0) { x = r.triangleWidth * .3f; up = r.triangleHeight * .3f; }
        else { dismiss.run(); return; }
        float sx = side == CornerGesture.RIGHT ? screen.x - x : x;
        float sy = screen.y - up, direction = side == CornerGesture.RIGHT ? -1 : 1;
        trail.reset(sx, sy);
        trail.add(sx + direction * 12 * density, sy - 10 * density);
        trail.add(sx + direction * 32 * density, sy - 35 * density);
        trail.add(sx + direction * 65 * density, sy - 58 * density);
        startFlight();
        postDelayed(dismiss, 1800);
    }

    private void startFlight() {
        reset();
        opened = true;
        reveal = 0;
        int duration = CornerPreferences.animationDuration(read(CornerPreferences.ANIMATION_KEY,
                CornerPreferences.DEFAULT_ANIMATION));
        if (duration == 0) { reveal = 1; invalidate(); return; }
        animation = ValueAnimator.ofFloat(0, 1);
        animation.setDuration(duration);
        animation.setInterpolator(new DecelerateInterpolator(1.7f));
        animation.addUpdateListener(a -> { reveal = (Float) a.getAnimatedValue(); invalidate(); });
        animation.start();
    }

    private void close() {
        if (!opened || closing) return;
        if (animation == null || reveal <= 0) { reset(); return; }
        held = false;
        closing = true;
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animator) {
                if (animation == animator) reset();
            }
        });
        animation.reverse();
    }

    private void reset() {
        removeCallbacks(dismiss);
        ValueAnimator old = animation;
        animation = null;
        if (old != null) old.cancel();
        opened = held = closing = false;
        reveal = 0;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (scale <= 0) return true;
        float x = (e.getX() - left) / scale, y = (e.getY() - top) / scale + crop;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                removeCallbacks(dismiss);
                if (held) { held = false; dismiss.run(); return true; }
                reset();
                armed = region().origin(x, y);
                float inward = Math.min(x, screen.x - x), up = screen.y - y;
                if (inward < cornerRadius && up < cornerRadius
                        && Math.hypot(cornerRadius - inward, cornerRadius - up) > cornerRadius)
                    armed = CornerGesture.NONE;
                downX = x; downY = y;
                trail.reset(x, y);
                if (armed != 0) getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (armed != 0) {
                    trail.add(x, y);
                    if (!opened && CornerGesture.earlyCapture(armed, downX, downY, x, y, density)) {
                        side = armed; startFlight();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (opened && !closing) {
                    held = read(CornerPreferences.KEEP_OPEN_KEY, 0) == 1;
                    if (held && animation != null) animation.end();
                    if (!held) postDelayed(dismiss, 1000);
                }
                armed = 0;
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                armed = 0; held = false; dismiss.run(); break;
        }
        return true;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        ensureArc();
        float worldHeight = screen.x * .98f;
        for (int i = 0; i < arc.count; i++) worldHeight = Math.max(worldHeight,
                screen.y - arc.y[i] + density * (arc.icon[i] * .565f + 12));
        worldHeight = Math.min(screen.y, worldHeight);
        scale = Math.min((getWidth() - 16 * density) / screen.x,
                (getHeight() - 8 * density) / worldHeight);
        if (scale <= 0) return;
        left = (getWidth() - screen.x * scale) * .5f;
        top = (getHeight() - worldHeight * scale) * .5f;
        crop = screen.y - worldHeight;
        c.save(); c.translate(left, top); c.scale(scale, scale); c.translate(0, -crop);
        phone.set(0, -screen.y, screen.x, screen.y);
        float corner = 32 * density;
        if (android.os.Build.VERSION.SDK_INT >= 31 && getRootWindowInsets() != null) {
            android.view.RoundedCorner rounded = getRootWindowInsets().getRoundedCorner(
                    android.view.RoundedCorner.POSITION_BOTTOM_RIGHT);
            if (rounded != null) corner = rounded.getRadius();
        }
        cornerRadius = corner;
        path.reset(); path.addRoundRect(phone, corner, corner, Path.Direction.CW);
        c.clipPath(path);
        paint.setColor(0xFFE7EDE8); c.drawRect(phone, paint);
        paint.setColor(Color.WHITE);
        for (int i = 0; i < 3; i++) c.drawRoundRect(26 * density, crop + (35 + 76 * i) * density,
                screen.x - 26 * density, crop + (93 + 76 * i) * density, 15 * density, 15 * density, paint);
        if (opened) {
            paint.setColor(Color.argb(Math.round(255 * Math.max(0, Math.min(70,
                    read(CornerPreferences.DIM_KEY, CornerPreferences.DEFAULT_DIM))) / 100f * reveal), 0, 0, 0));
            c.drawRect(phone, paint);
        }
        CornerRegion r = region();
        for (int edge = CornerGesture.LEFT; edge <= CornerGesture.RIGHT; edge++) {
            c.save();
            if (edge == CornerGesture.RIGHT) { c.translate(screen.x, 0); c.scale(-1, 1); }
            paint.setColor(0x805791D9);
            c.drawRect(0, screen.y - r.bottomHeight, r.bottomLength, screen.y, paint);
            paint.setColor(0x80E7A23E);
            c.drawRect(0, screen.y - r.sideLength, r.sideWidth, screen.y, paint);
            path.reset(); path.moveTo(0, screen.y); path.lineTo(r.triangleWidth, screen.y);
            path.lineTo(0, screen.y - r.triangleHeight); path.close();
            paint.setColor(0x807C65CB); c.drawPath(path, paint);
            c.restore();
        }
        if (opened) {
            for (int i = 0; i < arc.count; i++) {
                float x = arc.x[i], y = arc.y[i], icon = arc.icon[i];
                float step = Math.max(0, Math.min(1, (reveal - i * .037f) / (1 - i * .037f)));
                trail.point(step, x, y, point);
                paint.setColor(i == 0 ? 0xFF2189FE : i < arc.middleCount ? 0xFF28684E : i < arc.innerStart ? 0xFF3673A8 : 0xFF619A7D);
                paint.setAlpha(Math.round(255 * step));
                c.drawCircle(point[0], point[1], icon * density * .5f * (.45f + .55f * step), paint);
                paint.setColor(Color.WHITE); paint.setAlpha(Math.round(255 * step));
                paint.setTextSize(icon * density * .42f); paint.setTextAlign(Paint.Align.CENTER);
                c.drawText(i == 0 ? "···" : String.valueOf(i), point[0], point[1] + icon * density * .15f, paint);
            }
        }
        paint.setColor(0xFF6C7C74);
        c.drawRoundRect(screen.x * .37f, screen.y - 9 * density, screen.x * .63f,
                screen.y - 5 * density, 2 * density, 2 * density, paint);
        c.restore();
    }

    private void ensureArc() {
        if (arc != null) return;
        int outer = previewRingCount(RingConfig.OUTER, manualOuterCount);
        int inner = previewRingCount(RingConfig.INNER, manualInnerCount);
        arc = new CornerRingGeometry(side, screen.x, screen.y, density,
                read(ArcConfig.RADIUS_KEY, ArcConfig.DEFAULT_RADIUS), read(ArcConfig.ICON_SIZE_KEY, ArcConfig.DEFAULT_ICON),
                count - 1, read(ArcConfig.SIDE_MARGIN_KEY, ArcConfig.DEFAULT_SIDE_MARGIN),
                read(ArcConfig.BOTTOM_MARGIN_KEY, ArcConfig.DEFAULT_BOTTOM_MARGIN), outer,
                read(RingConfig.OUTER.iconKey, RingConfig.DEFAULT_ICON), inner,
                read(RingConfig.INNER.iconKey, RingConfig.DEFAULT_ICON));
    }

    private int previewRingCount(RingConfig ring, int manualCount) {
        int count = read(ring.enableKey, 0) == 1
                ? Math.max(1, Math.min(RingConfig.MAX_APPS, read(ring.countKey, ring.defaultCount))) : 0;
        return read(ring.recentKey, ring.defaultRecent) == 1 ? count : Math.min(count, manualCount);
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }
}
