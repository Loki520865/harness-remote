package com.harness.assistant;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
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
 * 会话页（M2）：Web/手机共用同一会话库。
 * 点开会话 / 新建 / 重命名（同步 projcache）/ 删除（确认）/ 未读角标（F19）/ 打开工作区文件。
 */
public class SessionsPage {

    private final MainActivity act;
    private LinearLayout root;
    private LinearLayout listBox;
    private TextView emptyHint;
    private final List<JSONObject> items = new ArrayList<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    public SessionsPage(MainActivity act) {
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
        title.setText("会话");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Theme.txt(act));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView refreshBtn = new TextView(act);
        refreshBtn.setText("刷新");
        refreshBtn.setTextSize(13);
        refreshBtn.setTextColor(Theme.accent(act));
        refreshBtn.setGravity(Gravity.CENTER);
        refreshBtn.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 6), UiKit.dp(act, 12), UiKit.dp(act, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 16));
        bg.setColor(Theme.inputBg(act));
        bg.setStroke(UiKit.dp(act, 1), Theme.border(act));
        refreshBtn.setBackground(bg);
        refreshBtn.setClickable(true);
        refreshBtn.setOnClickListener(v -> refresh());
        head.addView(refreshBtn);

        TextView newBtn = new TextView(act);
        newBtn.setText("新建会话");
        newBtn.setTextSize(13);
        newBtn.setTextColor(Theme.onAccent(act));
        newBtn.setGravity(Gravity.CENTER);
        newBtn.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 6), UiKit.dp(act, 12), UiKit.dp(act, 6));
        GradientDrawable nbg = new GradientDrawable();
        nbg.setCornerRadius(UiKit.dp(act, 16));
        nbg.setColor(Theme.accent(act));
        newBtn.setBackground(nbg);
        newBtn.setClickable(true);
        newBtn.setOnClickListener(v -> newSession());
        LinearLayout.LayoutParams newLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        newLp.leftMargin = UiKit.dp(act, 6);
        head.addView(newBtn, newLp);

        root.addView(head);

        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(UiKit.dp(act, 12), 0, UiKit.dp(act, 12), UiKit.dp(act, 12));
        root.addView(listBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        emptyHint = new TextView(act);
        emptyHint.setText("暂无会话");
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
            req.put("op", "session.list");
            act.send(req);
        } catch (Exception e) {
            act.toast("请求失败: " + e.getMessage());
        }
    }

    private void newSession() {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "session.create");
            act.send(req);
        } catch (Exception e) {
            act.toast("新建失败: " + e.getMessage());
        }
    }

    private void openSession(JSONObject s) {
        String id = s.optString("id", "");
        if (id.isEmpty()) return;
        act.chat().setSessionId(id);
        act.markRead(id);
        act.switchTo(act.chat().view(), false);
    }

    private void renameDialog(JSONObject s) {
        String id = s.optString("id", "");
        final EditText et = new EditText(act);
        et.setText(s.optString("title", ""));
        et.setHint("新名称");
        et.setSingleLine(true);
        UiKit.sheet(act, "重命名会话", et,
                new UiKit.Btn("取消", null),
                new UiKit.Btn("保存", () -> {
                    String title = et.getText().toString().trim();
                    if (title.isEmpty()) {
                        act.toast("名称不能为空");
                        return;
                    }
                    JSONObject req = new JSONObject();
                    try {
                        req.put("op", "session.rename");
                        req.put("session_id", id);
                        req.put("title", title);
                        act.send(req);
                    } catch (Exception e) {
                        act.toast("重命名失败: " + e.getMessage());
                    }
                }));
    }

    private void deleteSession(JSONObject s) {
        final String id = s.optString("id", "");
        UiKit.confirm(act, "删除会话", "删除后该会话从列表中移除（历史文件保留在磁盘），确定？",
                "删除", true, () -> {
                    JSONObject req = new JSONObject();
                    try {
                        req.put("op", "session.delete");
                        req.put("session_id", id);
                        act.send(req);
                    } catch (Exception e) {
                        act.toast("删除失败: " + e.getMessage());
                    }
                });
    }

    private void openFiles(JSONObject s) {
        String cwd = s.optString("cwd", "");
        String title = s.optString("title", s.optString("id", ""));
        if (cwd.isEmpty()) {
            act.toast("该会话无工作区");
            return;
        }
        act.openFiles(cwd, title);
    }

    // ---- v0.7.0：终端 / 轨迹 / 分支 ----

    private void showSessionMenu(JSONObject s, String displayTitle) {
        UiKit.menu(act, displayTitle,
                new UiKit.Item("打开文件", () -> openFiles(s)),
                new UiKit.Item("终端", () -> openTerminal(s)),
                new UiKit.Item("轨迹", () -> openTrace(s)),
                new UiKit.Item("分支", () -> forkSession(s)),
                new UiKit.Item("重命名", () -> renameDialog(s)),
                new UiKit.Item("删除", UiKit.DANGER, () -> deleteSession(s)));
    }

    private void openTerminal(JSONObject s) {
        String cwd = s.optString("cwd", "");
        String title = s.optString("title", s.optString("id", ""));
        if (cwd.isEmpty()) {
            act.toast("该会话无工作区");
            return;
        }
        act.openTerminal(cwd, title);
    }

    private void openTrace(JSONObject s) {
        String id = s.optString("id", "");
        String title = s.optString("title", id);
        if (id.isEmpty()) {
            act.toast("无会话");
            return;
        }
        act.openTrace(id, title);
    }

    private void forkSession(JSONObject s) {
        String id = s.optString("id", "");
        if (id.isEmpty()) {
            act.toast("无会话");
            return;
        }
        UiKit.confirm(act, "分支会话", "复制当前会话全部历史到新会话（原会话不受影响），确定？",
                "分支", false, () -> act.sendSessionFork(id));
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

    public void onError(String error) {
        act.toast("获取会话失败: " + error);
        emptyHint.setVisibility(View.VISIBLE);
        emptyHint.setText("获取失败: " + error);
    }

    private void rebuild() {
        listBox.removeAllViews();
        if (items.isEmpty()) {
            emptyHint.setVisibility(View.VISIBLE);
            emptyHint.setText("暂无会话");
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

    private View buildRow(JSONObject s) {
        String id = s.optString("id", "");
        String title = s.optString("title", "");
        if (title.isEmpty()) title = id.length() > 12 ? id.substring(0, 12) + "…" : id;
        final String displayTitle = title;
        String cwd = s.optString("cwd", "");
        long updatedAt = s.optLong("updatedAt", 0);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 10), UiKit.dp(act, 10), UiKit.dp(act, 10));
        row.setClickable(true);
        row.setOnClickListener(v -> openSession(s));
        row.setOnLongClickListener(v -> {
            showSessionMenu(s, displayTitle);
            return true;
        });

        // 未读角标（F19）
        View badge = new View(act);
        badge.setVisibility(act.isUnread(id) ? View.VISIBLE : View.INVISIBLE);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(Theme.badge(act));
        badge.setBackground(bd);
        row.addView(badge, new LinearLayout.LayoutParams(UiKit.dp(act, 8), UiKit.dp(act, 8)));

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(UiKit.dp(act, 10), 0, 0, 0);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(mid);

        TextView titleT = new TextView(act);
        titleT.setText(title);
        titleT.setTextSize(14);
        titleT.setTextColor(Theme.txt(act));
        titleT.setSingleLine(true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(titleT);

        String sub = (cwd.isEmpty() ? "" : cwd + " · ") + id;
        if (updatedAt > 0) sub = timeFmt.format(new Date(updatedAt)) + "  " + sub;
        TextView subT = new TextView(act);
        subT.setText(sub);
        subT.setTextSize(11);
        subT.setTextColor(Theme.sub(act));
        subT.setSingleLine(true);
        subT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(subT);

        TextView more = new TextView(act);
        more.setText("•••");
        more.setTextSize(14);
        more.setTextColor(Theme.sub(act));
        more.setPadding(UiKit.dp(act, 8), 0, 0, 0);
        more.setClickable(true);
        more.setOnClickListener(v -> showSessionMenu(s, displayTitle));
        row.addView(more);
        return row;
    }

    // ---------------- 生命周期 ----------------

    public void onDeviceChanged() {
        refresh();
    }

    public void onShow() {
        refresh();
    }

    public void onHide() {
    }
}
