package com.harness.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * 系统通知（复用晨曦AI NotificationHelper）：
 * Android 8.0+ 必须建 NotificationChannel；小图标用系统自带 alpha 图标。
 */
public class NotificationHelper {

    public static final String CH_ID = "harness_assistant_notify";

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Harness助手任务通知", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("远程任务完成 / 状态提醒");
            ch.enableVibration(true);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    /** v0.6.0：前台保活常驻通知通道（低重要，无振动无角标）。 */
    public static void ensureKeepAliveChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    KeepAliveService.CH_ID, "后台保活", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("常驻通知：保持与电脑的连接不被系统回收");
            ch.enableVibration(false);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    public static void show(Context ctx, String title, String text) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int notifyId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        PendingIntent pi = PendingIntent.getActivity(
                ctx, notifyId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(ctx, CH_ID);
        } else {
            b = new Notification.Builder(ctx);
        }
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(notifyId, b.build());
    }
}
