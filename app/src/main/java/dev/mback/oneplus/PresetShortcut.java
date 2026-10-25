package dev.mback.oneplus;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import java.io.InputStream;

/** Component IDs also recognize shortcuts added through the activity picker. */
final class PresetShortcut {
    static final String TRANSIT_MODE_KEY = "mback_transit_direct";
    static final PresetShortcut[] ALL = {
        new PresetShortcut("微信扫一扫", "com.tencent.mm/com.tencent.mm.plugin.scanner.ui.BaseScanUI", "wechat-scan.png", null),
        new PresetShortcut("微信付款码", "com.tencent.mm/com.tencent.mm.plugin.offline.ui.WalletOfflineEntranceUI", "wechat-payment.png", null),
        new PresetShortcut("支付宝扫一扫", "com.eg.android.AlipayGphone/com.alipay.mobile.scan.as.main.MainCaptureActivity", "alipay-scan.png", null),
        new PresetShortcut("支付宝付款码", "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity", "alipay-payment.webp",
                "alipays://platformapi/startapp?appId=20000056"),
        new PresetShortcut("微信乘车码", "com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.WxTrafficCodeActivity", "wechat-transit.png", null),
        new PresetShortcut("支付宝乘车码", "com.coloros.assistantscreen/com.oplus.assistantscreen.flamingo.trafficcode.AliTrafficCodeActivity", "alipay-transit.webp", null)
    };

    final String label;
    final ShortcutTarget target;
    private final String asset, data;
    private Bitmap systemIcon, circleIcon;

    private PresetShortcut(String label, String id, String asset, String data) {
        this.label = label;
        this.target = ShortcutTarget.parse(id);
        this.asset = asset;
        this.data = data;
    }

    static PresetShortcut find(ShortcutTarget target) {
        for (PresetShortcut preset : ALL) if (preset.target.id().equals(target.id())) return preset;
        return null;
    }

    static boolean directTransit(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), TRANSIT_MODE_KEY, 0) == 1;
    }

    boolean isTransit() { return this == ALL[4] || this == ALL[5]; }

    ShortcutTarget launchTarget(boolean direct) {
        if (!direct || !isTransit()) return target;
        return ShortcutTarget.parse(this == ALL[4] ? WechatTransit.COMPONENT
                : "com.eg.android.AlipayGphone/com.alipay.mobile.quinox.SchemeLauncherActivity");
    }

    boolean available(Context context) {
        try {
            ActivityInfo info = ShortcutStore.activity(context, launchTarget(directTransit(context)));
            return info.enabled && info.applicationInfo.enabled;
        } catch (Exception error) { return false; }
    }

    Intent intent(boolean direct) {
        ShortcutTarget destination = launchTarget(direct);
        Intent intent = new Intent().setComponent(new ComponentName(destination.packageName, destination.activityName));
        String uri = direct && this == ALL[5]
                ? "alipays://platformapi/startapp?appId=200011235&chInfo=ch_oppo_left&sceneCode=KF_CHANGSHANG&partnerId=ch_oppo_left&pikshemo=YES"
                : data;
        if (uri != null) intent.setAction(Intent.ACTION_VIEW).setData(Uri.parse(uri));
        return intent;
    }

    Drawable icon(Context context) {
        return icon(context, ShortcutIcons.useOriginal(context));
    }

    synchronized Drawable icon(Context context, boolean original) {
        Bitmap bitmap = original ? systemIcon : circleIcon;
        if (bitmap == null) {
            bitmap = shape(rawIcon(context), original);
            if (original) systemIcon = bitmap;
            else circleIcon = bitmap;
        }
        return new BitmapDrawable(context.getResources(), bitmap);
    }

    static Bitmap shape(Drawable source, boolean original) {
        if (!original) return IconRenderer.circle(source);
        int size = 256;
        Bitmap artwork = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        source.setBounds(0, 0, size, size);
        source.draw(new Canvas(artwork));
        Path mask;
        AdaptiveIconDrawable system = new AdaptiveIconDrawable(
                new ColorDrawable(Color.BLACK), new ColorDrawable(Color.TRANSPARENT));
        system.setBounds(0, 0, size, size);
        mask = system.getIconMask();
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        result.setDensity(Bitmap.DENSITY_NONE);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(artwork, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        fill.setShader(shader);
        new Canvas(result).drawPath(mask, fill);
        artwork.recycle();
        return result;
    }

    private Drawable rawIcon(Context context) {
        // SystemUI's own AssetManager does not contain the module's bundled images.
        try (InputStream stream = context.getPackageManager().getResourcesForApplication("dev.mback.oneplus")
                .getAssets().open("preset-icons/" + asset)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream);
            if (bitmap != null) {
                // Prevent smaller WebP files from shrinking on high-density screens.
                bitmap.setDensity(Bitmap.DENSITY_NONE);
                return new BitmapDrawable(context.getResources(), bitmap);
            }
            throw new java.io.IOException("Cannot decode " + asset);
        } catch (Exception error) {
            Log.w("MBack", "Cannot load preset icon: " + asset, error);
            return context.getPackageManager().getDefaultActivityIcon();
        }
    }
}
