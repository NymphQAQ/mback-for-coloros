package dev.mback.oneplus;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.TypedValue;
import android.util.Xml;

import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports appfilter icon packs as data; no APK installation or code loading. */
final class IconPackStore {
    static final String MAP_KEY = "mback_icon_pack_map";
    static final String REVISION_KEY = "mback_icon_assets_revision";
    private static String cachedRaw;
    private static JSONObject cachedItems = new JSONObject();

    static synchronized String icon(Context context, String pkg, String activity) {
        String raw = Settings.Global.getString(context.getContentResolver(), MAP_KEY);
        if (!java.util.Objects.equals(raw, cachedRaw)) {
            cachedRaw = raw;
            try { cachedItems = new JSONObject(raw == null ? "{}" : raw).optJSONObject("items"); }
            catch (Exception ignored) { cachedItems = null; }
        }
        if (cachedItems == null) return "";
        String exact = activity == null ? "" : cachedItems.optString(pkg + "/" + activity);
        return ShortcutIcons.validKey(exact) ? exact : cachedItems.optString(pkg);
    }

    static String activeName(Context context) {
        try { return new JSONObject(Settings.Global.getString(context.getContentResolver(), MAP_KEY)).optString("name", "原应用图标"); }
        catch (Exception ignored) { return "原应用图标"; }
    }

    static ArrayList<JSONObject> list(Context context) {
        synchronized (ShortcutIcons.class) {
            ArrayList<JSONObject> packs = new ArrayList<>();
            File[] files = directory(context).listFiles();
            if (files != null) for (File file : files) {
                if (!file.getName().matches("[0-9a-f]{64}\\.json")) continue;
                try { JSONObject pack = read(file); if (validPack(pack)) packs.add(pack); }
                catch (Exception ignored) { /* An interrupted or invalid pack never hides other packs. */ }
            }
            Collections.sort(packs, (a, b) -> a.optString("name").compareToIgnoreCase(b.optString("name")));
            return packs;
        }
    }

    static boolean validPack(JSONObject pack) {
        if (pack == null || !ShortcutIcons.validKey(pack.optString("id"))
                || pack.optString("name").length() > 160 || pack.optJSONObject("items") == null) return false;
        JSONObject items = pack.optJSONObject("items");
        if (items.length() > IconPackRules.MAX_ENTRIES) return false;
        for (Iterator<String> it = items.keys(); it.hasNext();) {
            String target = it.next();
            if (!target.equals(IconPackRules.component(target)) || !ShortcutIcons.validKey(items.optString(target))) return false;
        }
        return true;
    }

    static JSONObject importPack(Context context, Uri uri, String name) throws Exception {
        File archive = File.createTempFile("icon-pack-", ".apk", context.getCacheDir());
        try {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 FileOutputStream output = new FileOutputStream(archive)) {
                if (input == null) throw new java.io.IOException("无法读取图标包");
                IconPackRules.copy(input, output, IconPackRules.MAX_ARCHIVE_BYTES);
            }
            String id;
            try (InputStream input = new FileInputStream(archive)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
                StringBuilder hash = new StringBuilder();
                for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                id = hash.toString();
            }
            // A restore must not replace images while their pack is being assembled.
            synchronized (ShortcutIcons.class) {
            try (ZipFile zip = new ZipFile(archive)) {
                if (zip.size() > IconPackRules.MAX_ENTRIES) throw new java.io.IOException("图标包文件过多（最多 50000 项）");
                HashMap<String, String> images = new HashMap<>();
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!IconPackRules.safePath(entry.getName())) throw new java.io.IOException("图标包包含无效路径");
                    String path = entry.getName();
                    if (path.matches(".*\\.(png|webp|jpg|jpeg)$")) {
                        String base = path.substring(path.lastIndexOf('/') + 1, path.lastIndexOf('.'));
                        images.putIfAbsent(base, path);
                    }
                }
                PackageInfo info = context.getPackageManager().getPackageArchiveInfo(archive.getPath(), 0);
                Resources resources = null;
                if (info != null && info.applicationInfo != null) {
                    info.applicationInfo.sourceDir = archive.getPath();
                    info.applicationInfo.publicSourceDir = archive.getPath();
                    resources = context.getPackageManager().getResourcesForApplication(info.applicationInfo);
                    try { name = info.applicationInfo.loadLabel(context.getPackageManager()).toString(); }
                    catch (Exception ignored) { /* Use the file name if the archive has no label. */ }
                }
                LinkedHashMap<String, String> requested = new LinkedHashMap<>();
                ZipEntry appfilter = zip.getEntry("assets/appfilter.xml");
                if (appfilter == null) appfilter = zip.getEntry("appfilter.xml");
                if (appfilter != null) {
                    try (InputStream input = bounded(zip, appfilter, 4 * 1024 * 1024)) {
                        XmlPullParser parser = Xml.newPullParser();
                        parser.setInput(input, "UTF-8");
                        parse(parser, requested);
                    }
                } else if (resources != null) {
                    int xml = resources.getIdentifier("appfilter", "xml", info.packageName);
                    if (xml != 0) {
                        android.content.res.XmlResourceParser parser = resources.getXml(xml);
                        try { parse(parser, requested); } finally { parser.close(); }
                    }
                }
                if (requested.isEmpty()) throw new java.io.IOException("未找到有效的 appfilter.xml；请选择常见 APK 图标包或含 appfilter.xml 与图片的 ZIP");
                JSONObject items = new JSONObject();
                HashMap<String, String> imported = new HashMap<>();
                long outputBytes = 0;
                for (Map.Entry<String, String> request : requested.entrySet()) {
                    String drawable = request.getValue();
                    String key = imported.get(drawable);
                    if (!imported.containsKey(drawable)) {
                        if (imported.size() >= IconPackRules.MAX_ICONS) throw new java.io.IOException("图标数量过多（最多 8000 张）");
                        Bitmap bitmap = null;
                        try {
                            String path = images.get(drawable);
                            int resource = resources == null ? 0 : resources.getIdentifier(drawable, "drawable", info.packageName);
                            if (resource != 0) {
                                TypedValue value = new TypedValue();
                                resources.getValue(resource, value, true);
                                if (value.string != null && value.string.toString().matches(".*\\.(png|webp|jpg|jpeg)$")
                                        && zip.getEntry(value.string.toString()) != null) path = value.string.toString();
                            }
                            if (path != null) bitmap = bitmap(zip, path);
                            else if (resource != 0) {
                                Drawable icon = resources.getDrawableForDensity(resource, 160, null);
                                bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
                                icon.setBounds(0, 0, 256, 256);
                                icon.draw(new Canvas(bitmap));
                            }
                            if (bitmap != null) {
                                key = ShortcutIcons.save(context, bitmap);
                                outputBytes += ShortcutIcons.file(context, key).length();
                                if (outputBytes > 192L * 1024 * 1024) throw new java.io.IOException("解包后的图标超过 192 MB");
                            }
                        } catch (Resources.NotFoundException ignored) { /* An optional missing icon does not discard usable icons. */ }
                        finally { if (bitmap != null) bitmap.recycle(); }
                        imported.put(drawable, key);
                    }
                    if (key != null) items.put(request.getKey(), key);
                }
                if (items.length() == 0) throw new java.io.IOException("图标包中没有可读取的图标");
                // Desktop icons follow the pack's launcher component before other activities.
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo launcher : context.getPackageManager().queryIntentActivities(query, 0)) {
                    if (launcher.activityInfo == null) continue;
                    String pkg = launcher.activityInfo.packageName;
                    String key = items.optString(pkg + "/" + launcher.activityInfo.name);
                    if (ShortcutIcons.validKey(key)) items.put(pkg, key);
                }
                JSONObject pack = new JSONObject().put("id", id).put("name", cleanName(name)).put("items", items);
                byte[] metadata = pack.toString().getBytes(StandardCharsets.UTF_8);
                if (metadata.length > 2 * 1024 * 1024)
                    throw new java.io.IOException("图标包映射信息超过 2 MB，请使用精简图标包");
                atomic(directory(context), id + ".json", metadata);
                return pack;
            }
            }
        } finally { archive.delete(); }
    }

    private static void parse(XmlPullParser parser, LinkedHashMap<String, String> items) throws Exception {
        int events = 0;
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (++events > 250000) throw new java.io.IOException("图标映射文件过大");
            if (parser.getEventType() != XmlPullParser.START_TAG || !"item".equals(parser.getName())) continue;
            String component = IconPackRules.component(parser.getAttributeValue(null, "component"));
            String drawable = parser.getAttributeValue(null, "drawable");
            if (component == null || drawable == null || !drawable.matches("[A-Za-z0-9_]{1,160}")) continue;
            items.putIfAbsent(component, drawable);
            if (items.size() > IconPackRules.MAX_ENTRIES) throw new java.io.IOException("图标映射过多");
        }
    }

    private static InputStream bounded(ZipFile zip, ZipEntry entry, int limit) throws Exception {
        if (entry.getSize() > limit) throw new java.io.IOException("图标包内部文件过大");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(entry)) { IconPackRules.copy(input, output, limit); }
        return new java.io.ByteArrayInputStream(output.toByteArray());
    }

    private static Bitmap bitmap(ZipFile zip, String path) throws Exception {
        byte[] bytes;
        try (InputStream input = bounded(zip, zip.getEntry(path), 8 * 1024 * 1024)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            IconPackRules.copy(input, output, 8 * 1024 * 1024);
            bytes = output.toByteArray();
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 16384 || options.outHeight > 16384)
            throw new java.io.IOException("图标尺寸无效或过大");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    static void activate(Context context, JSONObject pack) throws Exception {
        synchronized (ShortcutIcons.class) {
            JSONObject active = new JSONObject();
            if (pack != null) {
                if (!validPack(pack)) throw new java.io.IOException("图标包数据无效");
                HashSet<String> installed = new HashSet<>();
                for (ApplicationInfo app : context.getPackageManager().getInstalledApplications(0)) installed.add(app.packageName);
                JSONObject filtered = new JSONObject(), source = pack.getJSONObject("items");
                for (Iterator<String> it = source.keys(); it.hasNext();) {
                    String target = it.next();
                    if (installed.contains(target.split("/", 2)[0])) filtered.put(target, source.getString(target));
                }
                // Rebuild launcher aliases when switching, so newly installed apps also match.
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo launcher : context.getPackageManager().queryIntentActivities(query, 0)) {
                    if (launcher.activityInfo == null) continue;
                    String pkg = launcher.activityInfo.packageName;
                    String key = filtered.optString(pkg + "/" + launcher.activityInfo.name);
                    if (ShortcutIcons.validKey(key)) filtered.put(pkg, key);
                }
                active.put("id", pack.getString("id")).put("name", pack.getString("name")).put("items", filtered);
            }
            String raw = active.toString();
            if (raw.getBytes(StandardCharsets.UTF_8).length > IconPackRules.MAX_MAP_BYTES)
                throw new java.io.IOException("本机匹配的图标映射超过 96 KB，请使用精简图标包");
            writeSetting(MAP_KEY, raw);
            ShortcutIcons.clearCache();
            bumpRevision(context);
        }
    }

    static void remove(Context context, JSONObject pack) throws Exception {
        synchronized (ShortcutIcons.class) {
            String id = pack.optString("id");
            if (!ShortcutIcons.validKey(id)) throw new java.io.IOException("无效图标包");
            String raw = Settings.Global.getString(context.getContentResolver(), MAP_KEY);
            if (raw != null && id.equals(new JSONObject(raw).optString("id"))) activate(context, null);
            File file = new File(directory(context), id + ".json");
            if (file.exists() && !file.delete()) throw new java.io.IOException("无法删除图标包");
        }
    }

    static void bumpRevision(Context context) throws Exception { writeSetting(REVISION_KEY, Long.toString(System.currentTimeMillis())); }

    private static void writeSetting(String key, String value) throws Exception {
        Process process = new ProcessBuilder("su", "-c", "settings put global " + key + " " + ShortcutStore.quote(value))
                .redirectErrorStream(true).start();
        try (InputStream output = process.getInputStream()) { while (output.read() != -1) { } }
        if (process.waitFor() != 0) throw new java.io.IOException("保存失败，请检查 Root 授权");
    }

    static File directory(Context context) { return new File(context.getFilesDir(), "icon-packs"); }

    static JSONObject read(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new FileInputStream(file)) { IconPackRules.copy(input, output, 2 * 1024 * 1024); }
        return new JSONObject(output.toString("UTF-8"));
    }

    private static String cleanName(String name) {
        String value = name == null ? "图标包" : name.replaceAll("[\\p{Cntrl}]", "").trim();
        return value.isEmpty() ? "图标包" : value.substring(0, Math.min(value.length(), 160));
    }

    private static void atomic(File directory, String name, byte[] data) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建图标包目录");
        File pending = File.createTempFile("pack-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(pending)) { output.write(data); output.getFD().sync(); }
            if (!pending.renameTo(new File(directory, name))) throw new java.io.IOException("无法保存图标包");
        } finally { pending.delete(); }
    }

    private IconPackStore() {}
}
