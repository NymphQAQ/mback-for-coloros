package dev.mback.oneplus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;

final class ShortcutIcons {
    static final String AUTHORITY = "dev.mback.oneplus.shortcuts";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final LruCache<Object, Bitmap> circles = new LruCache<>(48);

    static void clearCache() { circles.evictAll(); }

    static Drawable application(Context context, String pkg, Drawable fallback) {
        try {
            String key = IconPackStore.icon(context, pkg, null);
            if (validKey(key)) {
                Drawable packed = image(context, key);
                if (packed != null) return display(context, packed);
            }
        } catch (Exception ignored) { /* A missing pack image falls back to the application. */ }
        return display(context, fallback);
    }

    static Drawable image(Context context, String key) throws Exception {
        Uri uri = URI.buildUpon().appendPath("icons").appendPath(key).build();
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(stream);
            return bitmap == null ? null : new BitmapDrawable(context.getResources(), bitmap);
        }
    }

    static Drawable display(Context context, Drawable source) {
        return useOriginal(context) ? source : circular(context, source, source.getConstantState());
    }

    private static Drawable circular(Context context, Drawable source, Object key) {
        Bitmap bitmap = key == null ? null : circles.get(key);
        if (bitmap == null) {
            bitmap = IconRenderer.circle(source);
            if (key != null) circles.put(key, bitmap);
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    static boolean validKey(String key) { return key != null && key.matches("[0-9a-f]{64}"); }

    static File file(Context context, String key) {
        if (!validKey(key)) throw new IllegalArgumentException("Invalid icon key");
        return new File(new File(context.getFilesDir(), "shortcut-icons"), key + ".png");
    }

    static Drawable load(Context context, ShortcutStore.Item item) throws Exception {
        return load(context, item, useOriginal(context));
    }

    static boolean useOriginal(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), ArcConfig.ORIGINAL_ICON_KEY, 0) == 1;
    }

    static Drawable load(Context context, ShortcutStore.Item item, boolean original) throws Exception {
        PresetShortcut preset = PresetShortcut.find(item.target);
        if (validKey(item.icon)) {
            Uri uri = URI.buildUpon().appendPath("icons").appendPath(item.icon).build();
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(stream);
                if (bitmap != null) {
                    Drawable icon = new BitmapDrawable(context.getResources(), bitmap);
                    if (original && preset == null) return icon;
                    Drawable shaped = original ? new BitmapDrawable(context.getResources(), PresetShortcut.shape(icon, true))
                            : circular(context, icon, item.icon);
                    bitmap.recycle();
                    return shaped;
                }
            } catch (Exception ignored) { /* A removed image falls back to its default icon. */ }
        }
        if (preset != null) return preset.icon(context, original);
        String packed = IconPackStore.icon(context, item.target.packageName, item.target.activityName);
        if (validKey(packed)) {
            try {
                Drawable icon = image(context, packed);
                if (icon != null) return original ? icon : circular(context, icon, packed);
            } catch (Exception ignored) { /* Missing pack entries use their application icon. */ }
        }
        Drawable icon = context.getPackageManager().getApplicationIcon(item.target.packageName);
        return original ? icon : circular(context, icon, icon.getConstantState());
    }

    static String importImage(Context context, Uri uri) throws Exception {
        File input = File.createTempFile("icon-input-", ".image", context.getCacheDir());
        try {
            try (InputStream source = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(input)) {
                if (source == null) throw new IOException("无法读取图片");
                IconPackRules.copy(source, output, 16L * 1024 * 1024);
            }
            Bitmap decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(input),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    if (width <= 0 || height <= 0 || width > 16384 || height > 16384) throw new IllegalArgumentException("图片尺寸过大");
                    float scale = Math.min(1f, 256f / Math.max(width, height));
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            try { return save(context, decoded); }
            finally { decoded.recycle(); }
        } finally { input.delete(); }
    }

    static String save(Context context, Bitmap decoded) throws Exception {
        Bitmap square = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(square);
        float scale = Math.min(256f / decoded.getWidth(), 256f / decoded.getHeight());
        float width = decoded.getWidth() * scale, height = decoded.getHeight() * scale;
        canvas.drawBitmap(decoded, null, new android.graphics.RectF((256 - width) / 2f, (256 - height) / 2f,
                (256 + width) / 2f, (256 + height) / 2f), new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        square.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        square.recycle();
        byte[] data = bytes.toByteArray();
        StringBuilder key = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(data)) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        synchronized (ShortcutIcons.class) {
            File destination = file(context, key.toString());
            File directory = destination.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create icon directory");
            if (!destination.exists()) {
                File pending = File.createTempFile("import-", ".png", directory);
                try {
                    try (FileOutputStream output = new FileOutputStream(pending)) { output.write(data); output.getFD().sync(); }
                    if (!pending.renameTo(destination)) throw new java.io.IOException("Cannot save icon");
                } finally { pending.delete(); }
            }
        }
        return key.toString();
    }

    private ShortcutIcons() {}
}
