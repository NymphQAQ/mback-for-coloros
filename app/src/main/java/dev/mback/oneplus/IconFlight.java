package dev.mback.oneplus;

/** A short sampled finger trail followed by a curved flight to each icon's stop. */
final class IconFlight {
    private final float[] xs = new float[24], ys = new float[24];
    private int count;

    void reset(float x, float y) { count = 0; add(x, y); }

    void add(float x, float y) {
        if (count > 0 && Math.hypot(x - xs[count - 1], y - ys[count - 1]) < 1) return;
        if (count == xs.length) {
            // Retain the actual origin while keeping the latest part of the stroke.
            System.arraycopy(xs, 2, xs, 1, count - 2);
            System.arraycopy(ys, 2, ys, 1, count - 2);
            count--;
        }
        xs[count] = x; ys[count] = y; count++;
    }

    void point(float progress, float targetX, float targetY, float[] result) {
        float t = Math.max(0, Math.min(1, progress));
        if (count == 0) { result[0] = targetX; result[1] = targetY; return; }
        if (t < .35f && count > 1) {
            float index = t / .35f * (count - 1);
            int i = Math.min(count - 2, (int) index);
            float f = index - i;
            result[0] = xs[i] + (xs[i + 1] - xs[i]) * f;
            result[1] = ys[i] + (ys[i + 1] - ys[i]) * f;
        } else {
            float f = Math.max(0, (t - .35f) / .65f), rest = 1 - f;
            float x = xs[count - 1], y = ys[count - 1];
            int prev = Math.max(0, count - 2);
            float cx = x + (targetX - x) * .5f + (x - xs[prev]) * 2;
            float cy = y + (targetY - y) * .5f + (y - ys[prev]) * 2;
            result[0] = rest * rest * x + 2 * rest * f * cx + f * f * targetX;
            result[1] = rest * rest * y + 2 * rest * f * cy + f * f * targetY;
        }
    }
}
