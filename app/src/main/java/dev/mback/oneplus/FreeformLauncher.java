package dev.mback.oneplus;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/** Uses ColorOS' native flexible-window routing, not a fake WebView overlay. */
final class FreeformLauncher {
    private static final String TAG = "[MBack-Freeform]";
    private final Context context;

    FreeformLauncher(Context context) { this.context = context; }

    static void openMore(Context context) { openMore(context, 0); }

    static void openMore(Context context, int ring) {
        new FreeformLauncher(context).open(ShortcutTarget.parse(
                "dev.mback.oneplus/dev.mback.oneplus.MoreAppsActivity"), ring);
    }

    void open(ShortcutTarget target) { open(target, 0); }

    private void open(ShortcutTarget target, int ring) {
        try {
            Intent launch;
            if (target.activityName == null) {
                launch = context.getPackageManager().getLaunchIntentForPackage(target.packageName);
            } else {
                ActivityInfo info = ShortcutStore.activity(context, target);
                if (!info.enabled || !info.applicationInfo.enabled) {
                    message(context, "此活动已停用，无法打开");
                    return;
                }
                PresetShortcut preset = PresetShortcut.find(target);
                launch = preset == null ? new Intent().setComponent(new ComponentName(target.packageName, target.activityName))
                        : preset.intent();
            }
            if (launch == null) {
                message(context, "找不到此应用的启动入口");
                return;
            }
            if (ring != 0) launch.putExtra(MoreAppsActivity.RING_EXTRA, ring);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // OPPO/ColorOS floating-window option (not AOSP WINDOWING_MODE_FREEFORM=5).
            ActivityOptions options = ActivityOptions.makeBasic();
            Bundle bundle = options.toBundle();
            bundle.putInt("android.activity.windowingMode", 100);
            context.startActivity(launch, bundle);
            Log.i(TAG, "ColorOS flexible start requested: " + target.id());
            // ColorOS may ignore mode 100 for apps outside its allowed window list.
            // Never silently launch a second fullscreen copy as a fallback.
        } catch (SecurityException error) {
            Log.w(TAG, "Activity permission denied: " + target.id(), error);
            message(context, "系统不允许直接打开此活动");
        } catch (Exception error) {
            Log.w(TAG, "Native floating launch failed: " + target.id(), error);
            message(context, "活动无法打开，可能已卸载或需要应用内部参数");
        }
    }

    private static void message(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }
}
