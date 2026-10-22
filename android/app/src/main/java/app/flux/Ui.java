package app.flux;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view helpers shared by every screen (the app builds its UI in code: no layout XML to break). */
final class Ui {
    static final int INK = 0xFF0A0A0A, MUTED = 0xFF6B7280, LINE = 0xFFE6E8EC, SOFT = 0xFFF6F7F9, GREEN = 0xFF16A34A, RED = 0xFFDC2626, ACCENT = 0xFF2F7DF6, WHITE = Color.WHITE;
    private Ui() {}

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable rounded(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp)); g.setColor(fill);
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    static TextView text(Context c, float sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static LinearLayout vbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout hbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(android.view.Gravity.CENTER_VERTICAL); return l; }

    static void press(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(.98f).scaleY(.98f).setDuration(80).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
    }

    /** A vector icon from res/drawable by name (looked up at runtime so this file has no generated-R dependency). */
    static ImageView icon(Context c, String name, int sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id != 0) iv.setImageResource(id);
        iv.setColorFilter(tint);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** kind 0 = solid black, 1 = outlined, 2 = quiet. */
    static Button button(Context c, String label, int kind) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setTypeface(Typeface.DEFAULT_BOLD); b.setStateListAnimator(null);
        b.setTextColor(kind == 0 ? WHITE : INK);
        b.setBackground(kind == 0 ? rounded(c, INK, 14, 0) : kind == 1 ? rounded(c, WHITE, 14, 0xFFD5D9E0) : rounded(c, Color.TRANSPARENT, 14, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(c, kind == 2 ? 44 : 54)); lp.topMargin = dp(c, 10);
        b.setLayoutParams(lp);
        press(b);
        return b;
    }

    static View spacer(Context c, int heightDp) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp))); return v; }
    static View flex(Context c) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f)); return v; }
}
