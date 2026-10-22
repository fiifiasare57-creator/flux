package app.flux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Line illustration: phone - link - laptop (decorative; it never implies a connection state). */
final class Illustration extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    Illustration(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE); p.setColor(Ui.INK); p.setStrokeWidth(Ui.dp(c, 2)); p.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cy = h / 2f, s = Ui.dp(getContext(), 1);
        // phone
        float pw = Math.min(h * 0.46f, w * 0.16f), ph = Math.min(h * 0.86f, pw * 1.9f), px = w * 0.5f - Math.min(w * 0.34f, Ui.dp(getContext(), 120));
        r.set(px - pw / 2, cy - ph / 2, px + pw / 2, cy + ph / 2); c.drawRoundRect(r, 8 * s, 8 * s, p);
        c.drawLine(px - pw * 0.18f, cy - ph / 2 + 6 * s, px + pw * 0.18f, cy - ph / 2 + 6 * s, p);
        // laptop
        float lw = Math.min(w * 0.30f, Ui.dp(getContext(), 120)), lh = lw * 0.62f, lx = w * 0.5f + Math.min(w * 0.34f, Ui.dp(getContext(), 120));
        r.set(lx - lw / 2, cy - lh / 2 - 4 * s, lx + lw / 2, cy + lh / 2 - 4 * s); c.drawRoundRect(r, 5 * s, 5 * s, p);
        c.drawLine(lx - lw * 0.62f, cy + lh / 2 + 2 * s, lx + lw * 0.62f, cy + lh / 2 + 2 * s, p);
        // link (two chain loops) between them
        float mx = w * 0.5f, lk = 9 * s;
        r.set(mx - lk * 1.6f, cy - lk * 0.8f, mx + lk * 0.2f, cy + lk * 0.8f); c.drawRoundRect(r, lk * 0.8f, lk * 0.8f, p);
        r.set(mx - lk * 0.2f, cy - lk * 0.8f, mx + lk * 1.6f, cy + lk * 0.8f); c.drawRoundRect(r, lk * 0.8f, lk * 0.8f, p);
    }
}
