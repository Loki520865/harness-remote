package com.harness.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * 设置页（M2）：服务器地址（Q5 连接可视化）+ 主题 + 设备信息。
 * M3 新增：账号卡片（邀请码/邮箱验证码注册、登录、退出）。
 */
public class SettingsPage {

    private final MainActivity act;
    private LinearLayout root;
    private ScrollView scroll;     // v0.6.2 修复：设置页内容超屏无法滚动（从 M2 就存在）
    private EditText serverInput;
    private TextView deviceInfo;
    private TextView noticeText;   // 公告（关于卡片）
    private TextView errRow;       // 上次断开原因（诊断）
    private TextView themeRow;
    private TextView keepRow;      // v0.6.0: 后台保活开关状态
    private TextView connDiag;     // v0.6.1: 连接诊断（重连次数/在线时长）
    private TextView modelRow;     // v0.7.0: 模型（新会话生效）

    // ---- M3 账号 ----
    private EditText emailInput;
    private EditText passwordInput;  // 密码（登录/注册，照晨曦AI）
    private EditText inviteInput;
    private EditText codeInput;
    private TextView statusLine;
    private TextView actionBtn;      // 注册并登录（未登录时）
    private TextView loginBtn;       // 登录（邮箱+密码）
    private TextView forgotBtn;      // 找回密码
    private TextView changePwdBtn;   // 修改密码（已登录时）
    private TextView logoutBtn;

    public SettingsPage(MainActivity act) {
        this.act = act;
        build();
    }

    public View view() { return scroll; }

    private void build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(act));

        TextView title = new TextView(act);
        title.setText("设置");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.txt(act));
        title.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 12), UiKit.dp(act, 16), UiKit.dp(act, 12));
        root.addView(title);

        // 服务器地址
        LinearLayout serverBox = cardBox();
        TextView serverLabel = new TextView(act);
        serverLabel.setText("服务器地址");
        serverLabel.setTextSize(12);
        serverLabel.setTextColor(Theme.sub(act));
        serverBox.addView(serverLabel);

        serverInput = new EditText(act);
        serverInput.setTextColor(Theme.txt(act));
        serverInput.setTextSize(14);
        serverInput.setBackgroundResource(0);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(UiKit.dp(act, 12));
        ibg.setColor(Theme.inputBg(act));
        serverInput.setBackground(ibg);
        serverInput.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        serverInput.setSingleLine(true);
        serverBox.addView(serverInput);

        TextView saveBtn = new TextView(act);
        saveBtn.setText("保存");
        saveBtn.setTextSize(13);
        saveBtn.setTextColor(Theme.onAccent(act));
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 6), UiKit.dp(act, 14), UiKit.dp(act, 6));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(UiKit.dp(act, 16));
        sbg.setColor(Theme.accent(act));
        saveBtn.setBackground(sbg);
        saveBtn.setClickable(true);
        saveBtn.setOnClickListener(v -> {
            // 修复：保存按钮真正落盘（原来只 toast）
            String url = serverInput.getText().toString().trim();
            if (!url.isEmpty()) {
                act.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE)
                        .edit().putString(MainActivity.KEY_SERVER, url).apply();
            }
            act.toast("已保存，点顶部「连接」生效");
        });
        serverBox.addView(saveBtn);
        root.addView(serverBox);

        // ---- M3 账号卡片 ----
        root.addView(buildAccountCard());

        // 主题（v0.6.7：深色/浅色/跟随系统，点击切换，切换后重建 UI）
        LinearLayout themeBox = cardBox();
        TextView themeLabel = new TextView(act);
        themeLabel.setText("主题");
        themeLabel.setTextSize(12);
        themeLabel.setTextColor(Theme.sub(act));
        themeBox.addView(themeLabel);
        themeRow = new TextView(act);
        themeRow.setText(Theme.modeName(act));
        themeRow.setTextSize(15);
        themeRow.setTextColor(Theme.txt(act));
        themeRow.setPadding(0, UiKit.dp(act, 6), 0, 0);
        themeRow.setClickable(true);
        themeRow.setOnClickListener(v -> pickTheme());
        themeBox.addView(themeRow);
        root.addView(themeBox);

        // v0.7.0：模型（新会话生效，点击切换 provider/模型）
        LinearLayout modelBox = cardBox();
        TextView modelLabel = new TextView(act);
        modelLabel.setText("模型（新会话生效）");
        modelLabel.setTextSize(12);
        modelLabel.setTextColor(Theme.sub(act));
        modelBox.addView(modelLabel);
        modelRow = new TextView(act);
        modelRow.setText("点击加载…");
        modelRow.setTextSize(15);
        modelRow.setTextColor(Theme.txt(act));
        modelRow.setPadding(0, UiKit.dp(act, 6), 0, 0);
        modelRow.setClickable(true);
        modelRow.setOnClickListener(v -> act.sendModelList());
        modelBox.addView(modelRow);
        root.addView(modelBox);

        // v0.6.0：后台保活开关（前台服务常驻通知，默认开；点击切换）
        LinearLayout keepBox = cardBox();
        TextView keepLabel = new TextView(act);
        keepLabel.setText("后台保活");
        keepLabel.setTextSize(12);
        keepLabel.setTextColor(Theme.sub(act));
        keepBox.addView(keepLabel);
        keepRow = new TextView(act);
        keepRow.setText(isKeepAliveOn() ? "已开启（常驻通知）" : "已关闭");
        keepRow.setTextSize(15);
        keepRow.setTextColor(Theme.accent(act));
        keepRow.setPadding(0, UiKit.dp(act, 6), 0, 0);
        keepRow.setClickable(true);
        keepRow.setOnClickListener(v -> toggleKeepAlive());
        keepBox.addView(keepRow);
        root.addView(keepBox);

        // 设备信息
        LinearLayout devBox = cardBox();
        TextView devLabel = new TextView(act);
        devLabel.setText("工作站");
        devLabel.setTextSize(12);
        devLabel.setTextColor(Theme.sub(act));
        devBox.addView(devLabel);
        deviceInfo = new TextView(act);
        deviceInfo.setText("未连接");
        deviceInfo.setTextSize(13);
        deviceInfo.setTextColor(Theme.txt(act));
        deviceInfo.setPadding(0, UiKit.dp(act, 6), 0, 0);
        deviceInfo.setLineSpacing(UiKit.dp(act, 2), 1f);
        devBox.addView(deviceInfo);
        root.addView(devBox);

        // 关于：版本号 + 公告 + 上次断开原因（诊断）
        LinearLayout aboutBox = cardBox();
        TextView aboutLabel = new TextView(act);
        aboutLabel.setText("关于");
        aboutLabel.setTextSize(12);
        aboutLabel.setTextColor(Theme.sub(act));
        aboutBox.addView(aboutLabel);

        String ver = "v" + BuildConfig.VERSION_NAME;
        TextView verRow = new TextView(act);
        verRow.setText("版本 " + ver);
        verRow.setTextSize(13);
        verRow.setTextColor(Theme.txt(act));
        verRow.setPadding(0, UiKit.dp(act, 6), 0, 0);
        aboutBox.addView(verRow);

        // 手动检查更新（照搬晨曦AI 设置页"检查更新"）
        TextView checkUpd = new TextView(act);
        checkUpd.setText("检查更新");
        checkUpd.setTextSize(13);
        checkUpd.setTextColor(Theme.accent(act));
        checkUpd.setPadding(0, UiKit.dp(act, 6), 0, 0);
        checkUpd.setClickable(true);
        checkUpd.setOnClickListener(v -> checkUpdate());
        aboutBox.addView(checkUpd);

        noticeText = new TextView(act);
        noticeText.setText("公告加载中…");
        noticeText.setTextSize(13);
        noticeText.setTextColor(Theme.accent(act));
        noticeText.setPadding(0, UiKit.dp(act, 6), 0, 0);
        noticeText.setLineSpacing(UiKit.dp(act, 2), 1f);
        aboutBox.addView(noticeText);

        errRow = new TextView(act);
        errRow.setTextSize(12);
        errRow.setTextColor(Theme.errTxt(act));
        errRow.setPadding(0, UiKit.dp(act, 4), 0, 0);
        aboutBox.addView(errRow);

        // v0.6.1：连接诊断（累计重连次数 / 本次在线时长，辅助排查稳定性）
        connDiag = new TextView(act);
        connDiag.setTextSize(12);
        connDiag.setTextColor(Theme.txt(act));
        connDiag.setPadding(0, UiKit.dp(act, 4), 0, 0);
        aboutBox.addView(connDiag);
        root.addView(aboutBox);

        // v0.6.0：帮助卡片（官网 / 反馈邮箱 / 本地面板）
        LinearLayout helpBox = cardBox();
        TextView helpLabel = new TextView(act);
        helpLabel.setText("帮助");
        helpLabel.setTextSize(12);
        helpLabel.setTextColor(Theme.sub(act));
        helpBox.addView(helpLabel);

        TextView webLink = new TextView(act);
        webLink.setText("部署说明 · 见项目 README");
        webLink.setTextSize(13);
        webLink.setTextColor(Theme.accent(act));
        webLink.setPadding(0, UiKit.dp(act, 6), 0, 0);
        helpBox.addView(webLink);

        TextView mailLink = new TextView(act);
        mailLink.setText("问题反馈：GitHub Issues");
        mailLink.setTextSize(13);
        mailLink.setTextColor(Theme.accent(act));
        mailLink.setPadding(0, UiKit.dp(act, 4), 0, 0);
        helpBox.addView(mailLink);

        TextView panelHint = new TextView(act);
        panelHint.setText("电脑端请保持「桌面伴侣」运行；本地面板 http://localhost:8718 可查看电脑端链路状态。");
        panelHint.setTextSize(12);
        panelHint.setTextColor(Theme.sub(act));
        panelHint.setPadding(0, UiKit.dp(act, 4), 0, 0);
        panelHint.setLineSpacing(UiKit.dp(act, 2), 1f);
        helpBox.addView(panelHint);
        root.addView(helpBox);

        // v0.6.2 修复：设置页内容超屏无法滚动（从 M2 就存在）——整体包 ScrollView
        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.bg(act));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refreshAbout();
    }

    private boolean isKeepAliveOn() {
        return act.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_KEEPALIVE, true);
    }

    /** v0.6.0：切换后台保活（持久化 + 启停前台服务）。 */
    private void toggleKeepAlive() {
        boolean on = isKeepAliveOn();
        act.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(MainActivity.KEY_KEEPALIVE, !on).apply();
        if (on) act.stopKeepAlive();
        else act.startKeepAlive();
        keepRow.setText(!on ? "已开启（常驻通知）" : "已关闭");
        act.toast(!on ? "已开启后台保活" : "已关闭后台保活");
    }

    private void openUrl(String url) {
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            act.toast("无法打开: " + url);
        }
    }

    /** 手动检查更新（照搬晨曦AI 设置页）：有新版本弹窗，否则提示已是最新。 */
    private void checkUpdate() {
        act.toast("正在检查更新…");
        UpdateChecker.check(act, httpBase(), new UpdateChecker.Listener() {
            @Override
            public void onUpdate(int version, String name, String url, String logText) {
                act.showUpdateDialog(name, logText, url);
            }
            @Override public void onUpToDate() {
                act.toast("已是最新版本");
            }
            @Override public void onError(String msg) {
                act.toast("检查更新失败: " + msg);
            }
        });
    }

    /** 刷新关于卡片：版本公告 + 上次断开原因。 */
    private void refreshAbout() {        if (errRow == null) return;
        String lastErr = act.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_LAST_ERR, "");
        errRow.setText(lastErr.isEmpty() ? "" : "上次断开: " + lastErr);
        // v0.6.1：连接诊断
        if (connDiag != null) {
            long secs = act.getOnlineSeconds();
            String online;
            if (secs <= 0) online = "当前未连接";
            else if (secs < 60) online = "本次已在线 " + secs + " 秒";
            else online = "本次已在线 " + (secs / 60) + " 分 " + (secs % 60) + " 秒";
            connDiag.setText("累计自动重连 " + act.getReconnectCount() + " 次 · " + online);
        }
        ApiClient.fetchNotice(httpBase(), (ok, err, data) -> {
            if (ok && data != null) {
                String n = data.optString("notice", "");
                noticeText.setText(n.isEmpty() ? "暂无公告" : "公告: " + n);
            } else {
                noticeText.setText("公告加载失败" + (err == null ? "" : ": " + err));
            }
        });
    }

    /** M3：账号卡片（未登录：邀请码/邮箱/验证码注册或登录；已登录：显示邮箱 + 退出） */
    private LinearLayout buildAccountCard() {
        LinearLayout box = cardBox();

        TextView label = new TextView(act);
        label.setText("账号（手机端登录）");
        label.setTextSize(12);
        label.setTextColor(Theme.sub(act));
        box.addView(label);

        statusLine = new TextView(act);
        statusLine.setTextSize(13);
        statusLine.setTextColor(Theme.accent(act));
        statusLine.setPadding(0, UiKit.dp(act, 6), 0, 0);
        box.addView(statusLine);

        emailInput = inputBox("邮箱（如 you@qq.com）");
        box.addView(emailInput);

        passwordInput = inputBox("密码（至少 8 位，含字母和数字）");
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passwordInput);

        inviteInput = inputBox("邀请码（仅注册需要）");
        box.addView(inviteInput);

        // 验证码 + 发送按钮（注册/找回用）
        LinearLayout codeRow = new LinearLayout(act);
        codeRow.setOrientation(LinearLayout.HORIZONTAL);
        codeInput = new EditText(act);
        codeInput.setTextColor(Theme.txt(act));
        codeInput.setTextSize(14);
        codeInput.setHint("验证码");
        codeInput.setHintTextColor(Theme.sub(act));
        codeInput.setBackgroundResource(0);
        GradientDrawable cbg = new GradientDrawable();
        cbg.setCornerRadius(UiKit.dp(act, 12));
        cbg.setColor(Theme.inputBg(act));
        codeInput.setBackground(cbg);
        codeInput.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        codeInput.setSingleLine(true);
        codeRow.addView(codeInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView sendCodeBtn = new TextView(act);
        sendCodeBtn.setText("发送验证码");
        sendCodeBtn.setTextSize(13);
        sendCodeBtn.setTextColor(Theme.onAccent(act));
        sendCodeBtn.setGravity(Gravity.CENTER);
        sendCodeBtn.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 6), UiKit.dp(act, 12), UiKit.dp(act, 6));
        GradientDrawable scbg = new GradientDrawable();
        scbg.setCornerRadius(UiKit.dp(act, 16));
        scbg.setColor(Theme.accent(act));
        sendCodeBtn.setBackground(scbg);
        sendCodeBtn.setClickable(true);
        sendCodeBtn.setOnClickListener(v -> sendCode());
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scLp.leftMargin = UiKit.dp(act, 8);
        scLp.gravity = Gravity.CENTER_VERTICAL;
        codeRow.addView(sendCodeBtn, scLp);
        box.addView(codeRow);

        // 注册并登录（照晨曦AI：邀请码 + 邮箱验证码 + 密码）
        actionBtn = new TextView(act);
        actionBtn.setText("注册并登录");
        actionBtn.setTextSize(14);
        actionBtn.setTextColor(Theme.onAccent(act));
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 10), UiKit.dp(act, 14), UiKit.dp(act, 10));
        GradientDrawable abg = new GradientDrawable();
        abg.setCornerRadius(UiKit.dp(act, 16));
        abg.setColor(Theme.accent(act));
        actionBtn.setBackground(abg);
        actionBtn.setClickable(true);
        actionBtn.setOnClickListener(v -> register());
        LinearLayout.LayoutParams abLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        abLp.topMargin = UiKit.dp(act, 10);
        box.addView(actionBtn, abLp);

        // 已有账号 → 登录（邮箱+密码）
        loginBtn = new TextView(act);
        loginBtn.setText("已有账号？登录");
        loginBtn.setTextSize(13);
        loginBtn.setTextColor(Theme.accent(act));
        loginBtn.setGravity(Gravity.CENTER);
        loginBtn.setPadding(0, UiKit.dp(act, 10), 0, 0);
        loginBtn.setClickable(true);
        loginBtn.setOnClickListener(v -> login());
        box.addView(loginBtn);

        // 找回密码（邮箱验证码 + 新密码）
        forgotBtn = new TextView(act);
        forgotBtn.setText("忘记密码？");
        forgotBtn.setTextSize(12);
        forgotBtn.setTextColor(Theme.sub(act));
        forgotBtn.setGravity(Gravity.CENTER);
        forgotBtn.setPadding(0, UiKit.dp(act, 4), 0, 0);
        forgotBtn.setClickable(true);
        forgotBtn.setOnClickListener(v -> forgotPassword());
        box.addView(forgotBtn);

        // 修改密码（已登录时显示）
        changePwdBtn = new TextView(act);
        changePwdBtn.setText("修改密码");
        changePwdBtn.setTextSize(13);
        changePwdBtn.setTextColor(Theme.sub(act));
        changePwdBtn.setGravity(Gravity.CENTER);
        changePwdBtn.setPadding(0, UiKit.dp(act, 6), 0, 0);
        changePwdBtn.setClickable(true);
        changePwdBtn.setOnClickListener(v -> changePassword());
        box.addView(changePwdBtn);

        // 退出登录（已登录时显示）
        logoutBtn = new TextView(act);
        logoutBtn.setText("退出登录");
        logoutBtn.setTextSize(13);
        logoutBtn.setTextColor(Theme.errTxt(act));
        logoutBtn.setGravity(Gravity.CENTER);
        logoutBtn.setPadding(0, UiKit.dp(act, 6), 0, 0);
        logoutBtn.setClickable(true);
        logoutBtn.setOnClickListener(v -> {
            act.logout();
            refreshAuth();
            act.toast("已退出登录");
        });
        box.addView(logoutBtn);

        refreshAuth();
        return box;
    }

    private EditText inputBox(String hint) {
        EditText et = new EditText(act);
        et.setTextColor(Theme.txt(act));
        et.setTextSize(14);
        et.setHint(hint);
        et.setHintTextColor(Theme.sub(act));
        et.setBackgroundResource(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 12));
        bg.setColor(Theme.inputBg(act));
        et.setBackground(bg);
        et.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        et.setSingleLine(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(act, 8);
        et.setLayoutParams(lp);
        return et;
    }

    private String httpBase() {
        String url = getServerUrl();
        if (url.isEmpty()) return "";
        return MainActivity.httpBase(url);
    }

    private static String errText(String err) {
        return err == null || err.isEmpty() ? "未知错误" : err;
    }

    private void sendCode() {
        String email = emailInput.getText().toString().trim();
        if (email.isEmpty()) { act.toast("请先填写邮箱"); return; }
        String invite = inviteInput.getText().toString().trim();
        act.toast("验证码发送中…");
        ApiClient.sendCode(httpBase(), email, invite, (ok, err, data) -> {
            if (ok) act.toast("验证码已发送，请查收邮箱");
            else act.toast("发送失败: " + errText(err));
        });
    }

    private void register() {
        String email = emailInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String invite = inviteInput.getText().toString().trim();
        if (email.isEmpty() || code.isEmpty() || password.isEmpty()) {
            act.toast("请填写邮箱、验证码和密码");
            return;
        }
        if (invite.isEmpty()) { act.toast("注册需要邀请码"); return; }
        if (password.length() < 8) { act.toast("密码至少 8 位"); return; }
        act.toast("注册中…");
        ApiClient.register(httpBase(), email, code, password, act.phoneId(), (ok, err, data) -> {
            if (ok) {
                String token = data == null ? "" : data.optString("phone_token", "");
                if (token.isEmpty()) { act.toast("注册成功但未返回 token，请重新登录"); return; }
                act.saveLogin(token, email);
                refreshAuth();
                act.toast("注册成功，正在连接…");
                act.connect(getServerUrl());
            } else {
                act.toast("注册失败: " + errText(err));
            }
        });
    }

    private void login() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) { act.toast("请填写邮箱和密码"); return; }
        act.toast("登录中…");
        ApiClient.login(httpBase(), email, password, "phone", act.phoneId(), (ok, err, data) -> {
            if (ok) {
                String token = data == null ? "" : data.optString("token", "");
                if (token.isEmpty()) { act.toast("登录成功但未返回 token"); return; }
                act.saveLogin(token, email);
                refreshAuth();
                act.toast("登录成功，正在连接…");
                act.connect(getServerUrl());
            } else {
                act.toast("登录失败: " + errText(err));
            }
        });
    }

    /** 找回密码：弹窗输入验证码+新密码（先点此发送重置码到邮箱）。 */
    private void forgotPassword() {
        String email = emailInput.getText().toString().trim();
        if (email.isEmpty()) { act.toast("请先在上方填写邮箱"); return; }
        act.toast("重置验证码发送中…");
        ApiClient.sendResetCode(httpBase(), email, (ok, err, data) -> {
            if (!ok) { act.toast("发送失败: " + errText(err)); return; }
            act.toast("验证码已发送，请在弹窗中填写");
            // 弹窗：验证码 + 新密码
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(UiKit.dp(act, 24), UiKit.dp(act, 12), UiKit.dp(act, 24), 0);
            final EditText rc = new EditText(act);
            rc.setHint("邮箱收到的验证码");
            rc.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            row.addView(rc);
            final EditText rp = new EditText(act);
            rp.setHint("新密码（至少 8 位，含字母和数字）");
            rp.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            row.addView(rp);
            new android.app.AlertDialog.Builder(act)
                    .setTitle("找回密码")
                    .setView(row)
                    .setPositiveButton("重置", (d, w) -> {
                        String code = rc.getText().toString().trim();
                        String np = rp.getText().toString();
                        if (code.isEmpty() || np.isEmpty()) { act.toast("请填写验证码和新密码"); return; }
                        ApiClient.resetPassword(httpBase(), email, code, np, (ok2, err2, data2) -> {
                            if (ok2) act.toast("密码已重置，请用新密码登录");
                            else act.toast("重置失败: " + err2);
                        });
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
    }

    /** 修改密码（已登录）：旧密码 + 新密码；成功后双端 token 失效需重新登录。 */
    private void changePassword() {
        String token = act.authToken();
        if (token == null || token.isEmpty()) { act.toast("未登录"); return; }
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiKit.dp(act, 24), UiKit.dp(act, 12), UiKit.dp(act, 24), 0);
        final EditText op = new EditText(act);
        op.setHint("当前密码");
        op.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row.addView(op);
        final EditText np = new EditText(act);
        np.setHint("新密码（至少 8 位，含字母和数字）");
        np.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row.addView(np);
        new android.app.AlertDialog.Builder(act)
                .setTitle("修改密码")
                .setMessage("修改后手机和电脑端都需要用新密码重新登录")
                .setView(row)
                .setPositiveButton("确认修改", (d, w) -> {
                    String oldP = op.getText().toString();
                    String newP = np.getText().toString();
                    if (oldP.isEmpty() || newP.isEmpty()) { act.toast("请填写新旧密码"); return; }
                    ApiClient.changePassword(httpBase(), token, oldP, newP, (ok, err, data) -> {
                        if (ok) {
                            act.toast("密码已修改，请重新登录");
                            act.logout();
                            refreshAuth();
                        } else {
                            act.toast("修改失败: " + err);
                        }
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshAuth() {
        if (statusLine == null) return;
        String email = act.authEmail();
        if (!email.isEmpty()) {
            statusLine.setText("已登录：" + email);
            statusLine.setTextColor(Theme.accent(act));
            actionBtn.setVisibility(View.GONE);
            loginBtn.setVisibility(View.GONE);
            forgotBtn.setVisibility(View.GONE);
            changePwdBtn.setVisibility(View.VISIBLE);
            logoutBtn.setVisibility(View.VISIBLE);
        } else {
            statusLine.setText("未登录（服务器开启登录校验时需要）");
            statusLine.setTextColor(Theme.sub(act));
            actionBtn.setVisibility(View.VISIBLE);
            loginBtn.setVisibility(View.VISIBLE);
            forgotBtn.setVisibility(View.VISIBLE);
            changePwdBtn.setVisibility(View.GONE);
            logoutBtn.setVisibility(View.GONE);
        }
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 12), UiKit.dp(act, 16), UiKit.dp(act, 12));
        box.setBackgroundColor(Theme.card(act));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.dp(act, 12);
        box.setLayoutParams(lp);
        return box;
    }

    private void pickTheme() {
        UiKit.menu(act, "主题模式",
                new UiKit.Item("深色", "dark".equals(Theme.mode(act)), () -> applyTheme("dark")),
                new UiKit.Item("浅色", "light".equals(Theme.mode(act)), () -> applyTheme("light")),
                new UiKit.Item("跟随系统", "auto".equals(Theme.mode(act)), () -> applyTheme("auto")));
    }

    private void applyTheme(String mode) {
        Theme.setMode(act, mode);
        act.recreate();
    }

    // ---------------- 对外接口 ----------------

    public void setServerUrl(String url) {
        if (serverInput != null) serverInput.setText(url == null ? "" : url);
    }

    public String getServerUrl() {
        if (serverInput == null) return "";
        return serverInput.getText().toString().trim();
    }

    public void onConnChanged(boolean connected, Map<String, String> devices, String currentDevice) {
        if (deviceInfo == null) return;
        if (!connected) {
            deviceInfo.setText("未连接");
            return;
        }
        if (devices.isEmpty()) {
            deviceInfo.setText("已连接服务器，等待设备上线…");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : devices.entrySet()) {
            sb.append(e.getValue()).append("  ").append(e.getKey());
            if (e.getKey().equals(currentDevice)) sb.append("  (当前)");
            sb.append("\n");
        }
        deviceInfo.setText(sb.toString().trim());
    }

    public void onShow() {
        themeRow.setText(Theme.modeName(act));
        refreshAuth();
        // v0.7.0：进入设置页自动拉取模型配置
        act.sendModelList();
    }

    // ---- v0.7.0：模型切换 ----

    public void onModelList(JSONObject msg) {
        String provider = msg.optString("provider", "");
        String model = msg.optString("model", "");
        updateModelRow(provider, model);
        final JSONArray providers = msg.optJSONArray("providers");
        final JSONArray candidates = msg.optJSONArray("modelCandidates");
        if (providers == null || providers.length() == 0) {
            act.toast("无可用模型提供方");
            return;
        }
        UiKit.Item[] items = new UiKit.Item[providers.length()];
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            final String pid = p.optString("id", "");
            final String pname = p.optString("name", pid);
            items[i] = new UiKit.Item(pname.isEmpty() ? pid : pname, () -> pickModel(pid, candidates));
        }
        UiKit.menu(act, "选择模型提供方", items);
    }

    public void onModelChanged(JSONObject msg) {
        updateModelRow(msg.optString("provider", ""), msg.optString("model", ""));
    }

    private void updateModelRow(String provider, String model) {
        String p = provider == null || provider.isEmpty() ? "—" : provider;
        String m = model == null || model.isEmpty() ? "—" : model;
        modelRow.setText(p + " · " + m);
    }

    private void pickModel(final String provider, JSONArray candidates) {
        if (provider == null || provider.isEmpty()) return;
        if (candidates != null && candidates.length() > 0) {
            UiKit.Item[] items = new UiKit.Item[candidates.length()];
            for (int i = 0; i < candidates.length(); i++) {
                final String m = candidates.optString(i, "");
                items[i] = new UiKit.Item(m, () -> act.sendModelSet(provider, m));
            }
            UiKit.menu(act, "选择模型（新会话生效）", items);
        } else {
            final EditText et = new EditText(act);
            et.setHint("输入模型名");
            et.setTextSize(14);
            et.setTextColor(Theme.txt(act));
            et.setSingleLine(true);
            et.setBackgroundResource(0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(UiKit.dp(act, 10));
            bg.setColor(Theme.inputBg(act));
            et.setBackground(bg);
            et.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
            UiKit.sheet(act, "输入模型名（新会话生效）", et,
                    new UiKit.Btn("取消", null),
                    new UiKit.Btn("切换", () -> act.sendModelSet(provider, et.getText().toString().trim())));
        }
    }

    public void onHide() {
    }
}
