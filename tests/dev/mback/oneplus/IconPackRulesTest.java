package dev.mback.oneplus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

public final class IconPackRulesTest {
    public static void main(String[] args) throws Exception {
        check("com.example/com.example.Main".equals(IconPackRules.component("ComponentInfo{com.example/.Main}")), "relative activity");
        check("com.example/com.example.Main$Child".equals(IconPackRules.component("com.example/com.example.Main$Child")), "nested activity");
        check(IconPackRules.component("../escape") == null, "invalid component");
        check(IconPackRules.component("ComponentInfo{com.example/.Main}/extra") == null, "trailing input");
        check(IconPackRules.safePath("res/drawable/icon.png"), "valid path");
        for (String path : new String[]{"../icon.png", "res/../icon.png", "/tmp/icon.png", "res\\icon.png", "res/./icon.png", "bad\0.png"})
            check(!IconPackRules.safePath(path), "unsafe path " + path);
        byte[] bytes = new byte[20000];
        Arrays.fill(bytes, (byte) 7);
        ByteArrayOutputStream copied = new ByteArrayOutputStream();
        check(IconPackRules.copy(new ByteArrayInputStream(bytes), copied, bytes.length) == bytes.length, "exact bound");
        check(Arrays.equals(bytes, copied.toByteArray()), "copy content");
        try {
            IconPackRules.copy(new ByteArrayInputStream(bytes), new ByteArrayOutputStream(), bytes.length - 1);
            throw new AssertionError("oversized archive accepted");
        } catch (IOException expected) { }
        System.out.println("IconPackRules: components, archive paths and streaming size limits passed");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
