package dev.mback.oneplus;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

public final class TransitShortcutsInstrumentation extends Instrumentation {
    private String mode;
    @Override public void onCreate(Bundle args) { super.onCreate(args); mode = args.getString("mode"); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            PresetShortcut wechat = PresetShortcut.ALL[4], alipay = PresetShortcut.ALL[5];
            boolean direct = "direct".equals(mode), available = !"unavailable".equals(mode);
            check(PresetShortcut.directTransit(context) == direct, "Wrong default or saved mode");
            check(wechat.available(context) == available && alipay.available(context) == available,
                    "Preset availability does not follow the selected route");
            check(wechat.intent(direct).getComponent().getPackageName().equals(direct ? "com.tencent.mm"
                    : "com.coloros.assistantscreen"), "Wrong WeChat dispatch");
            check(alipay.intent(direct).getComponent().getPackageName().equals(direct ? "com.eg.android.AlipayGphone"
                    : "com.coloros.assistantscreen"), "Wrong Alipay dispatch");
            if (direct) check(alipay.intent(true).getData().getQueryParameter("appId").equals("200011235"), "Wrong transit appId");
            else check(wechat.intent(false).getData() == null && alipay.intent(false).getData() == null,
                    "Assistant route gained unexpected parameters");
            for (boolean option : new boolean[]{false, true}) {
                check(PresetShortcut.ALL[3].intent(option).getData().getQueryParameter("appId").equals("20000056"),
                        "Payment route changed");
                for (int i = 0; i < 4; i++) check(PresetShortcut.ALL[i].launchTarget(option).id()
                        .equals(PresetShortcut.ALL[i].target.id()), "Non-transit preset changed");
            }
            for (PresetShortcut preset : new PresetShortcut[]{wechat, alipay}) {
                ShortcutTarget saved = ShortcutTarget.parse(preset.target.id());
                check(PresetShortcut.find(saved) == preset, "Legacy shortcut lost its preset");
                check(ShortcutStore.label(context, new ShortcutStore.Item(saved, "")).equals(preset.label), "Legacy label changed");
            }
            check(context.getPackageManager().getActivityInfo(new ComponentName(context, WechatTransitActivity.class), 0)
                    .permission.equals("android.permission.STATUS_BAR"), "Transit action is not permission protected");
            check(context.getPackageManager().checkPermission("android.permission.STATUS_BAR", "dev.mback.oneplus.transitchecks")
                    == PackageManager.PERMISSION_DENIED, "Ordinary apps can invoke the transit action");
            check(context.getPackageManager().checkPermission("android.permission.STATUS_BAR", "com.android.systemui")
                    == PackageManager.PERMISSION_GRANTED, "SystemUI cannot invoke transit");
            check(!WechatTransitActivity.accepts(new Intent(WechatTransit.ACTION)), "Missing SystemUI authorization accepted");
            check(!WechatTransitActivity.accepts(null) && !WechatTransitActivity.accepts(new Intent()), "Unknown action accepted");
            check(!WechatTransitActivity.accepts(new Intent(WechatTransit.ACTION).putExtra("arbitrary", true)), "External arguments accepted");
            check(!WechatTransitActivity.accepts(new Intent(WechatTransit.ACTION).setData(android.net.Uri.parse("test://data"))), "External URI accepted");
            PendingIntent own = PendingIntent.getActivity(context, 1107, new Intent(context, MainActivity.class),
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
            try {
                check(!WechatTransitActivity.accepts(new Intent(WechatTransit.ACTION).putExtra(WechatTransit.LAUNCH_EXTRA, own)),
                        "A token from another package was accepted");
            } finally { own.cancel(); }
            result.putString("result", "Transit preset checks passed");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("error", error.toString());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
