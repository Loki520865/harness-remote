package com.harness.assistant;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 远程终端（v0.7.0）：手机执行电脑命令（terminal.exec，一次性执行 + 完整输出）。
 * 高危命令（对齐 isRisky）发送前二次确认；桌面伴侣另有 HIGH_RISK_PATTERN 兜底。
 */
public class TerminalPage {

    private final MainActivity act;
    private LinearLayout root;
    private TextView titleText;
    private TextView cwdText;
    private EditText cmdInput;
    private TextView execBtn;
    private ScrollView scroll;
    private TextView output;

    private String cwd = "";
    private boolean busy;

    public TerminalPage(MainActivity act) {
        this.act = act;
        build();
    }

    public View view() { return root; }

    private void build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(act));

        // 标题栏
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
        titleText.setText("终端");
        titleText.setTextSize(14);
        titleText.setTextColor(Theme.txt(act));
        titleText.setSingleLine(true);
        titleText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(titleText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        root.addView(head);

        // cwd 显示
        cwdText = new TextView(act);
        cwdText.setTextSize(11);
        cwdText.setTextColor(Theme.sub(act));
        cwdText.setSingleLine(true);
        cwdText.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        cwdText.setPadding(UiKit.dp(act, 12), 0, UiKit.dp(act, 12), UiKit.dp(act, 6));
        root.addView(cwdText);

        // 输入行：命令框 + 执行按钮
        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 4), UiKit.dp(act, 12), UiKit.dp(act, 8));

        cmdInput = new EditText(act);
        cmdInput.setHint("输入命令，如 dir / ls");
        cmdInput.setTextSize(14);
        cmdInput.setTextColor(Theme.txt(act));
        cmdInput.setHintTextColor(Theme.sub(act));
        cmdInput.setSingleLine(true);
        cmdInput.setBackground(bg(Theme.inputBg(act), 10));
        cmdInput.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        inputRow.addView(cmdInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        execBtn = new TextView(act);
        execBtn.setText("执行");
        execBtn.setTextSize(14);
        execBtn.setTextColor(Theme.onAccent(act));
        execBtn.setGravity(Gravity.CENTER);
        execBtn.setBackground(bg(Theme.accent(act), 10));
        execBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 8), UiKit.dp(act, 14), UiKit.dp(act, 8));
        execBtn.setClickable(true);
        execBtn.setOnClickListener(v -> run());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = UiKit.dp(act, 8);
        inputRow.addView(execBtn, btnLp);

        root.addView(inputRow);

        // 输出区
        output = new TextView(act);
        output.setTextSize(12);
        output.setTextColor(Theme.txt(act));
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 12));

        scroll = new ScrollView(act);
        scroll.setBackgroundColor(Theme.inputBg(act));
        scroll.addView(output, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    public void setCwd(String cwd, String title) {
        this.cwd = cwd == null ? "" : cwd;
        String shortTitle = title;
        if (shortTitle == null || shortTitle.isEmpty()) shortTitle = "终端";
        if (shortTitle.length() > 16) shortTitle = shortTitle.substring(0, 16) + "…";
        titleText.setText("终端 · " + shortTitle);
        cwdText.setText("目录: " + (this.cwd.isEmpty() ? "（未指定）" : this.cwd));
        output.setText("");
        cmdInput.setText("");
    }

    public void onShow() { }

    public void onHide() { }

    // ---------------- 执行 ----------------

    private void run() {
        if (busy) {
            act.toast("正在执行中…");
            return;
        }
        if (cwd.isEmpty()) {
            act.toast("无工作区目录");
            return;
        }
        String command = cmdInput.getText() == null ? "" : cmdInput.getText().toString().trim();
        if (command.isEmpty()) {
            act.toast("请输入命令");
            return;
        }
        if (MainActivity.isRisky(command)) {
            UiKit.confirm(act, "高危命令确认",
                    "命令包含高危操作（删除/格式化/关机/清库等），将直接在电脑上执行:\n\n" + command,
                    "仍然执行", true, () -> doExec(command, true));
        } else {
            doExec(command, false);
        }
    }

    private void doExec(String command, boolean confirmed) {
        busy = true;
        execBtn.setText("执行中…");
        append("$ " + command + "\n");
        act.sendTerminalExec(cwd, command, confirmed);
    }

    public void onExecResult(JSONObject msg) {
        busy = false;
        execBtn.setText("执行");
        String stdout = msg.optString("stdout", "");
        String stderr = msg.optString("stderr", "");
        int exitCode = msg.optInt("exitCode", -1);
        boolean truncated = msg.optBoolean("truncated", false);
        boolean timedOut = msg.optBoolean("timedOut", false);
        long durationMs = msg.optLong("durationMs", 0);

        if (!stdout.isEmpty()) append(stdout + (stdout.endsWith("\n") ? "" : "\n"));
        if (!stderr.isEmpty()) append(stderr + (stderr.endsWith("\n") ? "" : "\n"));
        String tail = "─ 退出码 " + exitCode + " · 耗时 " + formatDuration(durationMs);
        if (timedOut) tail += " · 超时(120s)已终止";
        if (truncated) tail += " · 输出过长已截断";
        append(tail + "\n\n");
    }

    public void onExecError(JSONObject msg) {
        busy = false;
        execBtn.setText("执行");
        String error = msg.optString("error", "未知错误");
        append("✗ " + error + "\n\n");
    }

    private void append(String text) {
        if (text == null || text.isEmpty()) return;
        output.append(text);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private android.graphics.drawable.GradientDrawable bg(int color, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(UiKit.dp(act, radius));
        return d;
    }

    private String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s";
        return (sec / 60) + "m" + (sec % 60) + "s";
    }
}
