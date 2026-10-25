package dev.mback.oneplus;

import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Uses ColorOS' native flexible-window routing, not a fake WebView overlay. */
final class FreeformLauncher {
    private static final String TAG = "[MBack-Freeform]";
    private static final AtomicInteger transitRequests = new AtomicInteger();
    private final Context context;

    FreeformLauncher(Context context) { this.context = context; }

    static void openMore(Context context) { openMore(context, 0); }

    static void openMore(Context context, int ring) {
        new FreeformLauncher(context).open(ShortcutTarget.parse(
                "dev.mback.oneplus/dev.mback.oneplus.MoreAppsActivity"), ring);
    }

    void open(ShortcutTarget target) { open(target, 0); }

    private void open(ShortcutTarget target, int ring) {
        int request = transitRequests.incrementAndGet();
        try {
            boolean direct = PresetShortcut.directTransit(context);
            PresetShortcut preset = PresetShortcut.find(target);
            Intent launch;
            if (target.activityName == null) {
                launch = context.getPackageManager().getLaunchIntentForPackage(target.packageName);
            } else {
                ActivityInfo info = ShortcutStore.activity(context, preset == null ? target : preset.launchTarget(direct));
                if (!info.enabled || !info.applicationInfo.enabled) {
                    message(context, "此活动已停用，无法打开");
                    return;
                }
                if (direct && preset == PresetShortcut.ALL[4]) {
                    WechatTransit.request(context);
                    followTransitWindow(target, request, true);
                    return;
                }
                launch = preset == null ? new Intent().setComponent(new ComponentName(target.packageName, target.activityName))
                        : preset.intent(direct);
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
            if (direct && preset != null && preset.isTransit()) bundle.putInt("android:activity.mZoomLaunchFlags", 2);
            context.startActivity(launch, bundle);
            followTransitWindow(target, request, direct);
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

    private void followTransitWindow(ShortcutTarget target, int request, boolean direct) {
        boolean wechat = target.id().equals(PresetShortcut.ALL[4].target.id());
        boolean alipay = target.id().equals(PresetShortcut.ALL[5].target.id());
        if (!wechat && !alipay) return;
        new Thread(() -> {
            try {
                Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
                Object windows = XposedHelpers.callStaticMethod(Class.forName(
                        "com.oplus.flexiblewindow.FlexibleWindowManager"), "getInstance");
                ActivityManager tasks = context.getSystemService(ActivityManager.class);
                KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
                Bundle options = ActivityOptions.makeBasic().toBundle();
                options.putInt("android.activity.windowingMode", 100);
                // ColorOS flag 2 restores a minimized task to its normal floating size.
                options.putInt("android:activity.mZoomLaunchFlags", 2);
                String pkg = wechat ? "com.tencent.mm" : "com.eg.android.AlipayGphone";
                String activity = wechat ? "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"
                        : "com.alipay.android.phone.xriver.bundlex.CSGAPushActivity";
                // shortcut: watch for 8 seconds; use task callbacks if slower ROM launches exceed this bound.
                long deadline = SystemClock.uptimeMillis() + 8000, stableSince = -1, lastAttempt = 0;
                int stableTask = -1;
                boolean converted = false;
                // Assistant may need conversion; direct launches only restore an existing native window.
                while (transitRequests.get() == request && SystemClock.uptimeMillis() < deadline) {
                    if (PresetShortcut.directTransit(context) != direct) return;
                    if ((keyguard != null && keyguard.isDeviceLocked()) || ShadeVisibility.isExpanded()) return;
                    List<ActivityManager.RunningTaskInfo> running = tasks.getRunningTasks(1);
                    if (transitRequests.get() != request) return;
                    ActivityManager.RunningTaskInfo task = running.isEmpty() ? null : running.get(0);
                    ComponentName top = task == null ? null : task.topActivity;
                    long now = SystemClock.uptimeMillis();
                    if (top != null && pkg.equals(top.getPackageName()) && top.getClassName().startsWith(activity)) {
                        int state = (Integer) XposedHelpers.callMethod(windows, "getFlexibleWindowState", task);
                        if (state == 1) {
                            if (stableTask != task.taskId) { stableTask = task.taskId; stableSince = now; }
                            if (now - stableSince >= 500) {
                                if (!converted) {
                                    int result = (Integer) XposedHelpers.callMethod(service, "startActivityFromRecents", task.taskId, options);
                                    if (result < 0) throw new IllegalStateException("Task start rejected: " + result);
                                }
                                XposedBridge.log(TAG + " transit native window ready task=" + task.taskId);
                                return;
                            }
                        } else {
                            stableTask = -1;
                            if (!direct && now - lastAttempt >= 500) {
                                converted |= (Boolean) XposedHelpers.callMethod(windows, "toggleFlexibleWindow", null, task.taskId, true, true);
                                lastAttempt = now;
                            }
                        }
                    } else stableTask = -1;
                    SystemClock.sleep(100);
                }
                if (transitRequests.get() == request && PresetShortcut.directTransit(context) == direct)
                    message(context, "乘车码已启动，但系统未能切换为小窗");
            } catch (Throwable error) {
                XposedBridge.log(TAG + " transit window failed: " + error);
                if (transitRequests.get() == request && PresetShortcut.directTransit(context) == direct)
                    message(context, "系统无法将乘车码切换为小窗");
            }
        }, "mback-transit-window").start();
    }

    private static void message(Context context, String text) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, text, Toast.LENGTH_LONG).show());
    }
}
