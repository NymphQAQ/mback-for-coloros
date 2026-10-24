package dev.mback.oneplus;

import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses ColorOS' native flexible-window routing, not a fake WebView overlay. */
final class FreeformLauncher {
    private static final String TAG = "[MBack-Freeform]";
    private final Context context;

    FreeformLauncher(Context context) { this.context = context; }

    void open(String packageName) {
        if (CornerGesture.sanitize(packageName) == null) return;
        try {
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) {
                XposedBridge.log(TAG + " no launch activity for " + packageName);
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // OPPO/ColorOS floating-window option (not AOSP WINDOWING_MODE_FREEFORM=5).
            ActivityOptions options = ActivityOptions.makeBasic();
            Bundle bundle = options.toBundle();
            bundle.putInt("android.activity.windowingMode", 100);
            context.startActivity(launch, bundle);
            XposedBridge.log(TAG + " ColorOS flexible start requested: " + packageName);
            // ColorOS may ignore mode 100 for apps outside its allowed window list.
            // Never silently launch a second fullscreen copy as a fallback.
        } catch (Throwable error) {
            XposedBridge.log(TAG + " native floating launch failed: " + error);
        }
    }
}
