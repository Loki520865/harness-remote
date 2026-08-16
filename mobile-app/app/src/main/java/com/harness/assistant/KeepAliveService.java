package com.harness.assistant;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * v0.6.0：前台保活服务（常驻通知，降低系统杀进程导致的断连/漏消息）。
 * 依赖：MainActivity 启动；开关在设置页（KEY_KEEPALIVE，默认开）。
 */
public class KeepAliveService extends Service {

    public static final String CH_ID = "harness_assistant_keepalive";

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.ensureKeepAliveChannel(this);
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 被系统回收后尝试重建
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("Harness助手运行中")
                .setContentText("后台保活中 · 点击返回对话")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }
}
