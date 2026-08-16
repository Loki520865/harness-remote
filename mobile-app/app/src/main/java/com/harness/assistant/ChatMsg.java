package com.harness.assistant;

import org.json.JSONObject;

/**
 * 聊天消息模型（复用晨曦AI 精简版）：用户/助手/工具/错误 四种来源。
 * streaming=true 表示正在流式输出中（增量追加到同一气泡）。
 */
public class ChatMsg {
    public static final int USER = 0;
    public static final int ASSISTANT = 1;
    public static final int TOOL = 2;
    public static final int ERROR = 3;
    public static final int REASONING = 4; // v0.6.2: 模型思考步骤（向电脑 web 靠拢）

    public int type;
    public String text;
    public boolean streaming;

    // v0.6.6: 卡片折叠/展开（思考卡、工具卡）
    public String fullText;   // 完整内容（折叠时点击展开显示全文）
    public boolean expanded;  // 当前展开状态

    // TOOL 卡片
    public int toolStatus;
    public String toolName;

    public static final int TOOL_PENDING = 0;
    public static final int TOOL_RUNNING = 1;
    public static final int TOOL_DONE = 2;
    public static final int TOOL_FAILED = 4;

    public ChatMsg(int type, String text) {
        this.type = type;
        this.text = text == null ? "" : text;
        this.streaming = false;
    }

    public ChatMsg append(String delta) {
        if (delta != null) text += delta;
        return this;
    }
}
