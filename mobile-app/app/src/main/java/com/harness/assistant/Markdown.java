package com.harness.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 Markdown 渲染（复用晨曦AI Markdown）：标题/粗体/斜体/行内码/代码块/列表/引用/分割线。
 * 不用第三方库，覆盖 AI 回复常见格式。
 */
public class Markdown {
    private static final int CODE_BG = 0xFF2B2B2B;
    private static final int CODE_FG = 0xFFE8E8E8;
    private static final Pattern LINK_RE = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    private static final Pattern ORDERED_RE = Pattern.compile("\\d+\\.\\s+.*");

    /** 兼容旧调用（无主题，按浅色渲染） */
    public static CharSequence render(String text) {
        return render(null, text);
    }

    /** 按当前主题渲染 markdown 文本 */
    public static CharSequence render(Context ctx, String text) {
        boolean dark = ctx != null && Theme.isDark(ctx);
        int inlineCodeBg = 0xFF2B2B2B;
        int inlineCodeFg = 0xFF7EE8C4;
        int quoteFg      = dark ? 0xFF9AA3B2 : 0xFFA1A7B0;
        int ruleFg       = dark ? 0xFF3A3F4B : 0xFF3D4148;
        int linkFg       = dark ? 0xFF7AA8F5 : 0xFF00E676;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    sb.append("\n");
                } else {
                    inCode = false;
                    sb.append("\n");
                }
                continue;
            }
            if (inCode) {
                appendCodeLine(sb, line);
                continue;
            }
            if (line.trim().isEmpty()) {
                sb.append("\n");
                continue;
            }
            String t = line.trim();
            if (t.startsWith("### ")) { appendHeading(sb, t.substring(4), 1.15f); }
            else if (t.startsWith("## ")) { appendHeading(sb, t.substring(3), 1.25f); }
            else if (t.startsWith("# ")) { appendHeading(sb, t.substring(2), 1.35f); }
            else if (t.startsWith(">")) { appendQuote(sb, t.substring(1), quoteFg); }
            else if (t.equals("---") || t.equals("***") || t.equals("___")) { appendRule(sb, ruleFg); }
            else if (t.startsWith("- ") || t.startsWith("* ")) { appendList(sb, "•  ", t.substring(2), inlineCodeBg, inlineCodeFg, linkFg); }
            else if (ORDERED_RE.matcher(t).matches()) {
                int dot = t.indexOf('.');
                appendList(sb, t.substring(0, dot + 1) + " ", t.substring(dot + 1).trim(), inlineCodeBg, inlineCodeFg, linkFg);
            }
            else { appendInline(sb, line, false, inlineCodeBg, inlineCodeFg, linkFg); }
            sb.append("\n");
        }
        return sb;
    }

    private static void appendHeading(SpannableStringBuilder sb, String content, float size) {
        int start = sb.length();
        sb.append(content);
        sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(size), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendQuote(SpannableStringBuilder sb, String content, int quoteFg) {
        int start = sb.length();
        appendInline(sb, content, true, 0, 0, 0);
        sb.setSpan(new ForegroundColorSpan(quoteFg), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendRule(SpannableStringBuilder sb, int ruleFg) {
        int start = sb.length();
        sb.append("――――――――――――――――");
        sb.setSpan(new ForegroundColorSpan(ruleFg), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendList(SpannableStringBuilder sb, String prefix, String content,
                                   int inlineCodeBg, int inlineCodeFg, int linkFg) {
        sb.append(prefix);
        appendInline(sb, content, false, inlineCodeBg, inlineCodeFg, linkFg);
    }

    private static void appendCodeLine(SpannableStringBuilder sb, String line) {
        int start = sb.length();
        sb.append(line.isEmpty() ? " " : line);
        sb.setSpan(new BackgroundColorSpan(CODE_BG), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(CODE_FG), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void appendInline(SpannableStringBuilder sb, String line, boolean quote,
                                     int inlineCodeBg, int inlineCodeFg, int linkFg) {
        int i = 0;
        int n = line.length();
        while (i < n) {
            int backtick = line.indexOf('`', i);
            if (backtick < 0) {
                appendBoldItalic(sb, line.substring(i), linkFg);
                break;
            }
            appendBoldItalic(sb, line.substring(i, backtick), linkFg);
            int close = line.indexOf('`', backtick + 1);
            if (close < 0) {
                appendBoldItalic(sb, line.substring(backtick), linkFg);
                break;
            }
            int start = sb.length();
            sb.append(line.substring(backtick + 1, close));
            sb.setSpan(new BackgroundColorSpan(inlineCodeBg), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(inlineCodeFg), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.TypefaceSpan("monospace"), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = close + 1;
        }
    }

    private static void appendBoldItalic(SpannableStringBuilder sb, String s, int linkFg) {
        int i = 0;
        while (true) {
            int b = s.indexOf("**", i);
            if (b < 0) {
                appendItalic(sb, s.substring(i), linkFg);
                break;
            }
            appendItalic(sb, s.substring(i, b), linkFg);
            int close = s.indexOf("**", b + 2);
            if (close < 0) {
                appendItalic(sb, s.substring(b), linkFg);
                break;
            }
            int start = sb.length();
            sb.append(s.substring(b + 2, close));
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = close + 2;
        }
    }

    private static void appendItalic(SpannableStringBuilder sb, String s, int linkFg) {
        int i = 0;
        while (true) {
            int b = s.indexOf('*', i);
            if (b < 0) {
                appendLink(sb, s.substring(i), linkFg);
                break;
            }
            appendLink(sb, s.substring(i, b), linkFg);
            int close = s.indexOf('*', b + 1);
            if (close < 0) {
                appendLink(sb, s.substring(b), linkFg);
                break;
            }
            int start = sb.length();
            sb.append(s.substring(b + 1, close));
            sb.setSpan(new StyleSpan(Typeface.ITALIC), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            i = close + 1;
        }
    }

    private static void appendLink(SpannableStringBuilder sb, String s, int linkFg) {
        Matcher m = LINK_RE.matcher(s);
        int last = 0;
        while (m.find()) {
            sb.append(s.substring(last, m.start()));
            int start = sb.length();
            sb.append(m.group(1));
            sb.setSpan(new ForegroundColorSpan(linkFg), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v0.6.7: 链接可点击跳转（TextView 需 setMovementMethod(LinkMovementMethod)）
            sb.setSpan(new android.text.style.URLSpan(m.group(2)), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = m.end();
        }
        sb.append(s.substring(last));
    }
}
