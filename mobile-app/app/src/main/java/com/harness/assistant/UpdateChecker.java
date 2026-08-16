package com.harness.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新（照搬晨曦AI UpdateChecker）：
 * 启动时 GET /api/version 对比本地 versionCode，
 * 有新版本 -> 回调弹窗 -> 下载 APK -> 走 ApkProvider 唤起系统安装器。
 */
public class UpdateChecker {

    public interface Listener {
        void onUpdate(int version, String name, String url, String logText); // 有新版本
        void onUpToDate();                                                  // 已是最新
        void onError(String msg);                                           // 检查失败(静默即可)
    }

    /** 检查更新（后台线程，结果回调到 UI 线程） */
    public static void check(final Activity act, final String baseUrl, final Listener l) {
        new Thread(() -> {
            try {
                String url = baseUrl + "/api/version";
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                JSONObject j = new JSONObject(readAll(c));
                int ver = j.optInt("version", 1);
                int local = act.getPackageManager().getPackageInfo(act.getPackageName(), 0).versionCode;
                if (ver > local) {
                    final int v = ver;
                    final String name = j.optString("name", "新版本");
                    final String apkUrl = j.optString("url", "");
                    // 只展示最新一条版本日志：items 限 8 条、总长限 400 字符（防弹窗被撑爆）
                    String logText = "";
                    JSONArray logs = j.optJSONArray("log");
                    if (logs != null && logs.length() > 0) {
                        JSONObject lo = logs.optJSONObject(0);
                        if (lo != null) {
                            StringBuilder sb = new StringBuilder();
                            sb.append("◆ ").append(lo.optString("v", "")).append(" · ")
                                    .append(lo.optString("date", "")).append("\n");
                            JSONArray items = lo.optJSONArray("items");
                            int cnt = 0;
                            if (items != null) {
                                for (int k = 0; k < items.length() && cnt < 8; k++, cnt++) {
                                    sb.append("   ").append(items.optString(k, "")).append("\n");
                                }
                            }
                            String full = sb.toString().trim();
                            logText = full.length() > 400 ? full.substring(0, 400) + "\n…" : full;
                        }
                    }
                    final String log = logText;
                    act.runOnUiThread(() -> l.onUpdate(v, name, apkUrl, log));
                } else {
                    act.runOnUiThread(l::onUpToDate);
                }
            } catch (final Exception e) {
                act.runOnUiThread(() -> l.onError(e.getMessage() == null ? "检查更新失败" : e.getMessage()));
            }
        }).start();
    }

    /** 下载 APK 到 filesDir/update.apk，完成后回调 onReady（后台线程） */
    public static void download(final Context ctx, final String apkUrl, final Runnable onReady, final Runnable onFail) {
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(10_000);
                c.setReadTimeout(120_000);
                File f = new File(ctx.getFilesDir(), "update.apk");
                try (InputStream in = c.getInputStream();
                     OutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    if (total <= 0) { onFail.run(); return; }
                }
                onReady.run();
            } catch (final Exception e) {
                onFail.run();
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    /** 唤起系统安装器（下载完成后调用；Android 8+ 需"安装未知来源应用"授权） */
    public static void install(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !ctx.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(ctx, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + ctx.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                try {
                    ctx.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/update.apk");
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "无法唤起安装器: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String readAll(HttpURLConnection c) throws Exception {
        InputStream in = c.getResponseCode() == 200 ? c.getInputStream() : c.getErrorStream();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }
}
