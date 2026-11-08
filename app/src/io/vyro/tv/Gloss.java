package io.vyro.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Home 2.0's play disc (design "Icons B"): glossy white, 3D (lit from the top, a gloss band over its top half), the
 * play symbol on it in black (#000000) over a soft shadow. Main thread only (shared paints); nothing allocated per frame.
 */
final class Gloss {
    private Gloss() {
    }

    /** The play symbol's colour, top to bottom (one black). */
    static final int[] INK = {0xFF000000, 0xFF000000};

    private static final Paint FILL = new Paint(Paint.ANTI_ALIAS_FLAG), MASK = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Unit gradients (0 to 1, top to bottom), mapped onto each shape with M.
    private static final LinearGradient DISC = unit(0xFFFFFFFF, 0xFFD9DDE6), DISC_BAND = unit(0xF2FFFFFF, 0x00FFFFFF),
            ICON = unit(INK[0], INK[1]);
    private static final Matrix M = new Matrix();
    private static final RectF R = new RectF(), B = new RectF();

    static {
        MASK.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
    }

    private static LinearGradient unit(int top, int bottom) {
        return new LinearGradient(0, 0, 0, 1, top, bottom, Shader.TileMode.CLAMP);
    }

    private static void mapTo(Shader g, RectF r) {
        M.setScale(1, r.height());
        M.postTranslate(0, r.top);
        g.setLocalMatrix(M);
    }

    /** The play disc: glossy white, 3D; the icon goes on it with icon(). */
    static void disc(Canvas c, float cx, float cy, float rad, float k, float alpha) {
        int a = Math.round(255 * Ui.clamp(alpha));
        B.set(cx - rad, cy - rad, cx + rad, cy + rad);
        mapTo(DISC, B);
        FILL.setShader(DISC);
        FILL.setAlpha(a);
        c.drawCircle(cx, cy, rad, FILL);
        R.set(cx - rad * 0.72f, cy - rad * 0.9f, cx + rad * 0.72f, cy - rad * 0.06f);
        mapTo(DISC_BAND, R);
        FILL.setShader(DISC_BAND);
        c.drawRoundRect(R, R.height() / 2, R.height() / 2, FILL);
        FILL.setShader(null);
        FILL.setAlpha(255);
    }

    /** The symbol named, in INK at (cx, cy), over its own shadow (pointSize as Symbols.draw). */
    static void icon(Canvas c, Context ctx, String symbol, float cx, float cy, float pointSize, float k, float alpha) {
        if (alpha <= 0.01f) return;
        Symbols.draw(ctx, c, symbol, cx, cy + 2.5f * k, pointSize, Ui.alpha(0xFF000000, 0.22f * alpha));
        B.set(cx - pointSize, cy - pointSize, cx + pointSize, cy + pointSize);
        int saved = c.saveLayer(B, null);
        Symbols.draw(ctx, c, symbol, cx, cy, pointSize, 0xFFFFFFFF);
        R.set(cx - pointSize / 2, cy - pointSize * 0.45f, cx + pointSize / 2, cy + pointSize * 0.45f);
        mapTo(ICON, R);
        MASK.setShader(ICON);
        MASK.setAlpha(Math.round(255 * Ui.clamp(alpha)));
        c.drawRect(B, MASK);
        MASK.setShader(null);
        c.restoreToCount(saved);
    }
}
