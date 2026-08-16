package com.harness.assistant;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * M3 账号 HTTP API 客户端（零第三方依赖）。
 * 走与 WS 同一 nginx 反代：wss://host/relay/app → https://host/relay/api/*。
 */
public final class ApiClient {

    public interface Callback {
        void onResult(boolean ok, String error, JSONObject data);
    }

    private static final String TAG = "ApiClient";

    /** 单线程后台执行器（修复：每次请求 new Thread 的线程风暴） */
    private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private ApiClient() {}

    /** 后台线程 POST JSON，回调切主线程 */
    public static void post(final String baseUrl, final String path,
                            final JSONObject body, final Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        EXEC.execute(() -> {
            final boolean[] ok = { false };
            final String[] err = { null };
            final JSONObject[] data = { null };
            try {
                URL url = new URL(baseUrl + path);
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(15_000);
                c.setDoOutput(true);
                byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream out = c.getOutputStream();
                out.write(raw);
                out.flush();
                out.close();
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                }
                c.disconnect();
                JSONObject j = sb.length() > 0 ? new JSONObject(sb.toString()) : new JSONObject();
                data[0] = j;
                if (code >= 200 && code < 300 && j.optBoolean("ok", true)) {
                    ok[0] = true;
                } else {
                    err[0] = j.optString("error", "HTTP " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, path + " 请求失败: " + e.getMessage());
                err[0] = e.getMessage() == null ? ("请求失败（" + e.getClass().getSimpleName() + "）") : e.getMessage();
            }
            final boolean fOk = ok[0];
            final String fErr = err[0];
            final JSONObject fData = data[0];
            ui.post(() -> cb.onResult(fOk, fErr, fData));
        });
    }

    /** 发送邮箱验证码（注册需传邀请码） */
    public static void sendCode(String baseUrl, String email, String inviteCode, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            if (inviteCode != null && !inviteCode.isEmpty()) body.put("invite_code", inviteCode);
        } catch (Exception ignored) {}
        post(baseUrl, "/api/send_code", body, cb);
    }

    /** 注册（邮箱验证码 + 邀请码 + 密码），返回 phone_token / desktop_token */
    public static void register(String baseUrl, String email, String code, String password, String phoneId, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("code", code);
            body.put("password", password);
            body.put("phone_id", phoneId);
        } catch (Exception ignored) {}
        post(baseUrl, "/api/register", body, cb);
    }

    /** 登录（照晨曦AI：邮箱+密码；device=phone/desktop），返回 token */
    public static void login(String baseUrl, String email, String password, String device, String deviceId, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("password", password);
            body.put("device", device);
            if (deviceId != null) body.put("device_id", deviceId);
        } catch (Exception ignored) {}
        post(baseUrl, "/api/login", body, cb);
    }

    /** 找回密码：发送重置验证码 */
    public static void sendResetCode(String baseUrl, String email, Callback cb) {
        JSONObject body = new JSONObject();
        try { body.put("email", email); } catch (Exception ignored) {}
        post(baseUrl, "/api/send_reset_code", body, cb);
    }

    /** 找回密码：验证码 + 新密码 */
    public static void resetPassword(String baseUrl, String email, String code, String newPassword, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email);
            body.put("code", code);
            body.put("password", newPassword);
        } catch (Exception ignored) {}
        post(baseUrl, "/api/reset_password", body, cb);
    }

    /** 修改密码（需 token）：旧密码 + 新密码 */
    public static void changePassword(String baseUrl, String token, String oldPassword, String newPassword, Callback cb) {
        JSONObject body = new JSONObject();
        try {
            body.put("token", token);
            body.put("old_password", oldPassword);
            body.put("new_password", newPassword);
        } catch (Exception ignored) {}
        post(baseUrl, "/api/change_password", body, cb);
    }

    /** 拉取公告（GET /api/notice） */
    public static void fetchNotice(String baseUrl, Callback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        EXEC.execute(() -> {
            String[] err = { null };
            JSONObject[] data = { null };
            try {
                URL url = new URL(baseUrl + "/api/notice");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(10_000);
                c.setReadTimeout(10_000);
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                StringBuilder sb = new StringBuilder();
                if (in != null) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    r.close();
                }
                c.disconnect();
                JSONObject j = sb.length() > 0 ? new JSONObject(sb.toString()) : new JSONObject();
                data[0] = j;
                if (code < 200 || code >= 300) err[0] = "HTTP " + code;
            } catch (Exception e) {
                err[0] = e.getMessage() == null ? "请求失败" : e.getMessage();
            }
            final String fErr = err[0];
            final JSONObject fData = data[0];
            ui.post(() -> cb.onResult(fErr == null, fErr, fData));
        });
    }
}
