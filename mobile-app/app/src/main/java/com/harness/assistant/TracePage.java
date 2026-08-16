package com.harness.assistant;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轨迹面板（v0.7.0）：会话完整时间线（用户/助手/思考/工具调用/步骤，含工具耗时）。
 * 数据源：桌面伴侣 session/trace（解析 dsh 会话事件，对齐 DeepSeek Phone Harness 轨迹面板）。
 */
public class TracePage {

    private final MainActivity act;
    private LinearLayout root;
    private TextView titleText;
    private LinearLayout body;

    private String sessionId = "";

    public TracePage(MainActivity act) {
        this.act = act;
        build();
    }

    public View view() { return root; }

    private void build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(act));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(UiKit.dp(act, 8), UiKit.dp(act, 8), UiKit.dp(act, 16), UiKit.dp(act, 8));

        TextView back = new TextView(act);
        back.setText("‹ 返回");
        back.setTextSize(14);
        back.setTextColor(Theme.accent(act));
        back.setGravity(Gravity.CENTER);
        back.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 6), UiKit.dp(act, 10), UiKit.dp(act, 6));
        back.setClickable(true);
        back.setOnClickListener(v -> act.closeSubPage());
        head.addView(back);

        titleText = new TextView(act);
        titleText.setText("轨迹");
        titleText.setTextSize(14);
        titleText.setTextColor(Theme.txt(act));
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(head);

        body = new LinearLayout(act);
        body.setOrientation(LinearLayout.VERTICAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    public void setSession(String sessionId, String title) {
        this.sessionId = sessionId == null ? "" : sessionId;
        String shortTitle = title;
        if (shortTitle == null || shortTitle.isEmpty()) shortTitle = "会话";
        if (shortTitle.length() > 16) shortTitle = shortTitle.substring(0, 16) + "…";
        titleText.setText("轨迹 · " + shortTitle);
        load();
    }

    public void onShow() { }

    public void onHide() { }

    private void load() {
        body.removeAllViews();
        body.addView(hintRow("加载中…"));
        if (sessionId.isEmpty()) {
            body.removeAllViews();
            body.addView(hintRow("无会话"));
            return;
        }
        act.sendSessionTrace(sessionId);
    }

    public void onTraceResult(JSONObject msg) {
        body.removeAllViews();
        JSONArray entries = msg.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            body.addView(hintRow("暂无轨迹"));
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e != null) body.addView(entryRow(e));
        }
    }

    public void onTraceError(String error) {
        body.removeAllViews();
        body.addView(hintRow("加载失败: " + (error == null ? "未知错误" : error)));
    }

    // ---------------- 渲染 ----------------

    private TextView hintRow(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Theme.sub(act));
        t.setGravity(Gravity.CENTER);
        t.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 24), UiKit.dp(act, 16), UiKit.dp(act, 24));
        return t;
    }

    private View entryRow(JSONObject e) {
        String type = e.optString("type", "assistant");
        String title = e.optString("title", "");
        String detail = e.optString("detail", "");
        long timeMs = e.optLong("time", 0);
        boolean hasDuration = e.has("durationMs") && !e.isNull("durationMs");
        long durationMs = e.optLong("durationMs", 0);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));

        // 左侧色条 + 内容
        LinearLayout inner = new LinearLayout(act);
        inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.TOP);

        View bar = new View(act);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(typeColor(type));
        barBg.setCornerRadius(UiKit.dp(act, 2));
        bar.setBackground(barBg);
        inner.addView(bar, new LinearLayout.LayoutParams(UiKit.dp(act, 4), UiKit.dp(act, 34)));

        LinearLayout textBox = new LinearLayout(act);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setPadding(UiKit.dp(act, 10), 0, 0, 0);

        // 标题行：标题 + 时间 + 耗时
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleTv = new TextView(act);
        titleTv.setText(title);
        titleTv.setTextSize(13);
        titleTv.setTextColor(Theme.txt(act));
        titleTv.setTypeface(null, Typeface.BOLD);
        titleRow.addView(titleTv);

        TextView metaTv = new TextView(act);
        StringBuilder meta = new StringBuilder();
        meta.append("  ").append(formatTime(timeMs));
        if (hasDuration) {
            meta.append(" · ").append(formatDuration(durationMs));
        }
        metaTv.setText(meta.toString());
        metaTv.setTextSize(11);
        metaTv.setTextColor(Theme.sub(act));
        titleRow.addView(metaTv);

        textBox.addView(titleRow);

        if (detail != null && !detail.isEmpty()) {
            TextView detailTv = new TextView(act);
            String d = detail;
            if (d.length() > 500) d = d.substring(0, 500) + "…";
            detailTv.setText(d);
            detailTv.setTextSize(12);
            detailTv.setTextColor(Theme.sub(act));
            detailTv.setPadding(0, UiKit.dp(act, 3), 0, 0);
            textBox.addView(detailTv);
        }

        inner.addView(textBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(inner);

        // 分隔线
        View line = new View(act);
        line.setBackgroundColor(Theme.divider(act));
        row.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return row;
    }

    private int typeColor(String type) {
        switch (type) {
            case "user": return 0xFF4FC3F7;      // 蓝
            case "assistant": return 0xFF66BB6A; // 绿
            case "reasoning": return 0xFFFFB74D; // 橙
            case "tool": return 0xFFAB47BC;      // 紫
            case "tool-result": return 0xFF7E57C2;
            case "step": return 0xFFFF7043;      // 深橙
            case "step-end": return 0xFF8D6E63;
            default: return Theme.sub(act);
        }
    }

    private String formatTime(long timeMs) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(timeMs));
        } catch (Exception e) {
            return "";
        }
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        long min = sec / 60;
        return min + "m" + (sec % 60) + "s";
    }
}
