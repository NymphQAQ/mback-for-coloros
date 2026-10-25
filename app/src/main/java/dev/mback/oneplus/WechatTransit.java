package dev.mback.oneplus;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;
import dalvik.system.PathClassLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** WeChat classes stay in MBack's process; SystemUI authorizes the fixed destination. */
final class WechatTransit {
    static final String ACTION = "dev.mback.oneplus.OPEN_WECHAT_TRANSIT";
    static final String LAUNCH_EXTRA = "wechat-launch";
    static final String COMPONENT = "com.tencent.mm/com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI";
    private static final AtomicBoolean opening = new AtomicBoolean();
    private static final AtomicInteger requests = new AtomicInteger();

    static void request(Context context) {
        ActivityOptions options = ActivityOptions.makeBasic();
        if (Build.VERSION.SDK_INT >= 35) options.setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        Bundle bundle = options.toBundle();
        bundle.putInt("android.activity.windowingMode", 100);
        bundle.putInt("android:activity.mZoomLaunchFlags", 2);
        Intent destination = new Intent().setComponent(ComponentName.unflattenFromString(COMPONENT))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent target = PendingIntent.getActivity(context, requests.incrementAndGet(), destination, flags, bundle);
        try {
            context.startActivity(new Intent(ACTION).setClassName("dev.mback.oneplus", "dev.mback.oneplus.WechatTransitActivity")
                    .putExtra(LAUNCH_EXTRA, target)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException error) { target.cancel(); throw error; }
    }

    static void open(Context context, PendingIntent target) {
        if (!PresetShortcut.directTransit(context)) { target.cancel(); return; }
        if (!opening.compareAndSet(false, true)) { target.cancel(); return; }
        new Thread(() -> {
            try {
                if (!context.getPackageManager().hasSigningCertificate("com.tencent.mm", Base64.decode(
                        "D+T/hcIVkYOW2tx82M5pYzOa8z03dRpW5UxyBrY6PHw=", Base64.NO_WRAP), PackageManager.CERT_INPUT_SHA256)) {
                    throw new SecurityException("WeChat signing certificate is not trusted");
                }
                ApplicationInfo info = context.getPackageManager().getApplicationInfo("com.tencent.mm", 0);
                if (!info.enabled) throw new IllegalStateException("WeChat is disabled");
                ClassLoader loader = new PathClassLoader(info.sourceDir, context.getClassLoader());
                // shortcut: WeChat 8.0.79's private parcel fields; update this mapping if WeChat changes its protocol.
                Class<?> type = loader.loadClass("com.tencent.mm.plugin.appbrand.launching.params.LaunchParcel");
                Object request = type.getDeclaredConstructor().newInstance();
                type.getField("d").set(request, "gh_3cf62f4f1d52@app");
                type.getField("i").set(request, "pages/qrcode/index?noback=1&channel=oppo_fuone&attach=02_oppo_fuone");
                type.getField("o").set(request, loader.loadClass("com.tencent.mm.plugin.appbrand.report.AppBrandStatObject")
                        .getDeclaredConstructor().newInstance());
                Intent fill = new Intent().putExtra("extra_launch_parcel", (Parcelable) request).putExtra("extra_from_mm", false);
                ActivityOptions sender = ActivityOptions.makeBasic();
                if (Build.VERSION.SDK_INT >= 34) sender.setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                if (!PresetShortcut.directTransit(context)) return;
                target.send(context, 0, fill, null, null, null, sender.toBundle());
                Log.i("MBack", "Direct WeChat transit launched");
            } catch (Throwable error) {
                Log.w("MBack", "Direct WeChat transit failed", error);
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context,
                        "微信乘车码打开失败，请确认微信已启用且版本兼容", Toast.LENGTH_LONG).show());
            } finally {
                target.cancel();
                opening.set(false);
            }
        }, "mback-wechat-transit").start();
    }

    private WechatTransit() {}
}
