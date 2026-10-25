package dev.mback.oneplus;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

public final class IconLayoutTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        int[] blank = new int[256 * 256];
        Arrays.fill(blank, 0xff0066ff);
        IconLayout empty = new IconLayout(blank, 256, false);
        check(empty.scale == 1f && empty.x == 0f && empty.y == 0f);
        int[] asymmetric = blank.clone();
        for (int y = 10; y < 200; y++) for (int x = 2; x < 90; x++) asymmetric[y * 256 + x] = -1;
        verify(asymmetric, 256);
        check(blank[0] == 0xff0066ff);
        int[] translucent = asymmetric.clone();
        for (int i = 0; i < translucent.length; i++) if (translucent[i] == blank[0]) translucent[i] = 0xfd0066ff;
        check((verify(translucent, 256).background >>> 24) == 255);
        for (int color : new int[]{0xff000000, 0xffffffff, 0xff127af1}) {
            int[] transparent = new int[256 * 256];
            for (int y = 40; y < 160; y++) for (int x = 0; x < 100; x++) transparent[y * 256 + x] = color;
            IconLayout fitted = verify(transparent, 256, false, false);
            check(fitted.background != color && fitted.background != -1);
            verify(transparent, 256, true, false);
        }
        int[] paddedTile = new int[256 * 256];
        for (int y = 20; y < 236; y++) for (int x = 20; x < 236; x++) paddedTile[y * 256 + x] = -1;
        for (int y = 90; y < 150; y++) for (int x = 100; x < 156; x++) paddedTile[y * 256 + x] = 0xff1234ef;
        IconLayout tile = new IconLayout(paddedTile, 256, false);
        check(tile.background == -1 && tile.scale > 2f);
        int[] bubble = new int[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
            if (Math.hypot(x - 127.5, y - 127.5) < 100) bubble[y * 256 + x] = -1;
        for (int y = 120; y < 135; y++) for (int x = 100; x < 115; x++) bubble[y * 256 + x] = 0xff000000;
        check(verify(bubble, 256, false, false).background != -1);
        int[] photograph = new int[256 * 256];
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++)
            photograph[y * 256 + x] = 0xff000000 | (x << 16) | (y << 8) | ((x + y) & 255);
        verify(photograph, 256, false, false);
        for (String path : args) {
            BufferedImage source = ImageIO.read(new File(path));
            check(source != null && source.getWidth() == source.getHeight());
            int size = source.getWidth();
            int[] pixels = source.getRGB(0, 0, size, size, null, 0, size);
            IconLayout fit = verify(pixels, size);
            // Used by the asset check/preview to exercise the production geometry.
            System.out.println(new File(path).getName() + " " + fit.scale + " " + fit.x + " " + fit.y);
        }
        System.out.println("IconLayoutTest passed (" + checks + " assertions)");
    }

    private static IconLayout verify(int[] pixels, int size) {
        return verify(pixels, size, false, true);
    }

    private static IconLayout verify(int[] pixels, int size, boolean layer, boolean ignoreBackground) {
        int[] original = pixels.clone();
        IconLayout fit = new IconLayout(pixels, size, layer);
        check(Arrays.equals(pixels, original));
        check(fit.scale > 0 && Float.isFinite(fit.scale));
        float left = size, top = size, right = 0, bottom = 0;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int pixel = pixels[y * size + x];
            if ((pixel >>> 24) == 0 || ignoreBackground && pixel == pixels[0]) continue;
            float cx = (x + .5f) * fit.scale + fit.x, cy = (y + .5f) * fit.scale + fit.y;
            check(Math.hypot(cx - size * .5f, cy - size * .5f) + fit.scale * .7072 <= size * .38f + .001f);
            left = Math.min(left, cx); right = Math.max(right, cx);
            top = Math.min(top, cy); bottom = Math.max(bottom, cy);
        }
        check(Math.abs((left + right) * .5f - size * .5f) < .001f);
        check(Math.abs((top + bottom) * .5f - size * .5f) < .001f);
        return fit;
    }

    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("check " + checks);
    }
}
