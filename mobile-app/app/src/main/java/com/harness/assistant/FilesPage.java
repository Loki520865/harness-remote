package com.harness.assistant;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Base64;
import java.util.Locale;

/**
 * 文件页（M2 / F12-F14）：浏览工作站工作区（限 cwd）、查看/编辑/写回文本（F15 确认）、下载。
 * 目录穿越由桌面伴侣 assertInsideCwd 兜底。
 */
public class FilesPage {

    private final MainActivity act;
    private LinearLayout root;
    private TextView titleText;
    private LinearLayout body;

    private String cwd = "";
    private String relPath = ".";
    private String currentFile = ""; // 正在编辑的文件相对路径

    public FilesPage(MainActivity act) {
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
        back.setOnClickListener(v -> act.closeFiles());
        head.addView(back);

        titleText = new TextView(act);
        titleText.setText("文件");
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

    // ---------------- 导航 ----------------

    public void setWorkspace(String cwd, String title) {
        this.cwd = cwd == null ? "" : cwd;
        this.relPath = ".";
        this.currentFile = "";
        String shortTitle = title;
        if (shortTitle == null || shortTitle.isEmpty()) shortTitle = "工作区";
        if (shortTitle.length() > 20) shortTitle = shortTitle.substring(0, 20) + "…";
        titleText.setText("文件 · " + shortTitle);
        load();
    }

    private String joinPath(String base, String name) {
        if (base == null || base.isEmpty() || ".".equals(base)) return name;
        return base + "/" + name;
    }

    private String parentPath(String p) {
        if (p == null || p.isEmpty() || ".".equals(p)) return ".";
        int i = p.lastIndexOf('/');
        if (i <= 0) return ".";
        return p.substring(0, i);
    }

    private void load() {
        if (cwd.isEmpty()) {
            act.toast("无工作区");
            return;
        }
        JSONObject req = new JSONObject();
        try {
            req.put("op", "file.list");
            req.put("cwd", cwd);
            req.put("path", relPath);
            act.send(req);
        } catch (Exception e) {
            act.toast("请求失败: " + e.getMessage());
        }
    }

    // ---------------- 渲染：目录 ----------------

    public void onListResult(JSONObject msg) {
        body.removeAllViews();
        JSONArray entries = msg.optJSONArray("entries");
        if (entries == null || entries.length() == 0) {
            body.addView(emptyRow("空目录"));
            return;
        }
        if (!".".equals(relPath)) {
            body.addView(fileRow("..", "dir", 0, false, v -> {
                relPath = parentPath(relPath);
                load();
            }));
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject e = entries.optJSONObject(i);
            if (e == null) continue;
            final String name = e.optString("name", "");
            final String type = e.optString("type", "file");
            final long size = e.optLong("size", 0);
            body.addView(fileRow(name, type, size, i < entries.length() - 1, v -> onTap(name, type)));
        }
    }

    private void onTap(String name, String type) {
        if ("dir".equals(type)) {
            relPath = joinPath(relPath, name);
            load();
        } else {
            currentFile = joinPath(relPath, name);
            JSONObject req = new JSONObject();
            try {
                req.put("op", "file.read");
                req.put("cwd", cwd);
                req.put("path", currentFile);
                act.send(req);
            } catch (Exception e) {
                act.toast("读取失败: " + e.getMessage());
            }
        }
    }

    private View fileRow(String name, String type, long size, boolean divider, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 10), UiKit.dp(act, 14), UiKit.dp(act, 10));
        row.setClickable(true);
        row.setOnClickListener(click);

        TextView nameT = new TextView(act);
        nameT.setText(("dir".equals(type) ? "▸ " : "  ") + name);
        nameT.setTextSize(14);
        nameT.setTextColor("dir".equals(type) ? Theme.accent(act) : Theme.txt(act));
        nameT.setSingleLine(true);
        nameT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(nameT, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        if (!"dir".equals(type) && size > 0) {
            TextView sizeT = new TextView(act);
            sizeT.setText(fmtSize(size));
            sizeT.setTextSize(11);
            sizeT.setTextColor(Theme.sub(act));
            row.addView(sizeT);
        }
        if (divider) {
            row.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 8), UiKit.dp(act, 14), UiKit.dp(act, 8));
        }
        return row;
    }

    private TextView emptyRow(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Theme.sub(act));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UiKit.dp(act, 40), 0, 0);
        return t;
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", b / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", b / 1024.0 / 1024.0);
    }

    // ---------------- 渲染：文本编辑 ----------------

    public void onReadResult(JSONObject msg) {
        String path = msg.optString("path", currentFile);
        String content = msg.optString("content", "");
        boolean truncated = msg.optBoolean("truncated", false);
        currentFile = path;
        body.removeAllViews();

        TextView pathT = new TextView(act);
        pathT.setText(path);
        pathT.setTextSize(11);
        pathT.setTextColor(Theme.sub(act));
        pathT.setSingleLine(true);
        pathT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        pathT.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 8), UiKit.dp(act, 14), 0);
        body.addView(pathT);

        final EditText editor = new EditText(act);
        editor.setText(content);
        editor.setTextColor(Theme.txt(act));
        editor.setTextSize(13);
        editor.setBackgroundResource(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 10));
        bg.setColor(Theme.inputBg(act));
        editor.setBackground(bg);
        editor.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 10));
        editor.setMinHeight(UiKit.dp(act, 220));
        ScrollView sv = new ScrollView(act);
        sv.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), 0);
        sv.addView(editor);
        body.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (truncated) {
            TextView warn = new TextView(act);
            warn.setText("文件超过 256KB，仅展示开头部分；直接保存将覆盖全文件。");
            warn.setTextSize(11);
            warn.setTextColor(Theme.errTxt(act));
            warn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 6), UiKit.dp(act, 14), 0);
            body.addView(warn);
        }

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER);
        btns.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 10), UiKit.dp(act, 12), UiKit.dp(act, 12));

        TextView backBtn = actionBtn("返回列表", Theme.accent(act), Theme.inputBg(act), v -> load());
        btns.addView(backBtn, btnLp(backBtn));

        TextView dlBtn = actionBtn("下载", Theme.accent(act), Theme.inputBg(act), v -> download());
        btns.addView(dlBtn, btnLp(dlBtn));

        TextView saveBtn = actionBtn("保存修改", Theme.onAccent(act), Theme.accent(act), v -> save(editor.getText().toString()));
        btns.addView(saveBtn, btnLp(saveBtn));

        body.addView(btns);
    }

    private TextView actionBtn(String label, int fg, int bgColor, View.OnClickListener click) {
        TextView b = new TextView(act);
        b.setText(label);
        b.setTextSize(13);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        b.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), UiKit.dp(act, 8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 16));
        bg.setColor(bgColor);
        bg.setStroke(UiKit.dp(act, 1), fg);
        b.setBackground(bg);
        b.setClickable(true);
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout.LayoutParams btnLp(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = UiKit.dp(act, 4);
        return lp;
    }

    private void save(String content) {
        UiKit.confirm(act, "确认写入", "将覆盖工作区文件：\n" + currentFile + "\n\n确定保存？",
                "覆盖保存", true, () -> {
                    JSONObject req = new JSONObject();
                    try {
                        req.put("op", "file.write");
                        req.put("cwd", cwd);
                        req.put("path", currentFile);
                        req.put("content", content);
                        req.put("confirmed", true);
                        act.send(req);
                    } catch (Exception e) {
                        act.toast("写入失败: " + e.getMessage());
                    }
                });
    }

    private void download() {
        JSONObject req = new JSONObject();
        try {
            req.put("op", "file.download");
            req.put("cwd", cwd);
            req.put("path", currentFile);
            act.send(req);
        } catch (Exception e) {
            act.toast("下载失败: " + e.getMessage());
        }
    }

    // ---------------- 回包 ----------------

    public void onWriteResult(JSONObject msg) {
        act.toast("已保存: " + msg.optString("path", currentFile));
        load();
    }

    public void onDownloadResult(JSONObject msg) {
        final String name = msg.optString("name", "file");
        final String base64 = msg.optString("base64", "");
        final long size = msg.optLong("size", 0);
        if (base64.isEmpty()) {
            act.toast("下载内容为空");
            return;
        }
        // 修复：解码+写盘放后台线程（防主线程卡顿/ANR）；文件名清洗（防路径穿越与非法字符）
        final String safeName = new File(name).getName().replaceAll("[\\\\/:*?\"<>|]", "_");
        new Thread(() -> {
            try {
                File dir = new File(act.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, safeName);
                byte[] data = Base64.getDecoder().decode(base64);
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(data);
                }
                final String path = out.getAbsolutePath();
                act.runOnUiThread(() -> act.toast("已下载 " + fmtSize(size) + " → " + path));
            } catch (Exception e) {
                final String err = e.getMessage();
                act.runOnUiThread(() -> act.toast("保存失败: " + (err == null ? "未知错误" : err)));
            }
        }).start();
    }

    public void onError(JSONObject msg) {
        act.toast("文件操作失败: " + msg.optString("error", "未知错误"));
    }

    // ---------------- 生命周期 ----------------

    public void onShow() {
    }

    public void onHide() {
    }
}
