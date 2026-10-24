package dev.mback.oneplus;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

final class StatusNotification {
    private static final String CHANNEL = "mback_status";
    private static final int ID = 2107;

    static void sync(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (!context.getSharedPreferences("mback", Context.MODE_PRIVATE)
                .getBoolean("show_notification", false)) {
            manager.cancel(ID);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            manager.cancel(ID);
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "MBack 状态", NotificationManager.IMPORTANCE_DEFAULT);
        manager.createNotificationChannel(channel);
        PendingIntent pending = PendingIntent.getActivity(context, 0,
                new Intent(context, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL)
                .setContentTitle("MBack")
                .setContentText("轻触返回已配置 · 点此打开设置")
                .setSmallIcon(android.R.drawable.ic_media_previous)
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
        manager.notify(ID, notification);
    }
    private StatusNotification() {}
}
