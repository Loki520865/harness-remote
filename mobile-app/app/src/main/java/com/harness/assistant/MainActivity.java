package com.harness.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Harness助手 · 手机遥控器（M2 全页面重构）
 *
 * 布局：顶部标题栏（连接状态点 Q5 + 服务器名 + 连接按钮）→ 内容区（页面容器）→ 底部导航。
 * 页面：对话（默认）/ 会话 / 任务 / 设置；文件浏览器为子页（从会话行进入）。
 *
 * M2 能力：多任务（F21）、任务停止（F10）、文件浏览/下载/写回（F12-F14，限 cwd + 确认）、
 * 高危指令确认（F15）、chat ack 幂等重发（Q4）、草稿（F18）、未读角标（F19）、
 * 失败通知（F20）、连接态可视化（Q5）。
 */
public class MainActivity extends Activity {

    public static final String PREFS = "harness_assistant";
    public static final String KEY_SERVER = "server_url";
    public static final String KEY_LAST_SESSION = "last_session_id";
    public static final String KEY_DRAFT = "chat_draft";
    public static final String KEY_TOKEN = "auth_token"; // M3: phone_token
    public static final String KEY_EMAIL = "auth_email"; // M3: 登录邮箱
    public static final String KEY_PHONE_ID = "phone_id"; // M3: 本机唯一标识
    public static final String KEY_LAST_ERR = "last_conn_err"; // 最近一次连接断开原因（诊断用）
    public static final String KEY_KEEPALIVE = "keep_alive"; // v0.6.0: 前台保活开关（默认开）
    public static final String KEY_RECONNECT_COUNT = "reconnect_count"; // v0.6.1: 累计自动重连次数（诊断）

    private final Handler ui = new Handler(Looper.getMainLooper());

    // ---- 连接 ----
    private WsClient ws;
    private boolean connected;
    // 并发安全（修复：WsClient 回调线程写、UI 线程读，裸 HashMap 并发损坏 → 死循环/全白/卡死）
    private final Map<String, String> devices = new ConcurrentHashMap<>(); // device_id -> name
    private String currentDevice;
    private boolean manualDisconnect;   // 手动断开（不自动重连）
    private String lastUrl = "";
    private int reconnectAttempts;
    private Runnable reconnectRunnable; // 重连定时器（onOpen/重连前取消，防双连接）
    // v0.6.1 连接诊断：累计自动重连次数（跨启动持久化）+ 本次在线起点
    private int reconnectCount;
    private long connectStartedAt;

    // ---- 会话 ----
    public String lastSessionId = ""; // 自动续接
    private final Set<String> unread = ConcurrentHashMap.newKeySet(); // F19：未读会话 id

    // ---- Q4：mid -> 重发计时器（收 ack 取消，超时重发，最多 2 次）----
    private final Map<String, Runnable> pendingMids = new ConcurrentHashMap<>();
    private final Map<String, Integer> midTries = new ConcurrentHashMap<>();

    // ---- UI ----
    private LinearLayout root;
    private FrameLayout content;
    private TextView connDot;
    private TextView connText;
    private TextView connectBtn; // 顶部连接按钮（文本随状态变：连接/断开）
    private final Map<String, TextView> tabIcons = new HashMap<>(); // 0.5.0：Tab 图标（选中随主题变绿）
    private final Map<String, TextView> tabTexts = new HashMap<>();
    private final Map<String, View> tabBars = new HashMap<>();      // 0.5.0：Tab 顶部指示条（替代旧圆点）

    // ---- 页面 ----
    private ChatPage chatPage;
    private SessionsPage sessionsPage;
    private TasksPage tasksPage;
    private SettingsPage settingsPage;
    private FilesPage filesPage;
    private TracePage tracePage;       // v0.7.0：轨迹子页
    private TerminalPage terminalPage; // v0.7.0：远程终端子页
    private View currentPage;
    private View previousPage; // 文件子页返回目标

    // v0.7.0：图片 OCR（相册选图 → 转 base64 → ocr.image → 文本插入聊天输入框）
    private static final int REQ_IMAGE_PICK = 7001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // v0.6.3：强制内容不延伸到系统栏下（API 30+）。
        // 解决国产 ROM「全面屏强制显示」把未适配 App 的内容压到手势条下面 → 底部导航错位/被遮挡。
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
        }
        NotificationHelper.ensureChannel(this);
        // v0.6.0：前台保活（降低系统杀进程导致的断连），开关在设置页，默认开
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEPALIVE, true)) {
            startKeepAlive();
        }
        // v0.6.0：Android 13+ 通知权限（保活常驻通知可见性；拒绝不影响连接，仅通知不可见）
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
        buildUi();
        ensurePages();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        reconnectCount = prefs.getInt(KEY_RECONNECT_COUNT, 0); // v0.6.1 诊断
        String saved = prefs.getString(KEY_SERVER, null);
        if (saved == null || saved.equals("ws://localhost:8787/app")) {
            saved = ""; // 开源版：服务器地址由用户自行填写（设置页 → 服务器）
        }
        settingsPage.setServerUrl(saved);
        lastSessionId = prefs.getString(KEY_LAST_SESSION, "");
        String draft = prefs.getString(KEY_DRAFT, "");
        chatPage.restoreDraft(draft);
        switchTo(chatPage.view(), false);
        checkUpdateSilent();
    }

    /** v0.6.0：启动前台保活服务（幂等：已在运行则无害）。 */
    public void startKeepAlive() {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(new Intent(this, KeepAliveService.class));
            } else {
                startService(new Intent(this, KeepAliveService.class));
            }
        } catch (Exception e) {
            Log.w("MainActivity", "保活服务启动失败: " + e.getMessage());
        }
    }

    /** v0.6.0：停止保活服务（设置页开关关闭时）。 */
    public void stopKeepAlive() {
        try {
            stopService(new Intent(this, KeepAliveService.class));
        } catch (Exception e) {
            Log.w("MainActivity", "保活服务停止失败: " + e.getMessage());
        }
    }

    /** 版本更新（照搬晨曦AI）：启动时静默检查，有新版本弹窗提示。 */
    private void checkUpdateSilent() {
        String url = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SERVER, "");
        if (url.isEmpty()) return; // 未配置服务器地址时不检查更新
        final String base = httpBase(url);
        UpdateChecker.check(this, base, new UpdateChecker.Listener() {
            @Override
            public void onUpdate(int version, String name, String apkUrl, String logText) {
                showUpdateDialog(name, logText, apkUrl);
            }
            @Override public void onUpToDate() {}
            @Override public void onError(String msg) {} // 静默
        });
    }

    /** 更新弹窗：稍后 / 立即更新（下载 APK → 唤起安装器）。 */
    public void showUpdateDialog(String name, String logText, String apkUrl) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 " + name)
                .setMessage(logText == null || logText.isEmpty() ? "有新版本可用" : logText)
                .setPositiveButton("立即更新", (d, w) -> {
                    toast("正在下载更新…");
                    UpdateChecker.download(this, apkUrl, () -> {
                        ui.post(() -> {
                            toast("下载完成，准备安装");
                            UpdateChecker.install(this);
                        });
                    }, () -> ui.post(() -> toast("下载失败，请稍后重试")));
                })
                .setNegativeButton("稍后更新", null);
        try {
            b.show();
        } catch (Exception ignored) {}
    }

    // ---------------- UI ----------------

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(this));

        buildHeader();
        buildContent();
        buildTabs();

        setContentView(root);
        // v0.6.2：底部错位兜底——全面屏手势/系统导航栏遮挡时，根布局底部留出 inset 高度
        applyBottomInsetPadding(root);
    }

    /** 只处理底部系统栏 inset（手势条/导航栏遮挡），顶/左/右保持系统默认布局 */
    private void applyBottomInsetPadding(View target) {
        target.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, 0, 0, bottom);
            return insets;
        });
    }

    /** 顶部：品牌标题 + 连接状态 + 连接按钮（0.5.0 白绿品牌化，靠拢晨曦AI） */
    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 10), UiKit.dp(this, 16), UiKit.dp(this, 10));
        header.setBackgroundColor(Theme.card(this));

        connDot = new TextView(this);
        connDot.setText("●");
        connDot.setTextSize(14);
        connDot.setTextColor(Theme.sub(this));
        header.addView(connDot);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(UiKit.dp(this, 10), 0, 0, 0);
        // 品牌主标题（晨曦AI 式：粗体主标题在上，状态小字在下）
        TextView hostText = new TextView(this);
        hostText.setText("Harness助手");
        hostText.setTextSize(17);
        hostText.setTextColor(Theme.txt(this));
        hostText.setTypeface(null, Typeface.BOLD);
        hostText.setSingleLine(true);
        left.addView(hostText);

        connText = new TextView(this);
        connText.setText("未连接");
        connText.setTextSize(11);
        connText.setTextColor(Theme.sub(this));
        connText.setSingleLine(true);
        left.addView(connText);
        header.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView connectBtn = new TextView(this);
        connectBtn.setText("连接");
        connectBtn.setTextSize(13);
        connectBtn.setTextColor(Theme.onAccent(this));
        connectBtn.setGravity(Gravity.CENTER);
        connectBtn.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 8), UiKit.dp(this, 18), UiKit.dp(this, 8));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(UiKit.dp(this, 20));
        btnBg.setColor(Theme.accent(this));
        connectBtn.setBackground(btnBg);
        connectBtn.setClickable(true);
        this.connectBtn = connectBtn;
        connectBtn.setOnClickListener(v -> {
            if (connected) disconnect();
            else connect(settingsPage.getServerUrl());
        });
        header.addView(connectBtn);
        root.addView(header);

        View line = new View(this);
        line.setBackgroundColor(Theme.divider(this));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 1)));
    }

    private void buildContent() {
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    /** 底部导航：对话 / 会话 / 设置（0.5.0 按用户意见移除"任务"，晨曦AI 只有会话和对话） */
    private void buildTabs() {
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 6), UiKit.dp(this, 8), UiKit.dp(this, 8));
        tabs.setBackgroundColor(Theme.card(this));
        addTab(tabs, "chat", "◉", "对话", () -> switchTo(chatPage.view(), false));
        addTab(tabs, "sessions", "☰", "会话", () -> switchTo(sessionsPage.view(), false));
        addTab(tabs, "settings", "⚙", "设置", () -> switchTo(settingsPage.view(), false));
        root.addView(tabs);
    }

    /** 单个 Tab：顶部指示条 + 图标 + 文字（0.5.0 视觉升级，替代旧圆点） */
    private void addTab(LinearLayout tabs, String key, String icon, String label, Runnable action) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER_HORIZONTAL);
        tab.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 5), UiKit.dp(this, 6), UiKit.dp(this, 4));
        tab.setClickable(true);
        tab.setOnClickListener(v -> action.run());

        // 顶部指示条（选中态绿色，未选中透明）
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setShape(GradientDrawable.RECTANGLE);
        barBg.setCornerRadius(UiKit.dp(this, 2));
        barBg.setColor(0x00000000);
        bar.setBackground(barBg);
        tab.addView(bar, new LinearLayout.LayoutParams(UiKit.dp(this, 26), UiKit.dp(this, 3)));

        TextView iconTv = new TextView(this);
        iconTv.setText(icon);
        iconTv.setTextSize(16);
        iconTv.setTextColor(Theme.sub(this));
        iconTv.setGravity(Gravity.CENTER);
        iconTv.setPadding(0, UiKit.dp(this, 2), 0, 0);
        tab.addView(iconTv);

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(11);
        text.setTextColor(Theme.sub(this));
        tab.addView(text);

        tabIcons.put(key, iconTv);
        tabTexts.put(key, text);
        tabBars.put(key, bar);
        tabs.addView(tab, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    // ---------------- 页面管理 ----------------

    /** 切换主 Tab 页面 */
    public void switchTo(View page, boolean fromSub) {
        if (currentPage != null && currentPage == page && !fromSub) return;
        if (!fromSub && filesPage != null && currentPage == filesPage.view()) {
            filesPage.onHide();
        }
        content.removeAllViews();
        // 修复（全白根因）：addView 前必须恢复 VISIBLE——此前切走时 setVisibility(GONE)，
        // 切回来 addView 后 visibility 仍是 GONE → 内容区全白（从最初版本就存在）
        page.setVisibility(View.VISIBLE);
        content.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = page;
        previousPage = null;
        updateTabHighlight();
        if (page == chatPage.view()) chatPage.onShow();
        else if (page == sessionsPage.view()) sessionsPage.onShow();
        else if (page == tasksPage.view()) tasksPage.onShow();
        else if (page == settingsPage.view()) settingsPage.onShow();
    }

    /** 打开文件子页（全屏替换内容区，返回键回来源页） */
    public void openFiles(String cwd, String title) {
        if (filesPage == null) filesPage = new FilesPage(this);
        previousPage = currentPage;
        content.removeAllViews();
        filesPage.view().setVisibility(View.VISIBLE);
        content.addView(filesPage.view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = filesPage.view();
        filesPage.setWorkspace(cwd, title);
        filesPage.onShow();
    }

    /** 从文件子页返回 */
    public void closeFiles() {
        if (currentPage != filesPage.view()) return;
        filesPage.onHide();
        content.removeAllViews();
        View back = previousPage != null ? previousPage : chatPage.view();
        back.setVisibility(View.VISIBLE); // 同全白修复
        content.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = back;
        previousPage = null;
        updateTabHighlight();
    }

    // ---- v0.7.0：通用子页（轨迹 / 终端） ----

    /** 打开轨迹子页 */
    public void openTrace(String sessionId, String title) {
        if (tracePage == null) tracePage = new TracePage(this);
        previousPage = currentPage;
        content.removeAllViews();
        tracePage.view().setVisibility(View.VISIBLE);
        content.addView(tracePage.view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = tracePage.view();
        tracePage.setSession(sessionId, title);
        tracePage.onShow();
    }

    /** 打开远程终端子页 */
    public void openTerminal(String cwd, String title) {
        if (terminalPage == null) terminalPage = new TerminalPage(this);
        previousPage = currentPage;
        content.removeAllViews();
        terminalPage.view().setVisibility(View.VISIBLE);
        content.addView(terminalPage.view(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = terminalPage.view();
        terminalPage.setCwd(cwd, title);
        terminalPage.onShow();
    }

    /** 通用子页返回（轨迹/终端） */
    public void closeSubPage() {
        if (currentPage != tracePage.view() && currentPage != terminalPage.view()) return;
        if (currentPage == tracePage.view()) tracePage.onHide();
        if (currentPage == terminalPage.view()) terminalPage.onHide();
        content.removeAllViews();
        View back = previousPage != null ? previousPage : chatPage.view();
        back.setVisibility(View.VISIBLE);
        content.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        currentPage = back;
        previousPage = null;
        updateTabHighlight();
    }

    private void updateTabHighlight() {
        // 文件子页：保持原 Tab 高亮（由 previousPage 判断）
        if (filesPage != null && currentPage == filesPage.view()) {
            boolean sess = previousPage == sessionsPage.view();
            boolean chat = previousPage == chatPage.view();
            boolean sett = previousPage == settingsPage.view();
            setTabActive("chat", chat);
            setTabActive("sessions", sess);
            setTabActive("settings", sett);
            return;
        }
        boolean chat = currentPage == chatPage.view();
        boolean sess = currentPage == sessionsPage.view();
        boolean sett = currentPage == settingsPage.view();
        setTabActive("chat", chat);
        setTabActive("sessions", sess);
        setTabActive("settings", sett);
    }

    private void setTabActive(String key, boolean active) {
        TextView t = tabTexts.get(key);
        if (t == null) return;
        int color = active ? Theme.accent(this) : Theme.sub(this);
        t.setTextColor(color);
        t.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        TextView ic = tabIcons.get(key);
        if (ic != null) ic.setTextColor(color);
        View b = tabBars.get(key);
        if (b != null && b.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) b.getBackground()).setColor(active ? Theme.accent(this) : 0x00000000);
        }
    }

    // ---------------- 连接 ----------------

    public void connect(String url) {
        if (url == null || url.trim().isEmpty()) {
            toast("请先填写服务器地址");
            return;
        }
        // 清理旧实例（修复：重连前不关旧 ws → io/callback 线程泄漏 + 旧 socket 残留）
        if (ws != null) {
            try { ws.close(); } catch (Exception ignored) {}
            ws = null;
        }
        url = url.trim();
        manualDisconnect = false;
        lastUrl = url;
        reconnectAttempts = 0;
        // 保存原始地址（不带 token，避免污染设置页显示）
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SERVER, url).apply();
        // M3：已登录则自动携带 token（auth_required 时服务器强制校验）
        // P1 修复（token 泄露面）：token 走自定义握手头 x-dsh-token，不再拼 URL query（防 nginx 日志残留）
        String token = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, "");
        java.util.Map<String, String> headers = null;
        if (!token.isEmpty()) {
            headers = java.util.Collections.singletonMap("x-dsh-token", token);
        }
        setConnUi("连接中…", Theme.busy(this), false);
        devices.clear();
        currentDevice = null;

        ws = new WsClient(new WsClient.Listener() {
            @Override
            public void onOpen() {
                reconnectAttempts = 0;
                connectStartedAt = System.currentTimeMillis(); // v0.6.1 诊断：本次在线起点
                if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable); // 防旧定时器再触发双连接
                ui.post(() -> {
                    connected = true;
                    setConnUi("已连接 · 等待设备上线", Theme.busy(MainActivity.this), true);
                    chatPage.appendStatus("已连接服务器");
                });
            }

            @Override
            public void onMessage(String text) {
                handleMessage(text);
            }

            @Override
            public void onClose(int code, String reason) {
                ui.post(() -> {
                    connected = false;
                    // 修复：授权失败（1008，token 失效/未登录）→ 停止自动重连并提示重新登录
                    // （此前无限重连，体验上"一直连接失败又一直重试"）
                    if (code == 1008) {
                        if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable);
                        String why = (reason == null || reason.isEmpty()) ? "登录已失效" : reason;
                        setConnUi("登录失效 · 请到设置重新登录", Theme.errTxt(MainActivity.this), false);
                        chatPage.appendStatus("连接被拒绝(" + why + ")");
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_ERR, why).apply();
                        toast("登录已失效，请到设置页重新登录");
                        return;
                    }
                    String why = "code=" + code + (reason == null || reason.isEmpty() ? "" : " " + reason);
                    setConnUi("连接断开", Theme.errTxt(MainActivity.this), false);
                    chatPage.appendStatus("连接断开(" + why + ")");
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_ERR, why).apply();
                    scheduleReconnect();
                });
            }

            @Override
            public void onError(String message) {
                ui.post(() -> {
                    connected = false;
                    String why = message == null ? "(无详细信息)" : message;
                    setConnUi("连接失败", Theme.errTxt(MainActivity.this), false);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_ERR, why).apply();
                    toast("连接失败: " + why);
                    scheduleReconnect();
                });
            }
        });
        ws.connect(url, headers);
    }

    /** 自动重连（指数退避 3/6/12/24/30s，手动断开不重连）。旧定时器先取消，防双连接。 */
    private void scheduleReconnect() {
        if (manualDisconnect) return;
        reconnectCount++; // v0.6.1 诊断：累计自动重连次数
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_RECONNECT_COUNT, reconnectCount).apply();
        int delay = Math.min(30_000, 3000 * (1 << Math.min(reconnectAttempts, 3)));
        reconnectAttempts++;
        if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable);
        reconnectRunnable = () -> {
            if (!manualDisconnect && !lastUrl.isEmpty()) {
                chatPage.appendStatus("自动重连…");
                connect(lastUrl);
            }
        };
        ui.postDelayed(reconnectRunnable, delay);
    }

    public void disconnect() {
        manualDisconnect = true;
        if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable);
        if (ws != null) ws.close();
        connected = false;
        setConnUi("未连接", Theme.sub(this), false);
        chatPage.appendStatus("已断开");
    }

    // ---- v0.6.1 连接诊断（设置页展示） ----

    public int getReconnectCount() {
        return reconnectCount;
    }

    /** 本次已在线秒数（未连接/刚断开返回 0）。 */
    public long getOnlineSeconds() {
        if (connectStartedAt <= 0) return 0;
        return Math.max(0, (System.currentTimeMillis() - connectStartedAt) / 1000);
    }

    // ---------------- M3 账号 ----------------

    public String authToken() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TOKEN, "");
    }

    public String authEmail() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_EMAIL, "");
    }

    public void saveLogin(String token, String email) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_TOKEN, token).putString(KEY_EMAIL, email).apply();
    }

    public void logout() {
        manualDisconnect = true; // 修复：登出后不自动重连
        if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .remove(KEY_TOKEN).remove(KEY_EMAIL).apply();
        if (ws != null) ws.close();
        connected = false;
        setConnUi("未连接", Theme.sub(this), false);
    }

    /** 本机唯一标识（首次生成并持久化，注册/登录用；UUID 防重装变化/同毫秒碰撞） */
    public String phoneId() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String id = p.getString(KEY_PHONE_ID, "");
        if (id.isEmpty()) {
            id = "ph_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            p.edit().putString(KEY_PHONE_ID, id).apply();
        }
        return id;
    }

    /** 从 WS 地址推导 HTTP API 基址：wss://host/relay/app -> https://host/relay */
    public static String httpBase(String serverUrl) {
        String u = serverUrl == null ? "" : serverUrl.trim();
        u = u.replace("wss://", "https://").replace("ws://", "http://");
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/app")) u = u.substring(0, u.length() - 4);
        else if (u.endsWith("/device")) u = u.substring(0, u.length() - 7);
        return u;
    }

    private void setConnUi(String text, int color, boolean online) {
        connText.setText(text);
        connText.setTextColor(color);
        connDot.setTextColor(color);
        // 修复：按钮文本随状态变化（此前恒为"连接"，已连接时点击实际是断开，造成困惑）
        if (connectBtn != null) connectBtn.setText(online ? "断开" : "连接");
        settingsPage.onConnChanged(connected, devices, currentDevice);
    }

    // ---------------- 协议分发 ----------------

    private void handleMessage(String raw) {
        try {
            JSONObject msg = new JSONObject(raw);
            String op = msg.optString("op", "");
            switch (op) {
                case "device.online": {
                    String id = msg.optString("device_id", "");
                    String name = msg.optString("name", id);
                    devices.put(id, name);
                    if (currentDevice == null) currentDevice = id;
                    ui.post(() -> {
                        setConnUi("设备: " + name, Theme.accent(this), true);
                        chatPage.appendStatus("设备上线: " + name);
                        sessionsPage.onDeviceChanged();
                    });
                    break;
                }
                case "device.offline": {
                    String id = msg.optString("device_id", "");
                    devices.remove(id);
                    if (id.equals(currentDevice)) {
                        currentDevice = devices.keySet().stream().findFirst().orElse(null);
                    }
                    ui.post(() -> {
                        chatPage.appendStatus("设备离线: " + id);
                        sessionsPage.onDeviceChanged();
                    });
                    break;
                }
                case "chat.ack": {
                    String mid = msg.optString("mid", "");
                    Runnable timer = pendingMids.remove(mid);
                    if (timer != null) ui.removeCallbacks(timer);
                    midTries.remove(mid);
                    break;
                }
                case "event":
                    ui.post(() -> chatPage.onEvent(msg.optJSONObject("event")));
                    break;
                case "user.question": {
                    // v0.6.4：AI 调用 ask_user_question → 渲染可交互提问卡片
                    JSONArray questions = msg.optJSONArray("questions");
                    final String qid = msg.optString("request_id", "");
                    final String qsid = msg.optString("session_id", "");
                    ui.post(() -> chatPage.showUserQuestions(qid, qsid, questions));
                    break;
                }
                case "user.answer.ack":
                    ui.post(() -> chatPage.markQuestionsSubmitted("已提交，等待回复…"));
                    break;
                case "user.answer.error":
                    ui.post(() -> chatPage.markQuestionsSubmitted("提交失败，可重试"));
                    break;
                case "session.status":
                    ui.post(() -> chatPage.onStatus(msg.optString("status", "?")));
                    break;
                case "run.done": {
                    String sid = msg.optString("session_id", "");
                    if (!sid.isEmpty()) {
                        lastSessionId = sid;
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_SESSION, sid).apply();
                        if (!currentChatSession().equals(sid)) unread.add(sid);
                    }
                    String finalText = msg.optString("final", "");
                    final String doneSid = sid;
                    ui.post(() -> {
                        // 修复：setSessionId 在 UI 线程执行（原在回调线程直接操作 View）
                        if (!doneSid.isEmpty() && chatPage.currentSessionId().isEmpty()) chatPage.setSessionId(doneSid);
                        chatPage.onDone(finalText);
                        sessionsPage.onDeviceChanged();
                    });
                    break;
                }
                case "run.error": {
                    String error = msg.optString("error", "未知错误");
                    ui.post(() -> {
                        chatPage.onError(error);
                        NotificationHelper.show(this, "Harness助手 · 任务失败", error.length() > 60 ? error.substring(0, 60) + "…" : error);
                    });
                    break;
                }
                case "session.list.result":
                    ui.post(() -> sessionsPage.onListResult(msg.optJSONArray("sessions")));
                    break;
                case "session.list.error":
                    ui.post(() -> sessionsPage.onError(msg.optString("error", "未知错误")));
                    break;
                case "session.rename.result":
                    ui.post(() -> {
                        toast("已重命名");
                        sessionsPage.refresh();
                    });
                    break;
                case "session.rename.error":
                    ui.post(() -> toast("重命名失败: " + msg.optString("error", "未知错误")));
                    break;
                case "session.delete.result": {
                    String sid = msg.optString("session_id", "");
                    if (!sid.isEmpty() && sid.equals(lastSessionId)) {
                        lastSessionId = "";
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_SESSION, "").apply();
                    }
                    unread.remove(sid);
                    ui.post(() -> {
                        toast("已删除");
                        sessionsPage.refresh();
                    });
                    break;
                }
                case "session.delete.error":
                    ui.post(() -> toast("删除失败: " + msg.optString("error", "未知错误")));
                    break;
                case "session.create.result": {
                    String sid = msg.optString("session_id", "");
                    lastSessionId = sid;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_SESSION, sid).apply();
                    ui.post(() -> {
                        toast("已新建会话");
                        chatPage.setSessionId(sid);
                        switchTo(chatPage.view(), false);
                        sessionsPage.refresh();
                    });
                    break;
                }
                case "session.create.error":
                    ui.post(() -> toast("新建失败: " + msg.optString("error", "未知错误")));
                    break;
                case "session.messages.result": {
                    // 修复：校验结果属于当前会话（防快速切会话时旧结果覆盖新历史）
                    final String rsid = msg.optString("session_id", "");
                    ui.post(() -> {
                        if (rsid.isEmpty() || rsid.equals(chatPage.currentSessionId())) {
                            chatPage.onHistoryResult(msg.optJSONArray("messages"));
                        }
                    });
                    break;
                }
                case "session.messages.error":
                    ui.post(() -> chatPage.onHistoryError(msg.optString("error", "未知错误")));
                    break;
                case "task.list.result":
                    ui.post(() -> tasksPage.onListResult(msg.optJSONArray("tasks")));
                    break;
                case "task.stop.result":
                    ui.post(() -> {
                        toast("已停止任务");
                        tasksPage.refresh();
                    });
                    break;
                case "task.stop.error":
                    ui.post(() -> toast("停止失败: " + msg.optString("error", "未知错误")));
                    break;
                case "file.list.result":
                    if (filesPage == null) break;
                    ui.post(() -> filesPage.onListResult(msg));
                    break;
                case "file.read.result":
                    if (filesPage == null) break;
                    ui.post(() -> filesPage.onReadResult(msg));
                    break;
                case "file.write.result":
                    if (filesPage == null) break;
                    ui.post(() -> filesPage.onWriteResult(msg));
                    break;
                case "file.download.result":
                    if (filesPage == null) break;
                    ui.post(() -> filesPage.onDownloadResult(msg));
                    break;
                case "file.list.error":
                case "file.read.error":
                case "file.write.error":
                case "file.download.error":
                    if (filesPage == null) break;
                    ui.post(() -> filesPage.onError(msg));
                    break;
                // ---- v0.7.0：模型切换 ----
                case "model.list.result":
                    if (settingsPage == null) break;
                    ui.post(() -> settingsPage.onModelList(msg));
                    break;
                case "model.list.error":
                    ui.post(() -> toast("获取模型失败: " + msg.optString("error", "未知错误")));
                    break;
                case "model.set.result": {
                    final String m = msg.optString("model", "");
                    ui.post(() -> {
                        toast("已切换模型: " + m);
                        if (settingsPage != null) settingsPage.onModelChanged(msg);
                    });
                    break;
                }
                case "model.set.error":
                    ui.post(() -> toast("切换模型失败: " + msg.optString("error", "未知错误")));
                    break;
                // ---- v0.7.0：远程终端 ----
                case "terminal.exec.result":
                    if (terminalPage == null) break;
                    ui.post(() -> terminalPage.onExecResult(msg));
                    break;
                case "terminal.exec.error":
                    if (terminalPage == null) break;
                    ui.post(() -> terminalPage.onExecError(msg));
                    break;
                // ---- v0.7.0：会话分支 ----
                case "session.fork.result": {
                    final String fsid = msg.optString("session_id", "");
                    ui.post(() -> {
                        toast("已分支新会话");
                        if (!fsid.isEmpty()) {
                            lastSessionId = fsid;
                            chatPage.setSessionId(fsid);
                            switchTo(chatPage.view(), false);
                            sessionsPage.refresh();
                        }
                    });
                    break;
                }
                case "session.fork.error":
                    ui.post(() -> toast("分支失败: " + msg.optString("error", "未知错误")));
                    break;
                // ---- v0.7.0：轨迹面板 ----
                case "session.trace.result":
                    if (tracePage == null) break;
                    ui.post(() -> tracePage.onTraceResult(msg));
                    break;
                case "session.trace.error":
                    if (tracePage == null) break;
                    ui.post(() -> tracePage.onTraceError(msg));
                    break;
                // ---- v0.7.0：图片 OCR ----
                case "ocr.image.result": {
                    final String text = msg.optString("text", "");
                    ui.post(() -> {
                        if (text.isEmpty()) {
                            toast("OCR 未识别到文字");
                        } else {
                            if (chatPage != null) chatPage.insertOcrText(text);
                            toast("OCR 识别完成，已插入输入框");
                        }
                    });
                    break;
                }
                case "ocr.image.error":
                    ui.post(() -> toast("OCR 失败: " + msg.optString("error", "未知错误")));
                    break;
                case "error": {
                    String error = msg.optString("message", "服务器错误");
                    ui.post(() -> chatPage.onError(error));
                    break;
                }
                default:
                    // 未识别 op 静默忽略（协议 v2 §7）
            }
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "协议解析失败: " + e.getMessage() + "\n" + raw);
        }
    }

    // ---------------- 发送（Q4 幂等重发） ----------------

    /** 发协议消息（统一出口）。 */
    public void send(JSONObject req) {
        if (ws == null || !connected) {
            toast("请先连接服务器");
            return;
        }
        if (currentDevice == null) {
            toast("没有在线设备");
            return;
        }
        try {
            req.put("device_id", currentDevice);
            ws.send(req.toString());
        } catch (Exception e) {
            // 修复：e.getMessage() 可能为 null（如 NPE）→ 显示"发送失败: null"；兜底为可读文案
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** v0.6.4：提交 ask_user_question 答案（AI 挂起等待中，需尽快发送） */
    public void sendUserAnswer(String requestId, JSONArray answers) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "user.answer");
            req.put("request_id", requestId);
            req.put("answers", answers);
            send(req);
        } catch (Exception e) {
            toast("提交失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** v0.6.7: 停止当前会话生成（对应 web 端"停止"按钮） */
    public void stopCurrent() {
        String sid = chatPage == null ? "" : chatPage.currentSessionId();
        if (sid == null || sid.isEmpty()) {
            toast("当前无会话");
            return;
        }
        JSONObject req = new JSONObject();
        try {
            req.put("op", "task.stop");
            req.put("session_id", sid);
            send(req);
        } catch (Exception e) {
            toast("停止失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    // ---- v0.7.0：新能力发送 ----

    /** 获取桌面伴侣模型配置（model.list） */
    public void sendModelList() {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "model.list");
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 切换模型（新会话生效，model.set） */
    public void sendModelSet(String provider, String model) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "model.set");
            if (provider != null && !provider.isEmpty()) req.put("provider", provider);
            if (model != null && !model.isEmpty()) req.put("model", model);
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 远程执行命令（terminal.exec） */
    public void sendTerminalExec(String cwd, String command, boolean confirmed) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "terminal.exec");
            req.put("cwd", cwd == null ? "" : cwd);
            req.put("command", command == null ? "" : command);
            if (confirmed) req.put("confirmed", true);
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 会话分支（session.fork） */
    public void sendSessionFork(String sessionId) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "session.fork");
            req.put("session_id", sessionId);
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 会话轨迹（session.trace） */
    public void sendSessionTrace(String sessionId) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "session.trace");
            req.put("session_id", sessionId);
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    /** 图片 OCR（ocr.image） */
    public void sendOcrImage(String base64, String name) {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "ocr.image");
            req.put("base64", base64);
            if (name != null && !name.isEmpty()) req.put("name", name);
            send(req);
        } catch (Exception e) {
            toast("发送失败: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    // ---- v0.7.0：相册选图 → OCR ----

    /** 打开系统图片选择器（OCR 入口，ChatPage 图片按钮调用） */
    public void openImagePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, REQ_IMAGE_PICK);
        } catch (Exception e) {
            toast("无法打开相册: " + (e == null || e.getMessage() == null ? "未知错误" : e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMAGE_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                Bitmap bmp = null;
                String name = "image.png";
                try {
                    android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0) name = cursor.getString(idx);
                        cursor.close();
                    }
                } catch (Exception ignored) {
                }
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("无法读取图片");
                    // 解码边界（只读尺寸，不整图加载）
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeStream(in, null, opts);
                    int inSample = 1;
                    int maxSide = Math.max(opts.outWidth, opts.outHeight);
                    while (maxSide / (inSample * 2) >= 1200) inSample *= 2;
                    opts.inSampleSize = inSample;
                    opts.inJustDecodeBounds = false;
                    // 重新开流解码
                    try (InputStream in2 = getContentResolver().openInputStream(uri)) {
                        bmp = BitmapFactory.decodeStream(in2, null, opts);
                    }
                }
                if (bmp == null) throw new IOException("解码图片失败");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
                byte[] bytes = out.toByteArray();
                if (bytes.length > 12 * 1024 * 1024) {
                    ui.post(() -> toast("图片过大，请选小图"));
                    return;
                }
                final String b64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
                final String fname = name;
                ui.post(() -> {
                    toast("图片识别中…");
                    sendOcrImage(b64, fname);
                });
            } catch (Exception e) {
                final String em = (e == null || e.getMessage() == null ? "未知错误" : e.getMessage());
                ui.post(() -> toast("读取图片失败: " + em));
            }
        }).start();
    }

    /** v0.6.7: 复制文本到剪贴板（长按消息） */
    public void copyToClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("harness", text == null ? "" : text));
            toast("已复制");
        } catch (Exception e) {
            toast("复制失败");
        }
    }

    /** chat.send：带 mid，收 ack 取消重发；超时自动重发（最多 2 次）。 */
    public void sendPrompt(String content, String sessionId) {
        final String mid = "m_" + System.currentTimeMillis();
        Runnable send = new Runnable() {
            @Override
            public void run() {
                JSONObject req = new JSONObject();
                try {
                    req.put("op", "chat.send");
                    if (sessionId != null && !sessionId.isEmpty()) req.put("session_id", sessionId);
                    req.put("content", content);
                    req.put("mid", mid);
                    send(req);
                } catch (Exception e) {
                    toast("发送失败: " + e.getMessage());
                    return;
                }
                int tries = midTries.getOrDefault(mid, 0);
                midTries.put(mid, tries + 1);
                if (tries >= 2) {
                    pendingMids.remove(mid);
                    midTries.remove(mid);
                    return;
                }
                Runnable timer = () -> {
                    if (pendingMids.containsKey(mid)) {
                        toast("网络慢，重试中…");
                        run();
                    }
                };
                pendingMids.put(mid, timer);
                ui.postDelayed(timer, 5000);
            }
        };
        send.run();
    }

    /** 高危关键词检测（F15：发送前确认，对齐 Web approval=ask）。 */
    public static boolean isRisky(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        String[] keywords = {
                "delete", "rm ", " rmdir", "del ", "format", "清空", "删除", "覆盖",
                "drop ", "truncate", "install -y", "apt purge", "chmod 777", "rm -rf",
                "shutdown", "reboot", "格式化", "清库", "重置", "卸载",
        };
        for (String k : keywords) {
            if (t.contains(k)) return true;
        }
        return false;
    }

    // ---------------- 会话/未读工具 ----------------

    private String currentChatSession() {
        return chatPage.currentSessionId();
    }

    public boolean isUnread(String sessionId) {
        return unread.contains(sessionId);
    }

    public void markRead(String sessionId) {
        if (sessionId != null) unread.remove(sessionId);
    }

    public String currentDevice() { return currentDevice; }
    public boolean isConnected() { return connected; }
    public String deviceName(String id) { return devices.getOrDefault(id, id); }

    // ---------------- 页面访问 ----------------

    private void ensurePages() {
        if (chatPage == null) chatPage = new ChatPage(this);
        if (sessionsPage == null) sessionsPage = new SessionsPage(this);
        if (tasksPage == null) tasksPage = new TasksPage(this);
        if (settingsPage == null) settingsPage = new SettingsPage(this);
    }

    public ChatPage chat() { ensurePages(); return chatPage; }
    public SessionsPage sessions() { ensurePages(); return sessionsPage; }
    public TasksPage tasks() { ensurePages(); return tasksPage; }
    public SettingsPage settings() { ensurePages(); return settingsPage; }

    public void toast(String text) {
        // 修复：线程安全（回调线程也可能调用）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } else {
            ui.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onBackPressed() {
        if (filesPage != null && currentPage == filesPage.view()) {
            closeFiles();
            return;
        }
        if (tracePage != null && currentPage == tracePage.view()) {
            closeSubPage();
            return;
        }
        if (terminalPage != null && currentPage == terminalPage.view()) {
            closeSubPage();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 修复：清理重连定时器与重发任务（防 Activity 销毁后仍在后台重连/重发）
        if (reconnectRunnable != null) ui.removeCallbacks(reconnectRunnable);
        for (Runnable r : pendingMids.values()) ui.removeCallbacks(r);
        pendingMids.clear();
        midTries.clear();
        if (ws != null) ws.close();
    }
}
