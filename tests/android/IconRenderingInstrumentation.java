package dev.mback.oneplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;

public final class IconRenderingInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Bitmap foreground = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
            for (int y = 10; y < 246; y++) for (int x = 0; x < 24; x++) foreground.setPixel(x, y, Color.WHITE);
            Drawable adaptive = new AdaptiveIconDrawable(new ColorDrawable(0xff0066ff),
                    new BitmapDrawable(getTargetContext().getResources(), foreground));
            Bitmap circle = verify(adaptive);
            for (int angle = 0; angle < 360; angle += 5) {
                int x = Math.round(127.5f + 118 * (float) Math.cos(Math.toRadians(angle)));
                int y = Math.round(127.5f + 118 * (float) Math.sin(Math.toRadians(angle)));
                check(circle.getPixel(x, y) == 0xff0066ff, "Artificial border or cropped adaptive foreground");
            }
            for (int color : new int[]{Color.WHITE, Color.BLACK, 0xff12a0ef}) {
                foreground.eraseColor(Color.TRANSPARENT);
                for (int y = 5; y < 250; y++) for (int x = 0; x < 35; x++) foreground.setPixel(x, y, color);
                Bitmap fitted = verify(new BitmapDrawable(getTargetContext().getResources(), foreground));
                int visible = 0;
                for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) if (fitted.getPixel(x, y) == color) visible++;
                check(visible > 500, "Transparent monochrome artwork disappeared");
            }
            foreground.eraseColor(0xff0066ff);
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
                if ((x < 32 || x >= 224) && (y < 32 || y >= 224)) foreground.setPixel(x, y, Color.WHITE);
            Bitmap backgroundOnly = verify(new AdaptiveIconDrawable(
                    new BitmapDrawable(getTargetContext().getResources(), foreground), new ColorDrawable(Color.TRANSPARENT)));
            int cornerArtwork = 0;
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
                if (backgroundOnly.getPixel(x, y) == Color.WHITE) cornerArtwork++;
            check(cornerArtwork > 500, "Artwork stored only in the adaptive background was cropped");
            Bitmap wide = Bitmap.createBitmap(256, 64, Bitmap.Config.ARGB_8888);
            wide.setDensity(Bitmap.DENSITY_NONE);
            for (int y = 16; y < 48; y++) for (int x = 112; x < 144; x++) wide.setPixel(x, y, 0xff12a0ef);
            Bitmap squareGlyph = verify(new BitmapDrawable(getTargetContext().getResources(), wide));
            int left = 256, top = 256, right = 0, bottom = 0;
            for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) if (squareGlyph.getPixel(x, y) == 0xff12a0ef) {
                left = Math.min(left, x); right = Math.max(right, x);
                top = Math.min(top, y); bottom = Math.max(bottom, y);
            }
            check(right > left && Math.abs(right - left - bottom + top) <= 2, "Non-square images distorted their artwork");
            File folder = new File(getTargetContext().getCacheDir(), "icon-render-checks");
            check(folder.isDirectory() || folder.mkdirs(), "Cannot create icon evidence folder");
            HashSet<String> packages = new HashSet<>();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            int adaptiveCount = 0;
            for (ResolveInfo info : getTargetContext().getPackageManager().queryIntentActivities(query, 0)) {
                String pkg = info.activityInfo.packageName;
                if (!packages.add(pkg)) continue;
                Drawable icon = getTargetContext().getPackageManager().getApplicationIcon(pkg);
                if (icon instanceof AdaptiveIconDrawable) adaptiveCount++;
                Bitmap original = raster(icon), fitted;
                try { fitted = verify(icon); }
                catch (AssertionError error) { throw new AssertionError(pkg + ": " + error.getMessage(), error); }
                save(new File(folder, pkg + "-original.png"), original);
                save(new File(folder, pkg + "-circle.png"), fitted);
                try (FileOutputStream output = new FileOutputStream(new File(folder, pkg + "-type.txt"))) {
                    output.write(icon.getClass().getName().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            check(packages.size() >= 6, "Too few real installed icons checked");
            result.putString("stream", "Native icon rendering passed: " + packages.size() + " apps ("
                    + adaptiveCount + " adaptive), transparent custom images and " + checks + " assertions\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Icon rendering failed: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private Bitmap verify(Drawable icon) {
        Bitmap before = raster(icon), result = IconRenderer.circle(icon), after = raster(icon);
        check(before.sameAs(after), "Circular conversion mutated the original icon");
        check(Color.alpha(result.getPixel(0, 0)) == 0 && Color.alpha(result.getPixel(255, 255)) == 0,
                "Circle retained square corners");
        for (int angle = 0; angle < 360; angle += 5) {
            int x = Math.round(127.5f + 118 * (float) Math.cos(Math.toRadians(angle)));
            int y = Math.round(127.5f + 118 * (float) Math.sin(Math.toRadians(angle)));
            check(Color.alpha(result.getPixel(x, y)) == 255, "Circular background has a transparent gap");
        }
        return result;
    }

    private Bitmap raster(Drawable icon) {
        Bitmap bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Rect old = new Rect(icon.getBounds());
        icon.setBounds(0, 0, 256, 256);
        icon.draw(new Canvas(bitmap));
        icon.setBounds(old);
        return bitmap;
    }

    private void save(File file, Bitmap bitmap) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
    }

    private void check(boolean value, String reason) { checks++; if (!value) throw new AssertionError(reason); }
}
