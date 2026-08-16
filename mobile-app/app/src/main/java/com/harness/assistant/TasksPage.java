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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 任务页（M2 / F21 多任务 + F10 停止）：列出工作站正在运行的任务，可一键停止。
 */
public class TasksPage {

    private final MainActivity act;
    private LinearLayout root;
    private LinearLayout listBox;
    private TextView emptyHint;
    private final List<JSONObject> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public TasksPage(MainActivity act) {
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
        head.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 10), UiKit.dp(act, 16), UiKit.dp(act, 10));

        TextView title = new TextView(act);
        title.setText("任务");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.txt(act));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView refreshBtn = new TextView(act);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(13);
        refreshBtn.setTextColor(Theme.accent(act));
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 6), UiKit.dp(act, 14), UiKit.dp(act, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 16));
        bg.setColor(Theme.inputBg(act));
        bg.setStroke(UiKit.dp(act, 1), Theme.border(act));
        refreshBtn.setBackground(bg);
        refreshBtn.setClickable(true);
        refreshBtn.setOnClickListener(v -> refresh());
        head.addView(refreshBtn);
        root.addView(head);

        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(UiKit.dp(act, 12), 0, UiKit.dp(act, 12), UiKit.dp(act, 12));
        root.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        emptyHint = new TextView(act);
        emptyHint.setText("暂无运行中的任务");
        emptyHint.setTextSize(13);
        emptyHint.setTextColor(Theme.sub(act));
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(0, UiKit.dp(act, 40), 0, 0);
        root.addView(emptyHint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyHint.setVisibility(View.GONE);
    }

    // ---------------- 操作 ----------------

    public void refresh() {
        if (!act.isConnected()) {
            act.toast("未连接服务器");
            return;
        }
        JSONObject req = new JSONObject();
        try {
            req.put("op", "task.list");
            act.send(req);
        } catch (Exception e) {
            act.toast("请求失败: " + e.getMessage());
        }
    }

    private void stopTask(JSONObject t) {
        String sid = t.optString("sessionId", t.optString("session_id", ""));
        if (sid.isEmpty()) return;
        UiKit.confirm(act, "停止任务", "确定停止该任务？当前进度将丢失。",
                "停止", true, () -> {
                    JSONObject req = new JSONObject();
                    try {
                        req.put("op", "task.stop");
                        req.put("session_id", sid);
                        act.send(req);
                    } catch (Exception e) {
                        act.toast("停止失败: " + e.getMessage());
                    }
                });
    }

    // ---------------- 渲染 ----------------

    public void onListResult(JSONArray arr) {
        items.clear();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) items.add(o);
        }
        rebuild();
    }

    private void rebuild() {
        listBox.removeAllViews();
        if (items.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            emptyHint.setText("暂无运行中的任务");
            return;
        }
        emptyHint.setVisibility(View.GONE);
        for (int i = 0; i < items.size(); i++) {
            listBox.addView(buildRow(items.get(i)));
            if (i < items.size() - 1) {
                View line = new View(act);
                line.setBackgroundColor(Theme.divider(act));
                listBox.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(act, 1)));
            }
        }
    }

    private View buildRow(JSONObject t) {
        String sid = t.optString("session_id", t.optString("sessionId", ""));
        String status = t.optString("status", "?");
        String lastEvent = t.optString("last_event", t.optString("lastEvent", ""));
        long startedAt = t.optLong("started_at", t.optLong("startedAt", 0));

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(mid);

        TextView titleT = new TextView(act);
        titleT.setText(sid.isEmpty() ? "(新会话)" : (sid.length() > 16 ? sid.substring(0, 16) + "…" : sid));
        titleT.setTextSize(14);
        titleT.setTextColor(Theme.txt(act));
        titleT.setSingleLine(true);
        mid.addView(titleT);

        String sub = "状态: " + status;
        if (!lastEvent.isEmpty()) sub += " · " + lastEvent;
        if (startedAt > 0) sub += " · " + timeFmt.format(new Date(startedAt));
        TextView subT = new TextView(act);
        subT.setText(sub);
        subT.setTextSize(11);
        subT.setTextColor(Theme.busy(act));
        subT.setSingleLine(true);
        subT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(subT);

        TextView stopBtn = new TextView(act);
        stopBtn.setText("停止");
        stopBtn.setTextSize(13);
        stopBtn.setTextColor(Theme.errTxt(act));
        stopBtn.setGravity(Gravity.CENTER);
        stopBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 6), UiKit.dp(act, 14), UiKit.dp(act, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 16));
        bg.setColor(Theme.errBg(act));
        bg.setStroke(UiKit.dp(act, 1), Theme.errTxt(act));
        stopBtn.setBackground(bg);
        stopBtn.setClickable(true);
        stopBtn.setOnClickListener(v -> stopTask(t));
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stopLp.leftMargin = UiKit.dp(act, 8);
        row.addView(stopBtn, stopLp);
        return row;
    }

    // ---------------- 生命周期 ----------------

    public void onShow() {
        refresh();
    }

    public void onHide() {
    }
}
