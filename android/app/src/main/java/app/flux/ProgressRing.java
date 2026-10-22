package app.flux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Circular progress (real values only: 0..1). */
final class ProgressRing extends View {
    private float progress;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    ProgressRing(Context c) {
        super(c);
        float w = Ui.dp(c, 9);
        track.setStyle(Paint.Style.STROKE); track.setStrokeWidth(w); track.setColor(0xFFE5E7EB);
        arc.setStyle(Paint.Style.STROKE); arc.setStrokeWidth(w); arc.setColor(Ui.INK); arc.setStrokeCap(Paint.Cap.ROUND);
    }

    void setProgress(float p) { progress = Math.max(0f, Math.min(1f, p)); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float pad = track.getStrokeWidth() / 2f + 1;
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        c.drawArc(box, 0, 360, false, track);
        if (progress > 0) c.drawArc(box, -90, 360f * progress, false, arc);
    }
}
