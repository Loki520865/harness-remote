package com.harness.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 简约 UI 工具（复用晨曦AI UiKit）：统一二级菜单 / 确认框 / 内容弹窗 / 返回键风格。
 */
public final class UiKit {

    public static final int NORMAL = 0;
    public static final int DANGER = 1;

    public static final class Item {
        public final String label;
        public final int style;
        public final boolean checked;
        public final Runnable action;
        public Item(String label, Runnable action) { this(label, false, NORMAL, action); }
        public Item(String label, int style, Runnable action) { this(label, false, style, action); }
        public Item(String label, boolean checked, Runnable action) { this(label, checked, NORMAL, action); }
        public Item(String label, boolean checked, int style, Runnable action) {
            this.label = label;
            this.checked = checked;
            this.style = style;
            this.action = action;
        }
    }

    public static void menu(Activity a, String title, Item... items) {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 8), dp(a, 6), dp(a, 8), dp(a, 8));

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            t.setTextColor(Theme.sub(a));
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 10));
            box.addView(t);
            box.addView(line(a));
        }

        for (int i = 0; i < items.length; i++) {
            final Item it = items[i];
            TextView row = new TextView(a);
            String label = it.checked ? "✓ " + it.label : it.label;
            row.setText(label);
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            row.setTextColor(it.style == DANGER ? Theme.errTxt(a) : (it.checked ? Theme.accent(a) : Theme.sub(a)));
            row.setGravity(Gravity.CENTER);
            row.setMinHeight(dp(a, 50));
            row.setPadding(dp(a, 16), 0, dp(a, 16), 0);
            row.setClickable(true);
            row.setOnClickListener(v -> { if (dlg[0] != null) dlg[0].dismiss(); it.action.run(); });
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < items.length - 1) box.addView(line(a));
        }

        dlg[0] = new AlertDialog.Builder(a)
                .setView(box)
                .setOnCancelListener(d -> { })
                .create();
        if (dlg[0].getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(a, 20));
            bg.setColor(Theme.card(a));
            dlg[0].getWindow().setBackgroundDrawable(bg);
        }
        dlg[0].show();
    }

    public static void confirm(Activity a, String title, String message,
                               String okLabel, boolean danger, Runnable onOk) {
        sheet(a, title, messageView(a, message),
                new Btn("取消", null),
                new Btn(okLabel, danger, onOk));
    }

    public static final class Btn {
        public final String label;
        public final boolean danger;
        public final Runnable action;
        public Btn(String label, Runnable action) { this(label, false, action); }
        public Btn(String label, boolean danger, Runnable action) {
            this.label = label;
            this.danger = danger;
            this.action = action;
        }
    }

    public static void sheet(Activity a, String title, View content, Btn... btns) {
        final AlertDialog[] dlg = new AlertDialog[1];
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 10));

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            t.setTextColor(Theme.txt(a));
            t.setTypeface(null, Typeface.BOLD);
            t.setGravity(Gravity.CENTER);
            box.addView(t);
        }

        if (content != null) {
            box.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout btnsRow = new LinearLayout(a);
        btnsRow.setOrientation(LinearLayout.HORIZONTAL);
        btnsRow.setPadding(0, dp(a, 10), 0, 0);
        if (btns == null || btns.length == 0) btns = new Btn[]{ new Btn("关闭", null) };
        for (int i = 0; i < btns.length; i++) {
            final Btn b = btns[i];
            TextView bv = btnText(a, b.label, b.danger ? Theme.errTxt(a) : Theme.accent(a));
            bv.setMinHeight(dp(a, 44));
            bv.setOnClickListener(v -> {
                if (dlg[0] != null) dlg[0].dismiss();
                if (b.action != null) b.action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i > 0) lp.leftMargin = dp(a, 6);
            btnsRow.addView(bv, lp);
        }
        box.addView(btnsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dlg[0] = new AlertDialog.Builder(a)
                .setView(box)
                .setOnCancelListener(d -> { })
                .create();
        if (dlg[0].getWindow() != null) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(a, 20));
            bg.setColor(Theme.card(a));
            dlg[0].getWindow().setBackgroundDrawable(bg);
        }
        dlg[0].show();
    }

    public static TextView messageView(Activity a, String message) {
        TextView m = new TextView(a);
        m.setText(message);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        m.setTextColor(Theme.sub(a));
        m.setGravity(Gravity.CENTER);
        m.setPadding(0, dp(a, 10), 0, 0);
        return m;
    }

    public static View scrollMessageView(Activity a, String message) {
        ScrollView sv = new ScrollView(a);
        TextView m = new TextView(a);
        m.setText(message);
        m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        m.setTextColor(Theme.sub(a));
        m.setPadding(0, dp(a, 10), 0, 0);
        m.setMaxHeight(dp(a, 280));
        sv.addView(m);
        return sv;
    }

    private static TextView btnText(Activity a, String text, int color) {
        TextView v = new TextView(a);
        v.setText(text);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        v.setTextColor(color);
        v.setGravity(Gravity.CENTER);
        v.setClickable(true);
        return v;
    }

    private static View line(Context c) {
        View v = new View(c);
        v.setBackgroundColor(Theme.divider(c));
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static ImageButton headerBack(Context c) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(R.drawable.ic_back);
        b.setColorFilter(Theme.sub(c));
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, 0, 0, 0);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setContentDescription("返回");
        return b;
    }

    private UiKit() {}
}
