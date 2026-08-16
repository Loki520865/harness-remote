package com.harness.assistant;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 对话页（M2）：消息流 + 输入区。
 * F15 高危指令发送前确认 / Q4 幂等发送 / F18 草稿自动保存 / 状态与事件可视化。
 */
public class ChatPage {

    private final MainActivity act;
    private LinearLayout root;
    private ScrollView scroll;
    private LinearLayout list;
    private EditText input;
    private TextView sessionLabel;

    private final List<ChatMsg> msgs = new ArrayList<>();
    private String sessionId = "";
    private TextView sendBtn;      // v0.6.7: 流式中变"停止"（对应 web 端停止按钮）
    private boolean busy = false;  // v0.6.7: 流式生成中

    // v0.6.2: 流式累积（session.event 增量 → 同一条消息内追加，向 web 靠拢）
    private ChatMsg streamingReasoning;   // 进行中的思考卡
    private TextView streamingReasoningView;
    private ChatMsg streamingAssistant;   // 进行中的回复气泡
    private TextView streamingAssistantView;

    // v0.6.5: callId → 工具名 反查表（tool/result 里没有 name，只能靠 source.callId 关联）
    private final Map<String, String> callNames = new HashMap<>();

    // v0.6.4: ask_user_question 交互卡片（AI 提问 → 手机选项卡 → 答案回传）
    private static class QState {
        JSONObject q;
        String id;
        boolean multi;
        Boolean[] selected;
        final List<TextView> optViews = new ArrayList<>();
        EditText custom;
    }
    private final List<QState> questionStates = new ArrayList<>();
    private LinearLayout questionCard;
    private TextView questionCardTitle;
    private boolean questionsSubmitted;

    public ChatPage(MainActivity act) {
        this.act = act;
        build();
    }

    public View view() { return root; }
    public String currentSessionId() { return sessionId; }

    private void build() {
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(act));

        // 会话标签行
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(UiKit.dp(act, 16), UiKit.dp(act, 8), UiKit.dp(act, 16), UiKit.dp(act, 8));
        sessionLabel = new TextView(act);
        sessionLabel.setText("新会话");
        sessionLabel.setTextSize(12);
        sessionLabel.setTextColor(Theme.sub(act));
        sessionLabel.setSingleLine(true);
        sessionLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        head.addView(sessionLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView newBtn = new TextView(act);
        newBtn.setText("新建");
        newBtn.setTextSize(13);
        newBtn.setTextColor(Theme.accent(act));
        newBtn.setGravity(Gravity.CENTER);
        newBtn.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 6), UiKit.dp(act, 14), UiKit.dp(act, 6));
        GradientDrawable nbg = new GradientDrawable();
        nbg.setCornerRadius(UiKit.dp(act, 16));
        nbg.setColor(Theme.inputBg(act));
        nbg.setStroke(UiKit.dp(act, 1), Theme.border(act));
        newBtn.setBackground(nbg);
        newBtn.setClickable(true);
        newBtn.setOnClickListener(v -> newSession());
        head.addView(newBtn);
        root.addView(head);

        // 消息流
        scroll = new ScrollView(act);
        scroll.setFillViewport(true);
        list = new LinearLayout(act);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 6), UiKit.dp(act, 12), UiKit.dp(act, 6));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 输入行
        LinearLayout inputRow = new LinearLayout(act);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        inputRow.setBackgroundColor(Theme.card(act));

        input = new EditText(act);
        input.setHint("给工作站下指令…");
        input.setHintTextColor(Theme.sub(act));
        input.setTextColor(Theme.txt(act));
        input.setTextSize(14);
        input.setBackgroundResource(0);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(UiKit.dp(act, 18));
        ibg.setColor(Theme.inputBg(act));
        input.setBackground(ibg);
        input.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 10), UiKit.dp(act, 14), UiKit.dp(act, 10));
        input.setMaxLines(4);
        inputRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // v0.7.0：图片按钮（相册选图 → 电脑本地 OCR → 文字插入输入框）
        TextView imgBtn = new TextView(act);
        imgBtn.setText("🖼");
        imgBtn.setTextSize(18);
        imgBtn.setTextColor(Theme.sub(act));
        imgBtn.setGravity(Gravity.CENTER);
        imgBtn.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        imgBtn.setClickable(true);
        imgBtn.setOnClickListener(v -> act.openImagePicker());
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        imgLp.rightMargin = UiKit.dp(act, 4);
        inputRow.addView(imgBtn, imgLp);

        sendBtn = new TextView(act);
        sendBtn.setText("发送");
        sendBtn.setTextSize(14);
        sendBtn.setTypeface(null, Typeface.BOLD);
        sendBtn.setTextColor(Theme.onAccent(act));
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(UiKit.dp(act, 18), UiKit.dp(act, 10), UiKit.dp(act, 18), UiKit.dp(act, 10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(UiKit.dp(act, 20));
        sbg.setColor(Theme.accent(act));
        sendBtn.setBackground(sbg);
        sendBtn.setClickable(true);
        sendBtn.setOnClickListener(v -> onSendClick());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = UiKit.dp(act, 8);
        inputRow.addView(sendBtn, sendLp);

        // F18：草稿实时保存
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                prefs().edit().putString(MainActivity.KEY_DRAFT, s.toString()).apply();
            }
        });
        root.addView(inputRow);
    }

    private SharedPreferences prefs() {
        return act.getSharedPreferences(MainActivity.PREFS, Activity.MODE_PRIVATE);
    }

    // ---------------- 输入 / 发送 ----------------

    private void onSendClick() {
        String content = input.getText().toString().trim();
        if (content.isEmpty()) {
            act.toast("指令不能为空");
            return;
        }
        if (MainActivity.isRisky(content)) {
            UiKit.confirm(act, "高危指令确认",
                    "这条指令可能修改或删除工作站数据：\n\n" + (content.length() > 80 ? content.substring(0, 80) + "…" : content) + "\n\n确认发送？",
                    "确认发送", true, () -> doSend(content));
        } else {
            doSend(content);
        }
    }

    private void doSend(String content) {
        input.setText("");
        prefs().edit().putString(MainActivity.KEY_DRAFT, "").apply();
        addMsg(new ChatMsg(ChatMsg.USER, content));
        act.sendPrompt(content, sessionId);
        busy = true;
        updateSendButton();
    }

    /** v0.6.7: 发送/停止按钮切换（流式中显示"停止"，点击向桌面端发 task.stop） */
    private void updateSendButton() {
        if (sendBtn == null) return;
        if (busy) {
            sendBtn.setText("停止");
            sendBtn.setOnClickListener(v -> act.stopCurrent());
        } else {
            sendBtn.setText("发送");
            sendBtn.setOnClickListener(v -> onSendClick());
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

    // ---------------- 消息渲染 ----------------

    private void addMsg(ChatMsg m) {
        msgs.add(m);
        renderMsg(m);
        scrollToBottom();
    }

    private TextView renderMsg(ChatMsg m) {
        if (m.type == ChatMsg.TOOL) {
            TextView t = new TextView(act);
            t.setText(m.expanded && m.fullText != null ? m.fullText : m.text);
            t.setTextSize(12);
            t.setTextColor(Theme.toolTxt(act));
            t.setLineSpacing(UiKit.dp(act, 2), 1f);
            t.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
            t.setBackgroundColor(Theme.toolBg(act));
            t.setClickable(true);
            t.setOnClickListener(v -> toggleCard(m, t));
            t.setOnLongClickListener(v -> { act.copyToClipboard(m.fullText != null ? m.fullText : m.text); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiKit.dp(act, 6);
            list.addView(t, lp);
            return t;
        }
        if (m.type == ChatMsg.ERROR) {
            TextView t = new TextView(act);
            t.setText("⚠ " + m.text);
            t.setTextSize(13);
            t.setTextColor(Theme.errTxt(act));
            t.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
            t.setBackgroundColor(Theme.errBg(act));
            t.setOnLongClickListener(v -> { act.copyToClipboard(m.text); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiKit.dp(act, 6);
            list.addView(t, lp);
            return t;
        }
        // v0.6.6: 思考步骤卡（灰色斜体气泡；折叠显示标题，点击展开全文，向 web 端"深度思考"靠拢）
        if (m.type == ChatMsg.REASONING) {
            TextView t = new TextView(act);
            t.setText(reasoningTitle(m));
            t.setTextSize(12);
            t.setTextColor(Theme.sub(act));
            t.setTypeface(null, Typeface.ITALIC);
            t.setLineSpacing(UiKit.dp(act, 2), 1f);
            t.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.toolBg(act));
            bg.setCornerRadius(UiKit.dp(act, 10));
            t.setBackground(bg);
            t.setClickable(true);
            t.setOnClickListener(v -> toggleCard(m, t));
            t.setOnLongClickListener(v -> { act.copyToClipboard(m.text); return true; });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = UiKit.dp(act, 6);
            list.addView(t, lp);
            return t;
        }
        boolean user = m.type == ChatMsg.USER;
        TextView t = new TextView(act);
        t.setText(Markdown.render(act, m.text));
        t.setTextSize(14);
        t.setTextColor(Theme.txt(act));
        t.setLineSpacing(UiKit.dp(act, 2), 1f);
        t.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 8), UiKit.dp(act, 12), UiKit.dp(act, 8));
        t.setBackgroundColor(user ? Theme.userBubble(act) : Theme.card(act));
        if (!user) {
            // v0.6.7: AI 回复支持 markdown 链接点击跳转
            t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            t.setHighlightColor(0x00000000);
        }
        // v0.6.7: 长按 → 复制（用户消息弹菜单：复制 / 重新生成）
        t.setOnLongClickListener(v -> { onLongPress(m); return true; });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(user ? Theme.userBubble(act) : Theme.card(act));
        bg.setCornerRadii(new float[]{
                UiKit.dp(act, user ? 14 : 4), UiKit.dp(act, user ? 14 : 4),
                UiKit.dp(act, 4), UiKit.dp(act, 14),
                UiKit.dp(act, 4), UiKit.dp(act, 14),
                UiKit.dp(act, 14), UiKit.dp(act, 14)});
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, user ? 0f : 1f);
        lp.topMargin = UiKit.dp(act, 6);
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (user) lp.gravity = Gravity.END;
        list.addView(t, lp);
        return t;
    }

    /** v0.6.7: 长按消息：用户消息弹菜单（复制/重新生成），其他直接复制 */
    private void onLongPress(ChatMsg m) {
        if (m.type == ChatMsg.USER) {
            UiKit.menu(act, "消息操作",
                    new UiKit.Item("复制", false, () -> act.copyToClipboard(m.text)),
                    new UiKit.Item("重新生成", false, () -> {
                        if (sessionId.isEmpty()) { act.toast("当前无会话"); return; }
                        act.sendPrompt(m.text, sessionId);
                        busy = true;
                        updateSendButton();
                    }));
        } else {
            act.copyToClipboard(m.text);
        }
    }

    private void addStatusRow(String text) {
        TextView t = new TextView(act);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(Theme.sub(act));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, UiKit.dp(act, 4), 0, UiKit.dp(act, 4));
        list.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    // ---------------- 协议回调 ----------------

    public void appendStatus(String text) {
        addStatusRow(text);
    }

    public void restoreDraft(String draft) {
        if (draft != null && !draft.isEmpty()) input.setText(draft);
    }

    /**
     * v0.6.2 重写：解析完整 session.event 结构（desktop 原样转发 harness 通知）。
     * 思考增量在 assistant/chunk.data.chunk.text（reasoning-delta），
     * 正式回复在 text-delta / assistant/message.data.message.content。
     * 旧实现只读顶层 type/message 两字段 → 只显示 "→ assistant/chunk" 空卡，看不到思考。
     */
    public void onEvent(JSONObject ev) {
        if (ev == null) return;
        String type = ev.optString("type", "");
        if (type.isEmpty()) return;
        JSONObject data = ev.optJSONObject("data");

        if ("turn/start".equals(type)) {
            addStatusRow("思考中…");
            busy = true;
            updateSendButton();
            return;
        }
        if ("assistant/chunk".equals(type)) {
            JSONObject chunk = data == null ? null : data.optJSONObject("chunk");
            if (chunk == null) return;
            String ctype = chunk.optString("type", "");
            String text = chunk.optString("text", "");
            if (text.isEmpty()) return;
            if ("reasoning-delta".equals(ctype)) appendReasoning(text);
            else if ("text-delta".equals(ctype)) appendAssistant(text);
            return;
        }
        if ("assistant/message".equals(type)) {
            finishStreaming();
            busy = false;
            updateSendButton();
            String full = assistantText(data);
            if (!full.isEmpty()) addMsg(new ChatMsg(ChatMsg.ASSISTANT, full));
            return;
        }
        if ("tool/call".equals(type)) {
            String[] r = toolCallLabel(data);
            if (r != null) addToolMsg(r[0], r[1]);
            return;
        }
        if ("tool/result".equals(type)) {
            String[] r = toolResultLabel(data);
            if (r != null) addToolMsg(r[0], r[1]);
            return;
        }
        // 其余事件（user/message、request/header、agent/inbox/…）不打扰对话流
    }

    /** 思考增量 → 累积到同一张思考卡（流式中默认展开，方便看到思考进展） */
    private void appendReasoning(String delta) {
        if (streamingReasoning == null) {
            streamingReasoning = new ChatMsg(ChatMsg.REASONING, delta);
            streamingReasoning.streaming = true;
            streamingReasoning.expanded = true;   // 流式中展开
            msgs.add(streamingReasoning);
            streamingReasoningView = renderMsg(streamingReasoning);
        } else {
            streamingReasoning.append(delta);
            if (streamingReasoningView != null) {
                streamingReasoningView.setText(reasoningTitle(streamingReasoning));
            }
        }
        scrollToBottom();
    }

    /** 回复增量 → 累积到同一气泡 */
    private void appendAssistant(String delta) {
        if (streamingAssistant == null) {
            streamingAssistant = new ChatMsg(ChatMsg.ASSISTANT, delta);
            streamingAssistant.streaming = true;
            msgs.add(streamingAssistant);
            streamingAssistantView = renderMsg(streamingAssistant);
        } else {
            streamingAssistant.append(delta);
            if (streamingAssistantView != null) {
                streamingAssistantView.setText(Markdown.render(act, streamingAssistant.text));
            }
        }
        scrollToBottom();
    }

    /** assistant/message 到达：结束本轮流式（思考卡自动收起为标题条，向 web 端"深度思考"靠拢） */
    private void finishStreaming() {
        if (streamingReasoning != null) {
            streamingReasoning.streaming = false;
            streamingReasoning.expanded = false;
            if (streamingReasoningView != null) {
                streamingReasoningView.setText(reasoningTitle(streamingReasoning));
            }
        }
        if (streamingAssistant != null) streamingAssistant.streaming = false;
        streamingReasoning = null;
        streamingReasoningView = null;
        streamingAssistant = null;
        streamingAssistantView = null;
    }

    /** 提取 assistant/message 的完整文本（data.message.content 的 text 块拼接） */
    private String assistantText(JSONObject data) {
        if (data == null) return "";
        JSONObject message = data.optJSONObject("message");
        if (message == null) return "";
        JSONArray content = message.optJSONArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            String bt = block.optString("type", "");
            if ("text".equals(bt)) sb.append(block.optString("text", ""));
        }
        return sb.toString().trim();
    }

    /** 工具调用事件 → [摘要, 全文]：名称 + 参数摘要（v0.6.5 能看到实际参数/命令，点击展开完整参数） */
    private String[] toolCallLabel(JSONObject data) {
        if (data == null) return null;
        String name = data.optString("name", "");
        String callId = data.optString("callId", "");
        if (!callId.isEmpty()) callNames.put(callId, name);
        if (name.isEmpty()) return new String[]{"🔧 调用工具", ""};
        String raw = data.optString("arguments", "");
        String summary = summarizeArgs(raw);
        String head = "🔧 调用工具: " + name;
        String summ = summary.isEmpty() ? head : head + "\n" + summary;
        String full = raw == null || raw.trim().isEmpty() ? "" : head + "\n" + limit(raw, 20000);
        return new String[]{summ, full.isEmpty() ? summ : full};
    }

    /** 工具结果事件 → [摘要, 全文]：名称 + 输出摘要（点击展开完整输出） */
    private String[] toolResultLabel(JSONObject data) {
        if (data == null) return null;
        JSONObject message = data.optJSONObject("message");
        if (message == null) return null;
        String callId = "";
        JSONObject source = message.optJSONObject("source");
        if (source != null) callId = source.optString("callId", "");
        String name = callNames.get(callId);
        if (name == null || name.isEmpty()) name = "工具";
        String err = message.optString("error", "");
        if (!err.isEmpty()) {
            String head = "❌ 工具出错: " + name + " — ";
            return new String[]{head + limit(err, 300), head + limit(err, 20000)};
        }
        String text = extractToolText(message);
        String head = "✅ 工具完成: " + name;
        if (text.isEmpty()) return new String[]{head, head};
        String summ = head + "\n" + (text.length() > 600 ? text.substring(0, 600) + "\n…（输出过长，点击展开查看全文）" : text);
        return new String[]{summ, head + "\n" + limit(text, 20000)};
    }

    /** 折叠卡点击：展开/收起并更新视图 */
    private void toggleCard(ChatMsg m, TextView v) {
        m.expanded = !m.expanded;
        if (m.type == ChatMsg.REASONING) {
            v.setText(reasoningTitle(m));
        } else if (m.fullText != null) {
            v.setText(m.expanded ? m.fullText : m.text);
        }
        scrollToBottom();
    }

    /** 思考卡标题：流式中显示进展；完成默认折叠为标题条（向 web 端"深度思考"靠拢） */
    private String reasoningTitle(ChatMsg m) {
        if (m.streaming) return "🧠 思考中…\n" + m.text;
        return m.expanded ? "🧠 深度思考\n" + m.text : "🧠 深度思考  ·  点击展开";
    }

    /** 工具卡入队：摘要默认显示，全文点击展开 */
    private void addToolMsg(String summary, String full) {
        ChatMsg m = new ChatMsg(ChatMsg.TOOL, summary);
        m.fullText = full == null ? summary : full;
        m.expanded = m.fullText.equals(summary);
        addMsg(m);
    }

    private String limit(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) + "…" : s);
    }

    /** 参数 JSON 字符串 → 精简摘要（取前 3 个 key=value，超长截断） */
    private String summarizeArgs(String json) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder();
            Iterator<String> keys = o.keys();
            int n = 0;
            while (keys.hasNext() && n < 3) {
                String k = keys.next();
                String v = o.optString(k, "");
                if (v.length() > 140) v = v.substring(0, 140) + "…";
                if (sb.length() > 0) sb.append("  ");
                sb.append(k).append("=").append(v);
                n++;
            }
            if (sb.length() == 0) sb.append(json);
            if (sb.length() > 500) return sb.substring(0, 500) + "…";
            return sb.toString();
        } catch (Exception e) {
            String s = json.trim();
            return s.length() > 300 ? s.substring(0, 300) + "…" : s;
        }
    }

    /** 提取 tool/result 的 message.content[].content[].text 全部文本 */
    private String extractToolText(JSONObject message) {
        JSONArray content = message.optJSONArray("content");
        if (content == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block == null) continue;
            JSONArray inner = block.optJSONArray("content");
            if (inner == null) continue;
            for (int j = 0; j < inner.length(); j++) {
                JSONObject seg = inner.optJSONObject(j);
                if (seg != null && "text".equals(seg.optString("type", ""))) {
                    sb.append(seg.optString("text", ""));
                }
            }
        }
        String s = sb.toString();
        // 去掉首尾多余空白，但保留内部换行（命令输出可读）
        while (s.startsWith("\n")) s = s.substring(1);
        return s.trim();
    }

    public void onStatus(String status) {
        addStatusRow("状态: " + status);
    }

    // ---------------- v0.6.4: ask_user_question 交互卡片 ----------------

    /** AI 提问 → 渲染可交互选项卡（选项可点选 + 可填自定义答案） */
    public void showUserQuestions(String requestId, String sessionId, JSONArray questions) {
        removeQuestionCard();
        questionStates.clear();
        questionsSubmitted = false;

        questionCard = new LinearLayout(act);
        questionCard.setOrientation(LinearLayout.VERTICAL);
        questionCard.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 12), UiKit.dp(act, 14), UiKit.dp(act, 12));
        questionCard.setTag(requestId); // 提交答案时通过 getTag() 取回 requestId
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 12));
        bg.setColor(Theme.card(act));
        bg.setStroke(UiKit.dp(act, 1), Theme.border(act));
        questionCard.setBackground(bg);

        questionCardTitle = new TextView(act);
        questionCardTitle.setText("AI 向你提问");
        questionCardTitle.setTextSize(14);
        questionCardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        questionCardTitle.setTextColor(Theme.txt(act));
        questionCard.addView(questionCardTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (questions != null) {
            for (int i = 0; i < questions.length(); i++) {
                JSONObject q = questions.optJSONObject(i);
                if (q != null) addQuestionRow(q, i + 1);
            }
        }
        if (questionStates.isEmpty()) {
            addStatusRow("（空的提问）");
            removeQuestionCard();
            return;
        }

        TextView submit = new TextView(act);
        submit.setText("提交答案");
        submit.setTextSize(14);
        submit.setGravity(Gravity.CENTER);
        submit.setTextColor(Theme.onAccent(act));
        submit.setPadding(UiKit.dp(act, 14), UiKit.dp(act, 10), UiKit.dp(act, 14), UiKit.dp(act, 10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(UiKit.dp(act, 10));
        sbg.setColor(Theme.accent(act));
        submit.setBackground(sbg);
        submit.setClickable(true);
        submit.setOnClickListener(v -> submitAnswers());
        questionCard.addView(submit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        list.addView(questionCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void addQuestionRow(JSONObject q, int no) {
        final QState st = new QState();
        st.q = q;
        st.id = q.optString("id", "");
        st.multi = q.optBoolean("multi_select", false);
        JSONArray opts = q.optJSONArray("options");
        st.selected = new Boolean[opts == null ? 0 : opts.length()];
        questionStates.add(st);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiKit.dp(act, 10), 0, 0);

        TextView qv = new TextView(act);
        String header = q.optString("header", "");
        String qtext = q.optString("question", "?");
        qv.setText((header.isEmpty() ? "问题 " + no : header) + (st.multi ? "（可多选）" : "") + "\n" + qtext);
        qv.setTextSize(14);
        qv.setTypeface(Typeface.DEFAULT_BOLD);
        qv.setTextColor(Theme.txt(act));
        row.addView(qv);

        if (opts != null) {
            for (int j = 0; j < opts.length(); j++) {
                JSONObject o = opts.optJSONObject(j);
                if (o == null) continue;
                final int idx = j;
                st.selected[idx] = false;
                TextView opt = new TextView(act);
                String label = o.optString("label", "选项" + (j + 1));
                String desc = o.optString("description", "");
                opt.setText(desc.isEmpty() ? label : label + "  —  " + desc);
                opt.setTextSize(13);
                opt.setPadding(UiKit.dp(act, 12), UiKit.dp(act, 9), UiKit.dp(act, 12), UiKit.dp(act, 9));
                opt.setClickable(true);
                styleOption(opt, false);
                opt.setOnClickListener(v -> {
                    if (questionsSubmitted) return;
                    if (st.multi) {
                        st.selected[idx] = !Boolean.TRUE.equals(st.selected[idx]);
                        styleOption(opt, Boolean.TRUE.equals(st.selected[idx]));
                    } else {
                        for (int k = 0; k < st.optViews.size(); k++) {
                            st.selected[k] = k == idx;
                            styleOption(st.optViews.get(k), k == idx);
                        }
                    }
                });
                st.optViews.add(opt);
                row.addView(opt, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                View gap = new View(act);
                gap.setMinimumHeight(UiKit.dp(act, 5));
                row.addView(gap);
            }
        }

        EditText custom = new EditText(act);
        custom.setHint("或输入自定义答案…");
        custom.setTextSize(13);
        custom.setTextColor(Theme.txt(act));
        custom.setHintTextColor(Theme.sub(act));
        custom.setSingleLine(true);
        custom.setBackgroundColor(Theme.inputBg(act));
        custom.setPadding(UiKit.dp(act, 10), UiKit.dp(act, 8), UiKit.dp(act, 10), UiKit.dp(act, 8));
        st.custom = custom;
        row.addView(custom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        questionCard.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void styleOption(TextView v, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(act, 9));
        bg.setColor(selected ? Theme.accent(act) : Theme.inputBg(act));
        bg.setStroke(UiKit.dp(act, 1), selected ? Theme.accent(act) : Theme.border(act));
        v.setBackground(bg);
        v.setTextColor(selected ? Theme.onAccent(act) : Theme.txt(act));
    }

    /** 收集答案 → 回传 MainActivity 发送 user.answer */
    private void submitAnswers() {
        if (questionsSubmitted || questionCard == null) return;
        questionsSubmitted = true;
        JSONArray answers = new JSONArray();
        try {
            for (QState st : questionStates) {
                JSONObject ans = new JSONObject();
                ans.put("id", st.id);
                JSONArray sel = new JSONArray();
                if (st.selected != null) {
                    JSONArray opts = st.q.optJSONArray("options");
                    for (int i = 0; i < st.selected.length && opts != null; i++) {
                        if (Boolean.TRUE.equals(st.selected[i])) {
                            JSONObject o = opts.optJSONObject(i);
                            sel.put(o == null ? "选项" + (i + 1) : o.optString("label", "选项" + (i + 1)));
                        }
                    }
                }
                ans.put("selected", sel);
                String c = st.custom == null ? "" : st.custom.getText().toString().trim();
                if (!c.isEmpty()) ans.put("custom", c);
                answers.put(ans);
            }
        } catch (Exception e) {
            addStatusRow("答案格式错误: " + e.getMessage());
            questionsSubmitted = false;
            return;
        }
        markQuestionsSubmitted("已提交，等待回复…");
        act.sendUserAnswer(activeRequestId(), answers);
    }

    private String activeRequestId() {
        // requestId 在 MainActivity 侧存储（showUserQuestions 第一个参数）；此处用 token 字段绕过跨类存储
        return questionCard == null ? "" : (String) questionCard.getTag();
    }

    /** 卡片状态：提交后置灰 / ack 或错误回显 */
    public void markQuestionsSubmitted(String status) {
        if (questionCard == null) return;
        questionsSubmitted = true;
        if (questionCardTitle != null) {
            questionCardTitle.setText(status);
            questionCardTitle.setTextColor(Theme.sub(act));
        }
        if (questionCard != null && questionCard.getTag() instanceof String) {
            for (int i = 0; i < questionCard.getChildCount(); i++) {
                View child = questionCard.getChildAt(i);
                if (child instanceof TextView && child != questionCardTitle) child.setEnabled(false);
                child.setAlpha(0.7f);
            }
        }
    }

    private void removeQuestionCard() {
        if (questionCard != null) {
            list.removeView(questionCard);
            questionCard = null;
            questionCardTitle = null;
        }
    }

    public void onDone(String finalText) {
        busy = false;
        updateSendButton();
        if (finalText != null && !finalText.isEmpty()) {
            addMsg(new ChatMsg(ChatMsg.ASSISTANT, finalText));
        } else {
            addStatusRow("任务完成");
        }
    }

    public void onError(String error) {
        busy = false;
        updateSendButton();
        addMsg(new ChatMsg(ChatMsg.ERROR, error == null ? "未知错误" : error));
    }

    public void setSessionId(String sid) {
        if (sid == null) sid = "";
        this.sessionId = sid;
        String label = sid.isEmpty() ? "新会话" : (sid.length() > 16 ? sid.substring(0, 16) + "…" : sid);
        sessionLabel.setText(label);
        msgs.clear();
        list.removeAllViews();
        finishStreaming(); // 清流式引用，防旧会话增量污染新会话
        if (sid.isEmpty()) {
            addStatusRow("新会话");
            return;
        }
        // 打开已有会话：请求历史消息（桌面伴侣 session.messages）
        addStatusRow("加载历史…");
        JSONObject req = new JSONObject();
        try {
            req.put("op", "session.messages");
            req.put("session_id", sid);
            act.send(req);
        } catch (Exception e) {
            addStatusRow("加载失败: " + e.getMessage());
        }
    }

    /** v0.7.0：OCR 识别文本插入输入框（追加到已有内容后） */
    public void insertOcrText(String text) {
        if (text == null || text.isEmpty()) return;
        String current = input.getText() == null ? "" : input.getText().toString();
        String merged = current.trim().isEmpty() ? text : current + "\n" + text;
        input.setText(merged);
        input.setSelection(merged.length());
        input.requestFocus();
    }

    /** 桌面伴侣 session.messages.result：渲染会话历史。 */
    public void onHistoryResult(JSONArray messages) {
        msgs.clear();
        list.removeAllViews();
        if (messages == null || messages.length() == 0) {
            addStatusRow("暂无历史消息");
            return;
        }
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String role = m.optString("role", "assistant");
            String content = m.optString("content", "");
            if (content.isEmpty()) continue;
            if ("tool".equals(role)) { // 修复：工具消息渲染为工具行而非用户气泡
                ChatMsg tm = new ChatMsg(ChatMsg.TOOL, content);
                msgs.add(tm);
                renderMsg(tm);
                continue;
            }
            ChatMsg msg = new ChatMsg("assistant".equals(role) ? ChatMsg.ASSISTANT : ChatMsg.USER, content);
            msgs.add(msg);
            renderMsg(msg);
        }
        scrollToBottom();
    }

    /** 桌面伴侣 session.messages.error：历史加载失败。 */
    public void onHistoryError(String error) {
        addStatusRow("历史加载失败: " + error);
    }

    public void onShow() {
        if (!sessionId.isEmpty()) act.markRead(sessionId);
        scrollToBottom();
    }

    public void onHide() {
    }
}
