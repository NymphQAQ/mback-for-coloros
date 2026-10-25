package dev.mback.oneplus;

import java.util.HashMap;

/** Fits complete artwork, using separate adaptive layers or a flat bitmap background. */
final class IconLayout {
    final int background;
    final float scale, x, y;
    private final boolean flat, layer;
    private final int flatColor;

    IconLayout(int[] pixels, int size, boolean foregroundLayer) {
        layer = foregroundLayer;
        int contentLeft = size, contentTop = size, contentRight = -1, contentBottom = -1, opaque = 0;
        for (int i = 0; i < pixels.length; i++) {
            int alpha = pixels[i] >>> 24;
            if (alpha < 250) continue;
            contentLeft = Math.min(contentLeft, i % size); contentRight = Math.max(contentRight, i % size);
            contentTop = Math.min(contentTop, i / size); contentBottom = Math.max(contentBottom, i / size);
            opaque++;
        }
        HashMap<Integer, Integer> colors = new HashMap<>();
        int samples = 0, common = 0, color = 0xff394655;
        int width = Math.max(0, contentRight - contentLeft + 1), height = Math.max(0, contentBottom - contentTop + 1);
        for (int i = 0; i < (width + height) * 2; i++) {
            int px = i < width * 2 ? contentLeft + i % width : (i - width * 2 < height ? contentLeft : contentRight);
            int py = i < width * 2 ? (i < width ? contentTop : contentBottom) : contentTop + (i - width * 2) % height;
            int pixel = pixels[py * size + px];
            if ((pixel >>> 24) < 250) continue;
            int count = colors.getOrDefault(pixel, 0) + 1;
            colors.put(pixel, count);
            samples++;
            if (count > common) { common = count; color = pixel; }
        }
        boolean flatEdge = !layer && samples > 0 && common >= samples * .85f && opaque >= width * height * .9f;
        boolean transparent = false, onlyBackground = true;
        for (int pixel : pixels) {
            if ((pixel >>> 24) == 0) transparent = true;
            else if (pixel != color) onlyBackground = false;
        }
        boolean monochrome = flatEdge && transparent && onlyBackground;
        flat = flatEdge && !monochrome;
        if (samples == 0) {
            for (int i = 0; i < pixels.length; i += 17) {
                int pixel = pixels[i];
                if ((pixel >>> 24) < 128) continue;
                int count = colors.getOrDefault(pixel, 0) + 1;
                colors.put(pixel, count);
                if (count > common) { common = count; color = pixel; }
            }
        }
        if (samples == 0 || monochrome || !flat && opaque < width * height * .9f) {
            int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
            // Transparent monochrome artwork needs contrast rather than a matching invisible disk.
            color = Math.max(r, Math.max(g, b)) < 80 ? 0xff60758b
                    : 0xff000000 | ((int) (r * .32f) << 16) | ((int) (g * .32f) << 8) | (int) (b * .32f);
        }
        flatColor = color;
        background = color | 0xff000000;
        int left = size, top = size, right = -1, bottom = -1;
        for (int i = 0; i < pixels.length; i++) {
            if (!foreground(pixels[i])) continue;
            int px = i % size, py = i / size;
            left = Math.min(left, px); right = Math.max(right, px);
            top = Math.min(top, py); bottom = Math.max(bottom, py);
        }
        if (right < left) {
            scale = 1f; x = y = 0f;
            return;
        }
        float cx = (left + right + 1) * .5f, cy = (top + bottom + 1) * .5f;
        float radiusSquared = 0f;
        for (int py = top; py <= bottom; py++) for (int px = left; px <= right; px++) {
            if (!foreground(pixels[py * size + px])) continue;
            float dx = px + .5f - cx, dy = py + .5f - cy;
            radiusSquared = Math.max(radiusSquared, dx * dx + dy * dy);
        }
        scale = Math.min(size * .62f / Math.max(right - left + 1, bottom - top + 1),
                size * .38f / ((float) Math.sqrt(radiusSquared) + 1f));
        x = size * .5f - cx * scale;
        y = size * .5f - cy * scale;
    }

    private boolean foreground(int pixel) {
        return (pixel >>> 24) != 0 && (layer || !flat || pixel != flatColor);
    }
}
