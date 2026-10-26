package app.flux;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * FLUX's design system for the phone app: ONE type scale, ONE spacing scale, ONE set of components.
 * Screens must not invent sizes: they pick a {@link Type}, a spacing step and a component from here.
 *
 *  TYPE (sp; every size scales with the user's system font size)
 *    DISPLAY 32  wordmark / hero numbers        TITLE 26  screen titles          HEADLINE 20  state headlines
 *    SECTION 18  section headings               SUBHEAD 16 semibold  row titles  BODY 16  reading text
 *    BODY_SM 14  supporting text / values       CAPTION 13  metadata (never smaller)   LABEL 16  buttons
 *    TAB 14 medium  tab labels                  NAV 12 medium  bottom-navigation labels   STAT 32  numbers (tabular)
 *  SPACING (dp): 4 · 8 · 12 · 16 · 24 · 32      screen gutter: 24 (16 on screens narrower than 360dp)
 *  RADII (dp): 12 small · 16 medium · 20 large   TOUCH: 48dp minimum for anything tappable
 *  COLOUR: every text colour passes WCAG AA (4.5:1) on white. FAINT is for icons and disabled states only.
 */
final class Ui {
    // ---- colour
    static final int INK = 0xFF0A0A0A, MUTED = 0xFF5F6775, FAINT = 0xFF8A93A1, LINE = 0xFFE6E8EC, SOFT = 0xFFF6F7F9, WHITE = Color.WHITE;
    static final int GREEN = 0xFF15803D, GREEN_BG = 0xFFE8F5EC, RED = 0xFFB42318, RED_BG = 0xFFFDECEA, ACCENT = 0xFF2457D6, ON_INK_MUTED = 0xFFC3C8D2;

    // ---- spacing scale (dp)
    static final int S4 = 4, S8 = 8, S12 = 12, S16 = 16, S24 = 24, S32 = 32;
    static final int TOUCH = 48, ROW_MIN = 64, R_SM = 12, R_MD = 16, R_LG = 20;

    private Ui() {}

    static int dp(Context c, int v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    /** Screen edge margin: 24dp, or 16dp on narrow (< 360dp) screens. */
    static int gutter(Context c) { return dp(c, c.getResources().getConfiguration().screenWidthDp < 360 ? S16 : S24); }

    // ---- type
    static final class Type {
        final float sp; final int weight; final float line; final float tracking;
        Type(float sp, int weight, float line, float tracking) { this.sp = sp; this.weight = weight; this.line = line; this.tracking = tracking; }
    }
    static final Type DISPLAY = new Type(32, 700, 1.10f, 0f), TITLE = new Type(26, 700, 1.15f, 0f), HEADLINE = new Type(20, 600, 1.25f, 0f),
            SECTION = new Type(18, 600, 1.25f, 0f), SUBHEAD = new Type(16, 600, 1.25f, 0f), BODY = new Type(16, 400, 1.35f, 0f),
            BODY_SM = new Type(14, 400, 1.35f, 0f), CAPTION = new Type(13, 400, 1.30f, 0f), LABEL = new Type(16, 600, 1.15f, 0f),
            TAB = new Type(14, 500, 1.15f, 0f), NAV = new Type(12, 500, 1.15f, 0f), STAT = new Type(32, 700, 1.05f, 0f),
            WORDMARK = new Type(26, 700, 1.05f, 0.14f);

    static void apply(TextView v, Type t, int color) {
        v.setTextColor(color); v.setTextSize(t.sp);                                   // setTextSize(float) is sp: scales with the system font size
        v.setTypeface(Typeface.create(Typeface.DEFAULT, t.weight, false));
        v.setLineSpacing(0f, t.line); v.setIncludeFontPadding(false);
        v.setLetterSpacing(t.tracking);
    }

    /** A text view with explicit LayoutParams: full width, wrap height. Never rely on a parent's default (that caused left-aligned headings). */
    static TextView text(Context c, CharSequence s, Type t, int color) {
        TextView v = new TextView(c); v.setText(s); apply(v, t, color);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return v;
    }
    /** Tabular figures so digits line up and don't jitter while numbers change. */
    static TextView num(Context c, CharSequence s, Type t, int color) { TextView v = text(c, s, t, color); v.setFontFeatureSettings("tnum"); return v; }
    static TextView center(TextView v) { v.setGravity(Gravity.CENTER_HORIZONTAL); return v; }
    static TextView lines(TextView v, int max) { v.setMaxLines(max); v.setEllipsize(android.text.TextUtils.TruncateAt.END); return v; }

    // ---- containers
    static LinearLayout vbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL); return l; }
    static LinearLayout hbox(Context c) { LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    static View flex(Context c) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1f)); return v; }
    static View divider(Context c) { View v = new View(c); v.setBackgroundColor(LINE); v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1) / 2 + 1))); return v; }

    static GradientDrawable rounded(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable(); g.setCornerRadius(dp(c, radiusDp)); g.setColor(fill);
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static android.graphics.drawable.Drawable ripple(Context c, boolean borderless) {
        TypedArray a = c.obtainStyledAttributes(new int[]{borderless ? android.R.attr.selectableItemBackgroundBorderless : android.R.attr.selectableItemBackground});
        android.graphics.drawable.Drawable d = a.getDrawable(0); a.recycle(); return d;
    }

    static void press(View v) {
        v.setOnTouchListener((x, e) -> {
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(.98f).scaleY(.98f).setDuration(80).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) x.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            return false;
        });
    }

    // ---- icons
    /** A vector icon from res/drawable by name (looked up at runtime so this file needs no generated-R). */
    static ImageView icon(Context c, String name, int sizeDp, int tint) {
        ImageView iv = new ImageView(c);
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id != 0) iv.setImageResource(id);
        iv.setColorFilter(tint); iv.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return iv;
    }

    /** Icon-only button with a full 48dp touch target and an accessibility label. */
    static ImageView iconButton(Context c, String name, String description, int tint) {
        ImageView iv = new ImageView(c);
        int id = c.getResources().getIdentifier(name, "drawable", c.getPackageName());
        if (id != 0) iv.setImageResource(id);
        iv.setColorFilter(tint); iv.setPadding(dp(c, S12), dp(c, S12), dp(c, S12), dp(c, S12)); iv.setBackground(ripple(c, true));
        iv.setContentDescription(description);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(c, TOUCH), dp(c, TOUCH)));
        return iv;
    }

    /** A circular badge with an icon inside (status marks, row leading icons). */
    static LinearLayout circle(Context c, String icon, int iconTint, int fill, int ring, int sizeDp) {
        LinearLayout b = new LinearLayout(c); b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(fill); if (ring != 0) g.setStroke(Math.max(2, dp(c, 2)), ring);
        b.setBackground(g); b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        b.addView(icon(c, icon, sizeDp / 2, iconTint));
        return b;
    }

    static ProgressBar spinner(Context c, int sizeDp, int tint) {
        ProgressBar p = new ProgressBar(c); p.setIndeterminateTintList(ColorStateList.valueOf(tint));
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return p;
    }

    // ---- buttons. kind 0 = primary (solid ink), 1 = secondary (outlined), 2 = quiet (text only).
    static Button button(Context c, CharSequence label, int kind) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); b.setStateListAnimator(null); b.setGravity(Gravity.CENTER); b.setMaxLines(2);
        apply(b, LABEL, kind == 0 ? WHITE : INK);
        b.setMinHeight(dp(c, 52)); b.setMinimumHeight(dp(c, 52)); b.setMinWidth(dp(c, TOUCH)); b.setMinimumWidth(dp(c, TOUCH));
        b.setPadding(dp(c, S16), dp(c, S12), dp(c, S16), dp(c, S12));
        b.setBackground(kind == 0 ? rounded(c, INK, R_MD, 0) : kind == 1 ? rounded(c, WHITE, R_MD, 0xFFCBD1DA) : rounded(c, Color.TRANSPARENT, R_MD, 0));
        b.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        press(b);
        return b;
    }
}
