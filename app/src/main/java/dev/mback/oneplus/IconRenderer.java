package dev.mback.oneplus;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;

final class IconRenderer {
    static Bitmap circle(Drawable icon) {
        int size = 256;
        boolean adaptive = icon instanceof AdaptiveIconDrawable;
        Drawable foreground = adaptive ? ((AdaptiveIconDrawable) icon).getForeground() : icon;
        Bitmap source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        draw(foreground, new Canvas(source), size, true);
        int[] pixels = new int[size * size];
        source.getPixels(pixels, 0, size, 0, 0, size, size);
        boolean visible = false;
        for (int pixel : pixels) if ((pixel >>> 24) != 0) { visible = true; break; }
        if (adaptive && !visible && ((AdaptiveIconDrawable) icon).getBackground() != null) {
            source.recycle();
            return circle(((AdaptiveIconDrawable) icon).getBackground());
        }
        Drawable background = adaptive ? ((AdaptiveIconDrawable) icon).getBackground() : null;
        if (adaptive && visible && (background == null || background.getOpacity() == PixelFormat.TRANSPARENT
                || foreground.getOpacity() == PixelFormat.OPAQUE)) {
            source.recycle();
            return circle(foreground);
        }
        IconLayout layout = new IconLayout(pixels, size, adaptive);
        Bitmap artwork = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(artwork);
        canvas.drawColor(layout.background);
        if (adaptive) {
            draw(background, canvas, size, false);
        }
        Matrix transform = new Matrix();
        transform.setScale(layout.scale, layout.scale);
        transform.postTranslate(layout.x, layout.y);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, transform, paint);
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        result.setDensity(Bitmap.DENSITY_NONE);
        paint.setShader(new BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(result).drawCircle(size * .5f, size * .5f, size * .5f, paint);
        source.recycle();
        artwork.recycle();
        return result;
    }

    private static void draw(Drawable source, Canvas canvas, int size, boolean contain) {
        if (source == null) return;
        Rect previous = new Rect(source.getBounds());
        try {
            int width = source.getIntrinsicWidth(), height = source.getIntrinsicHeight();
            if (contain && width > 0 && height > 0) {
                float scale = size / (float) Math.max(width, height);
                width = Math.round(width * scale); height = Math.round(height * scale);
            } else { width = height = size; }
            source.setBounds((size - width) / 2, (size - height) / 2,
                    (size + width) / 2, (size + height) / 2);
            source.draw(canvas);
        } finally { source.setBounds(previous); }
    }
}
