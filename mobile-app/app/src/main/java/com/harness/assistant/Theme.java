package com.harness.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Harness助手 主题：白绿浅色 + 深色（仿 Trae 深色系）。
 * v0.6.7：恢复深色模式支持——设置页可选 深色/浅色/跟随系统（SettingsPage 主题菜单），
 * 切换后 Activity.recreate() 整体换肤；浅色色板为品牌白绿色。
 */
public final class Theme {

    private static final String KEY = "theme";   // "dark" / "light" / "auto"（默认跟随系统）

    public static boolean isDark(Context ctx) {
        String m = mode(ctx);
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        int uiMode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String mode(Context ctx) {
        return ctx.getSharedPreferences("harness_assistant", Context.MODE_PRIVATE).getString(KEY, "auto");
    }

    public static void setMode(Context ctx, String mode) {
        ctx.getSharedPreferences("harness_assistant", Context.MODE_PRIVATE).edit().putString(KEY, mode).apply();
    }

    public static String modeName(Context ctx) {
        String m = mode(ctx);
        if ("dark".equals(m)) return "深色";
        if ("light".equals(m)) return "浅色";
        return "跟随系统";
    }

    // ---------------- 深色（仿 Trae 深色系 —— 黑灰主调 + Trae 绿点缀 + 白/浅灰字） ----------------
    static final int L_BG = 0xFF0F1115;
    static final int L_CARD = 0xFF17191E;
    static final int L_TXT = 0xFFFFFFFF;
    static final int L_SUB = 0xFFA1A7B0;
    static final int L_ACCENT = 0xFF00E676;
    static final int L_ON_ACCENT = 0xFF04281A;
    static final int L_INPUT = 0xFF1F2228;
    static final int L_USER_BUBBLE = 0xFF1F2228;
    static final int L_TOOL_BG = 0xFF1F2228;
    static final int L_TOOL_TXT = 0xFFD4D4D4;
    static final int L_ERR_BG = 0xFF2A1A1C;
    static final int L_ERR_TXT = 0xFFF14C4C;
    static final int L_DIVIDER = 0xFF2A2D33;
    static final int L_BORDER = 0xFF2A2D33;
    static final int L_RIBBLE = 0xFF272A31;
    /** M2（F19）：未读徽标红点。 */
    static final int L_BADGE = 0xFFF14C4C;
    /** M2（Q5）：连接中/运行中（琥珀）。 */
    static final int L_BUSY = 0xFFFFB74D;

    // ---------------- 浅色（品牌白绿） ----------------
    static final int S_BG = 0xFFF6F8F7;          // 页面底色（浅灰白）
    static final int S_CARD = 0xFFFFFFFF;        // 卡片/顶栏（纯白）
    static final int S_TXT = 0xFF14201A;         // 主文字（近黑）
    static final int S_SUB = 0xFF5A6A63;         // 副文字（中灰绿）
    static final int S_ACCENT = 0xFF00B36B;      // 强调绿（官网主色）
    static final int S_ON_ACCENT = 0xFFFFFFFF;   // 强调色上文字（白，官网按钮风格）
    static final int S_INPUT = 0xFFFFFFFF;       // 输入框底（白）
    static final int S_USER_BUBBLE = 0xFFE8F9F0; // 用户气泡（浅绿）
    static final int S_TOOL_BG = 0xFFF0F5F2;     // 工具气泡/卡片（浅灰绿）
    static final int S_TOOL_TXT = 0xFF3A4240;
    static final int S_ERR_BG = 0xFFFDECEC;      // 错误气泡（浅红）
    static final int S_ERR_TXT = 0xFFE53935;     // 错误文字（红）
    static final int S_DIVIDER = 0xFFE4EAE6;     // 分隔线（浅灰绿）
    static final int S_BORDER = 0xFFE4EAE6;      // 卡片描边（浅灰绿）
    static final int S_RIBBLE = 0xFFE9EFEB;      // 波纹/按压（浅灰绿）

    private static boolean d(Context c) { return isDark(c); }

    public static int bg(Context c)       { return d(c) ? L_BG : S_BG; }
    public static int card(Context c)     { return d(c) ? L_CARD : S_CARD; }
    public static int txt(Context c)      { return d(c) ? L_TXT : S_TXT; }
    public static int sub(Context c)      { return d(c) ? L_SUB : S_SUB; }
    public static int accent(Context c)   { return d(c) ? L_ACCENT : S_ACCENT; }
    public static int onAccent(Context c) { return d(c) ? L_ON_ACCENT : S_ON_ACCENT; }
    public static int inputBg(Context c)  { return d(c) ? L_INPUT : S_INPUT; }
    public static int userBubble(Context c) { return d(c) ? L_USER_BUBBLE : S_USER_BUBBLE; }
    public static int toolBg(Context c)   { return d(c) ? L_TOOL_BG : S_TOOL_BG; }
    public static int toolTxt(Context c)  { return d(c) ? L_TOOL_TXT : S_TOOL_TXT; }
    public static int errBg(Context c)    { return d(c) ? L_ERR_BG : S_ERR_BG; }
    public static int errTxt(Context c)   { return d(c) ? L_ERR_TXT : S_ERR_TXT; }
    public static int divider(Context c)  { return d(c) ? L_DIVIDER : S_DIVIDER; }
    public static int border(Context c)   { return d(c) ? L_BORDER : S_BORDER; }
    public static int ripple(Context c)   { return d(c) ? L_RIBBLE : S_RIBBLE; }
    public static int link(Context c)     { return d(c) ? L_ACCENT : S_ACCENT; }
    public static int badge(Context c)    { return L_BADGE; }
    public static int busy(Context c)     { return L_BUSY; }

    private Theme() {}
}
