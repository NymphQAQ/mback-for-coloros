package dev.mback.oneplus;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reusable imported images and data-only icon pack management. */
public final class IconLibraryActivity extends Activity {
    static final String EXTRA_TAB = "mback_icon_tab";
    static final String EXTRA_PICK = "mback_pick_icon";
    static final String EXTRA_ICON_KEY = "mback_icon_key";
    private static final int PICK_IMAGE = 81, PICK_PACK = 82, PAGE_SIZE = 80;
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(64);
    private final ArrayList<File> icons = new ArrayList<>();
    private final ArrayList<JSONObject> packs = new ArrayList<>();
    private LinearLayout content;
    private TextView summary, pageLabel;
    private Button add, previous, next;
    private final Button[] tabs = new Button[2];
    private GridView grid;
    private int tab, page;
    private boolean pick, busy;
    private int previousOrientation;
    private boolean orientationLocked;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        tab = state == null ? getIntent().getIntExtra(EXTRA_TAB, 0) : state.getInt("tab");
        if (pick) tab = 0;
        page = state == null ? 0 : state.getInt("page");
        LinearLayout root = AppUi.screen(this);
        root.addView(AppUi.header(this, pick ? "选择图标" : "图标资源", this::finish, null));
        LinearLayout body = AppUi.content(this);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!pick) {
            LinearLayout navigation = new LinearLayout(this);
            navigation.setPadding(dp(4), dp(4), dp(4), dp(4));
            navigation.setBackground(AppUi.cardBackground(this));
            String[] labels = {"图标库", "图标包"};
            for (int i = 0; i < labels.length; i++) {
                final int target = i;
                tabs[i] = button(labels[i], () -> switchTab(target));
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(48), 1);
                layout.setMargins(dp(2), 0, dp(2), 0);
                navigation.addView(tabs[i], layout);
            }
            body.addView(navigation);
        }
        summary = text("正在加载…", 13, AppUi.MUTED);
        summary.setPadding(dp(3), dp(16), dp(3), dp(16));
        summary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(summary);
        add = AppUi.button(this, "导入", true);
        add.setOnClickListener(v -> choose(tab == 1 ? PICK_PACK : PICK_IMAGE));
        LinearLayout.LayoutParams importLayout = new LinearLayout.LayoutParams(-1, dp(48));
        importLayout.bottomMargin = dp(16);
        body.addView(add, importLayout);
        content = column();
        body.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        busy = true;
        add.setEnabled(false);
        reload();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", tab); state.putInt("page", page);
        super.onSaveInstanceState(state);
    }

    private void switchTab(int value) {
        if (busy) return;
        tab = value; page = 0; render();
    }

    private void reload() {
        if (work.isShutdown()) return;
        work.execute(() -> {
            ArrayList<File> files = new ArrayList<>();
            ArrayList<JSONObject> saved;
            synchronized (ShortcutIcons.class) {
                File[] stored = new File(getFilesDir(), "shortcut-icons").listFiles();
                if (stored != null) for (File file : stored)
                    if (file.isFile() && file.getName().matches("[0-9a-f]{64}\\.png")) files.add(file);
                Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                saved = IconPackStore.list(this);
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                icons.clear(); icons.addAll(files); packs.clear(); packs.addAll(saved);
                busy = false; render(); unlockOrientation();
            });
        });
    }

    private void render() {
        content.removeAllViews();
        for (int i = 0; i < tabs.length; i++) if (tabs[i] != null) {
            tabs[i].setSelected(i == tab);
            tabs[i].setTextColor(i == tab ? AppUi.INK : AppUi.MUTED);
            tabs[i].setBackground(AppUi.ripple(this, i == tab ? AppUi.PALE : Color.WHITE, 16));
            tabs[i].setEnabled(!busy);
        }
        add.setEnabled(!busy);
        add.setText(tab == 1 ? "导入 APK / ZIP 图标包" : "添加图片到图标库");
        if (tab == 1) renderPacks(); else renderLibrary();
    }

    private void renderLibrary() {
        summary.setText("图标库 · " + icons.size() + " 张\n导入过的图片和图标包图标都会保留，可重复用于应用或活动。");
        grid = new GridView(this);
        grid.setNumColumns(Math.max(2, Math.min(4, (getResources().getConfiguration().screenWidthDp - 64) / 64)));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(6));
        grid.setClipToPadding(false);
        grid.setPadding(dp(12), dp(12), dp(12), dp(12));
        grid.setBackground(AppUi.cardBackground(this));
        content.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        if (icons.isEmpty()) {
            grid.setVisibility(View.GONE);
            TextView empty = text("还没有保存的图标\n\n点击上方按钮添加图片，下次修改图标时可再次选用。", 14, AppUi.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(24), dp(24), dp(24));
            empty.setBackground(AppUi.cardBackground(this));
            content.addView(empty, 0, new LinearLayout.LayoutParams(-1, 0, 1));
        }
        page = Math.max(0, Math.min(page, Math.max(0, (icons.size() - 1) / PAGE_SIZE)));
        grid.setAdapter(new IconAdapter());
        grid.setOnItemClickListener((parent, view, position, id) -> {
            String key = key(icons.get(page * PAGE_SIZE + position));
            if (pick) { setResult(RESULT_OK, new Intent().putExtra(EXTRA_ICON_KEY, key)); finish(); }
            else AppUi.show(new AlertDialog.Builder(this).setTitle("已保存在图标库")
                    .setMessage("在“更多应用”中点击图标旁的编辑按钮，选择“从图标库选择”，即可重复使用这张图标。")
                    .setPositiveButton("知道了", null));
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (busy || pick) return true;
            File file = icons.get(page * PAGE_SIZE + position);
            AppUi.show(new AlertDialog.Builder(this).setTitle("删除这张图标？")
                    .setMessage("正在被快捷项或图标包引用的图标无法删除。")
                    .setNegativeButton("取消", null).setPositiveButton("删除", (dialog, which) -> removeIcon(file)));
            return true;
        });
        LinearLayout pages = new LinearLayout(this);
        previous = button("上一页", () -> { if (page > 0) { page--; render(); } });
        next = button("下一页", () -> { if ((page + 1) * PAGE_SIZE < icons.size()) { page++; render(); } });
        previous.setEnabled(page > 0);
        next.setEnabled((page + 1) * PAGE_SIZE < icons.size());
        pageLabel = text((page + 1) + " / " + Math.max(1, (icons.size() + PAGE_SIZE - 1) / PAGE_SIZE), 13, MainActivity.MUTED);
        pageLabel.setGravity(Gravity.CENTER);
        pages.setPadding(0, dp(12), 0, 0);
        pages.addView(previous, new LinearLayout.LayoutParams(0, dp(48), 1));
        pages.addView(pageLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        pages.addView(next, new LinearLayout.LayoutParams(0, dp(48), 1));
        content.addView(pages);
    }

    private void renderPacks() {
        String active = IconPackStore.activeName(this);
        summary.setText("当前：" + active + "\n未匹配的应用保留原图，单独设置的图标优先。");
        LinearLayout.LayoutParams originalLayout = new LinearLayout.LayoutParams(-1, dp(48));
        originalLayout.bottomMargin = dp(12);
        content.addView(button("使用原应用图标", () -> action(() -> IconPackStore.activate(this, null), "已切换为原应用图标")), originalLayout);
        ListView list = new ListView(this);
        list.setBackground(AppUi.cardBackground(this));
        list.setPadding(dp(18), dp(4), dp(18), dp(4));
        list.setDivider(new android.graphics.drawable.ColorDrawable(AppUi.BORDER));
        list.setDividerHeight(dp(1));
        list.setSelector(AppUi.ripple(this, Color.WHITE, 12));
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return packs.size(); }
            @Override public Object getItem(int position) { return packs.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View view, ViewGroup parent) {
                LinearLayout row;
                if (view instanceof LinearLayout) row = (LinearLayout) view;
                else {
                    row = column();
                    row.setMinimumHeight(dp(76));
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(0, dp(14), 0, dp(14));
                    row.addView(AppUi.text(IconLibraryActivity.this, "", 15, AppUi.INK, true));
                    TextView detail = text("", 12, AppUi.MUTED);
                    detail.setPadding(0, dp(5), 0, 0);
                    row.addView(detail);
                }
                JSONObject pack = packs.get(position);
                ((TextView) row.getChildAt(0)).setText(pack.optString("name")
                        + (pack.optString("name").equals(active) ? "  · 使用中" : ""));
                ((TextView) row.getChildAt(1)).setText(pack.optJSONObject("items").length()
                        + " 个映射 · 点击切换 · 长按移除");
                return row;
            }
        });
        list.setOnItemClickListener((parent, view, position, id) -> {
            JSONObject pack = packs.get(position);
            action(() -> IconPackStore.activate(this, pack), "已应用“" + pack.optString("name") + "”");
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (busy) return true;
            JSONObject pack = packs.get(position);
            AppUi.show(new AlertDialog.Builder(this).setTitle("移除“" + pack.optString("name") + "”？")
                    .setMessage("已保存的图标图片会留在图标库。当前正在使用时会切回原应用图标。")
                    .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) ->
                            action(() -> IconPackStore.remove(this, pack), "已移除图标包")));
            return true;
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        if (packs.isEmpty()) {
            list.setVisibility(View.GONE);
            TextView empty = text("尚未导入图标包\n\n导入后点击名称切换，APK 无需安装。", 14, AppUi.MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(24), dp(24), dp(24));
            empty.setBackground(AppUi.cardBackground(this));
            content.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    private final class IconAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.min(PAGE_SIZE, icons.size() - page * PAGE_SIZE); }
        @Override public Object getItem(int position) { return icons.get(page * PAGE_SIZE + position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View view, ViewGroup parent) {
            ImageView image = view instanceof ImageView ? (ImageView) view : new ImageView(IconLibraryActivity.this);
            image.setLayoutParams(new android.widget.AbsListView.LayoutParams(-1, dp(76)));
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(9), dp(9), dp(9), dp(9));
            image.setBackground(AppUi.ripple(IconLibraryActivity.this, AppUi.BACKGROUND, 16));
            File file = (File) getItem(position);
            String key = key(file);
            image.setTag(key);
            image.setContentDescription("图标 " + key.substring(0, 8));
            Bitmap cached = thumbs.get(key);
            image.setImageBitmap(cached);
            if (cached == null && !work.isShutdown()) work.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getPath(), options);
                if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 16384 || options.outHeight > 16384) return;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 128) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
                if (bitmap == null) return;
                thumbs.put(key, bitmap);
                runOnUiThread(() -> { if (!isDestroyed() && key.equals(image.getTag())) image.setImageBitmap(bitmap); });
            });
            return image;
        }
    }

    private void choose(int request) {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(request == PICK_IMAGE ? "image/*" : "*/*").putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try { startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException error) { toast("没有可用的文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_IMAGE) {
            page = 0;
            action(() -> ShortcutIcons.importImage(this, data.getData()), "已保存到图标库");
        } else if (request == PICK_PACK) {
            action(() -> IconPackStore.importPack(this, data.getData(), displayName(data.getData())), "图标包已导入，点击名称即可切换");
        }
    }

    private String displayName(android.net.Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) { }
        return "图标包";
    }

    private interface Job { void run() throws Exception; }
    private void action(Job job, String success) {
        if (busy) return;
        busy = true;
        previousOrientation = getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        orientationLocked = true;
        add.setEnabled(false);
        summary.setText("正在处理，请稍候…");
        work.execute(() -> {
            String error = null;
            try { job.run(); }
            catch (Exception failure) {
                android.util.Log.w("MBack", "Cannot update icon assets", failure);
                error = failure.getMessage() == null ? "操作失败，请重试" : failure.getMessage();
            }
            String message = error == null ? success : error;
            runOnUiThread(() -> { if (!isDestroyed()) toast(message); });
            reload();
        });
    }

    private void removeIcon(File file) {
        action(() -> {
            synchronized (ShortcutIcons.class) {
                String key = key(file);
                for (int ring = 0; ring < 3; ring++) for (ShortcutStore.Item item : ShortcutStore.read(this, ring))
                    if (key.equals(item.icon)) throw new java.io.IOException("这张图标正在被快捷项使用，请先更换快捷项图标");
                String activeRaw = android.provider.Settings.Global.getString(getContentResolver(), IconPackStore.MAP_KEY);
                if (activeRaw != null) {
                    JSONObject active = new JSONObject(activeRaw).optJSONObject("items");
                    if (active != null) for (java.util.Iterator<String> it = active.keys(); it.hasNext();)
                        if (key.equals(active.optString(it.next()))) throw new java.io.IOException("这张图标正在被当前图标包使用，请先切换为原应用图标");
                }
                for (JSONObject pack : IconPackStore.list(this)) {
                    JSONObject items = pack.getJSONObject("items");
                    for (java.util.Iterator<String> it = items.keys(); it.hasNext();)
                        if (key.equals(items.optString(it.next()))) throw new java.io.IOException("这张图标属于已保存的图标包，请先移除对应图标包");
                }
                if (file.exists() && !file.delete()) throw new java.io.IOException("无法删除图标");
                thumbs.remove(key);
            }
        }, "图标已删除");
    }

    private void unlockOrientation() {
        if (orientationLocked) {
            orientationLocked = false;
            setRequestedOrientation(previousOrientation);
        }
    }

    @Override protected void onDestroy() { work.shutdown(); super.onDestroy(); }
    private String key(File file) { return file.getName().substring(0, 64); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private LinearLayout column() { return AppUi.column(this); }
    private TextView text(String label, int size, int color) { return AppUi.text(this, label, size, color, false); }
    private Button button(String title, Runnable clicked) { Button view = AppUi.button(this, title, false); view.setOnClickListener(v -> clicked.run()); return view; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
