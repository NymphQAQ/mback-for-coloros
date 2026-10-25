package dev.mback.oneplus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Decorative gesture diagrams and the matching bottom-navigation symbols. */
final class HomeArtwork extends View {
    private final boolean shortcuts;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    HomeArtwork(Context context, boolean shortcuts) {
        super(context);
        this.shortcuts = shortcuts;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float unit = getHeight() / 158f;
        canvas.save();
        canvas.translate(getWidth() - 139 * unit, 0);
        canvas.scale(unit, unit);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(shortcuts ? 0xFFD2E6D8 : 0xFF244C3C);
        canvas.drawCircle(115, 85, 99, paint);
        paint.setColor(shortcuts ? 0xFFC4DCCD : 0xFF2C5643);
        canvas.drawCircle(146, 143, 74, paint);
        if (shortcuts) drawWindows(canvas);
        else drawPill(canvas);
        canvas.restore();
    }

    private void drawPill(Canvas canvas) {
        canvas.save();
        canvas.rotate(-12, 88, 95);
        paint.setColor(0xFF406351);
        canvas.drawRoundRect(49, 13, 137, 174, 20, 20, paint);
        paint.setColor(0xFF254635);
        canvas.drawRoundRect(53, 17, 133, 170, 17, 17, paint);
        paint.setColor(0xFF436B55);
        canvas.drawRoundRect(63, 38, 121, 48, 5, 5, paint);
        canvas.drawRoundRect(63, 57, 101, 63, 3, 3, paint);
        paint.setColor(0xFF31583F);
        canvas.drawRoundRect(63, 75, 121, 108, 9, 9, paint);
        paint.setColor(0xFF436B55);
        canvas.drawCircle(91, 139, 25, paint);
        paint.setColor(0xFF749D7E);
        canvas.drawCircle(91, 139, 16, paint);
        paint.setColor(0xFFE0F2D9);
        canvas.drawRoundRect(69, 136, 113, 142, 3, 3, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(108, 145, 5, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.4f);
        paint.setColor(0xFFB5D1BC);
        canvas.drawCircle(117, 132, 11, paint);
        canvas.drawCircle(117, 132, 18, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWindows(Canvas canvas) {
        canvas.save();
        canvas.rotate(-9, 91, 84);
        paint.setColor(0xFFAFCCB9);
        canvas.drawRoundRect(40, 29, 125, 126, 17, 17, paint);
        paint.setColor(0xFFFBFEFA);
        canvas.drawRoundRect(53, 43, 140, 142, 17, 17, paint);
        paint.setColor(0xFFE7EFE7);
        canvas.drawRoundRect(65, 58, 119, 65, 3.5f, 3.5f, paint);
        canvas.drawRoundRect(65, 73, 103, 79, 3, 3, paint);
        paint.setColor(0xFFD7E8DA);
        canvas.drawRoundRect(65, 92, 128, 126, 8, 8, paint);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.2f);
        paint.setColor(0xFF79A58A);
        path.reset();
        path.moveTo(139, 151);
        path.cubicTo(74, 150, 18, 116, 22, 59);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        float[] points = {33, 110, 57, 135, 91, 147};
        for (int i = 0; i < points.length; i += 2) {
            paint.setColor(i == 2 ? MainActivity.INK : MainActivity.GREEN);
            canvas.drawRoundRect(points[i] - 13, points[i + 1] - 13,
                    points[i] + 13, points[i + 1] + 13, 8, 8, paint);
            paint.setColor(0xFFE8F3E6);
            canvas.drawCircle(points[i], points[i + 1], 3, paint);
        }
    }

    static final class NavIcon extends Drawable {
        private final boolean shortcuts;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        NavIcon(Context context, boolean shortcuts, int color) {
            this.shortcuts = shortcuts;
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.7f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            size = Math.round(24 * context.getResources().getDisplayMetrics().density);
        }

        @Override public void draw(Canvas canvas) {
            canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
            if (shortcuts) {
                canvas.drawRoundRect(3, 3, 17, 17, 3, 3, paint);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(9, 10, 22, 22, 3, 3, paint);
                paint.setStyle(Paint.Style.STROKE);
            } else {
                canvas.drawRoundRect(3, 14, 21, 19, 2.5f, 2.5f, paint);
                canvas.drawCircle(12, 7, 2.7f, paint);
                canvas.drawLine(12, 10, 12, 11, paint);
            }
            canvas.restore();
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
