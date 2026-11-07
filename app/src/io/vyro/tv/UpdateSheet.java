package io.vyro.tv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * The update sheet (design "B1 · Editorial sheet"): from the right, over the dimmed home screen, the new version, its
 * size and date, what's new, and Install / Later. Shown once per version when the home screen is at rest (HomeView),
 * or from Settings › Updates. Install hands the checked APK to Android's installer (Updates.install), which asks again;
 * Later and Back put this version off. Its text is laid out once when it opens, never while it moves.
 */
final class UpdateSheet {
    private final HomeView home;
    boolean open;
    private Updates.Offer offer;
    private int focus;   // 0 Install, 1 Later
    private boolean okDown, backDown;
    private final Spring show = new Spring(0.3f, 1f, 0);
    private final Spring[] buttons = {new Spring(0.24f, 0.9f, 0), new Spring(0.24f, 0.9f, 0)};
    private Paint title, titleIt, chip, label, heading, ring, button;
    private TextPaint sub, meta, bullet, note;
    private final List<StaticLayout> notes = new ArrayList<>();
    private StaticLayout subLayout, noteLayout;
    private final RectF sheet = new RectF(), r = new RectF();
    private float laidOutK;

    private static final int PANEL = 0xFF161214, IVORY = 0xFFF3EDE2, FOCUS = 0xFFF3EADB, INK = 0xFF1A1614, ACCENT = 0xFFFFB27A;
    private static final float W = 760, PAD_X = 80, PAD_TOP = 120, PAD_BOTTOM = 96, BUTTON_H = 72;

    UpdateSheet(HomeView home) {
        this.home = home;
    }

    void show(Updates.Offer o) {
        if (o == null) return;
        offer = o;
        open = true;
        focus = 0;
        okDown = backDown = false;
        buttons[0].snap(1);
        buttons[1].snap(0);
        laidOutK = 0;   // laid out on the first draw, at the screen's scale
        show.target = 1;
        home.updateLayer.setVisibility(HomeView.VISIBLE);
        home.updateLayer.invalidate();
        home.updateLayer.announceForAccessibility(Ui.trf("Update ready: version {v}. Install or Later.", "v", o.version));
        home.wake();
    }

    /** Closes it; `later`: this version isn't offered again on its own. */
    void dismiss(boolean later, boolean snap) {
        if (!open && show.value <= 0.001f) return;
        if (later && offer != null) Updates.later(home.getContext(), offer.code);
        open = false;
        show.target = 0;
        if (snap) {
            show.snap(0);
            home.updateLayer.setVisibility(HomeView.GONE);
        }
        home.wake();
    }

    /** One frame: the fade and slide while it opens or closes, the buttons' focus. */
    boolean step(float dt) {
        boolean moving = show.step(dt);
        for (int i = 0; i < 2; i++) {
            buttons[i].target = open && i == focus ? 1 : 0;
            moving |= buttons[i].step(dt);
        }
        if (moving) {
            home.updateLayer.setAlpha(Ui.clamp(show.value));
            home.updateLayer.invalidate();
        }
        if (!open && show.value <= 0.002f && home.updateLayer.getVisibility() != HomeView.GONE) {
            home.updateLayer.setVisibility(HomeView.GONE);
        }
        return moving;
    }

    boolean key(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int next = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT ? 0 : 1;
                if (down && next != focus) {
                    focus = next;
                    home.click.move();
                    home.wake();
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (e.getRepeatCount() == 0) okDown = true;
                } else if (okDown && !e.isCanceled()) {
                    okDown = false;
                    home.click.open();
                    if (focus == 0) install();
                    else dismiss(true, false);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (down) {
                    if (e.getRepeatCount() == 0) backDown = true;
                } else if (backDown && !e.isCanceled()) {
                    backDown = false;
                    dismiss(true, false);   // Back is Later
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                return false;
            default:
                return true;   // nothing reaches the screen under it
        }
    }

    private void install() {
        Updates.Offer o = offer;
        if (o == null) return;
        String problem = Updates.install(home.activity, o);
        dismiss(false, problem == null);   // handed over: the installer's screen comes next
        if (problem != null) home.showToast(problem);
    }

    // MARK: - Drawing

    void draw(Canvas c) {
        if (offer == null) return;
        float k = home.k;
        if (laidOutK != k) layOut(k);
        float v = Ui.clamp(show.value);
        home.fill.setShader(null);
        home.fill.setColor(0x80000000);   // the home screen under it, dimmed
        c.drawRect(0, 0, home.w, home.h, home.fill);
        c.save();
        c.translate((1 - v) * 80 * k, 0);   // slides in a little as it fades in
        sheet.set(home.w - W * k, 0, home.w, home.h);
        home.shadow.setColor(0x73000000);
        r.set(sheet);
        r.offset(-18 * k, 0);
        home.shadows.draw(c, r, 0, HomeView.SHADOW_BLUR * k, home.shadow);
        home.fill.setColor(PANEL);
        c.drawRect(sheet, home.fill);

        float x = sheet.left + PAD_X * k, right = sheet.right - PAD_X * k;
        float y = PAD_TOP * k;
        home.fill.setColor(IVORY);   // the launcher's headings: a short ivory bar, then the serif title
        r.set(x, y, x + 48 * k, y + 4 * k);
        c.drawRoundRect(r, 2 * k, 2 * k, home.fill);
        y += 26 * k + Ui.halfCap(title) * 2;
        String fresh = Ui.tr("Fresh") + " ";
        c.drawText(fresh, x, y, title);
        c.drawText(Ui.tr("build"), x + title.measureText(fresh), y, titleIt);
        y += 28 * k;
        y = drawLayout(c, subLayout, x, y) + 26 * k;

        // "1.1 → 1.2" and the size and date.
        String version = offer.installed + " → " + offer.version;
        float cw = chip.measureText(version) + 36 * k;
        r.set(x, y, x + cw, y + 44 * k);
        home.fill.setColor(0x1AF3EDE2);
        c.drawRoundRect(r, 22 * k, 22 * k, home.fill);
        c.drawText(version, x + 18 * k, r.centerY() + Ui.halfCap(chip), chip);
        String m = UpdateLogic.sizeLabel(offer.size) + (offer.date.isEmpty() ? "" : " · " + Ui.trf("released {date}", "date", offer.date));
        c.drawText(TextUtils.ellipsize(m, meta, right - r.right - 14 * k, TextUtils.TruncateAt.END).toString(),
                r.right + 14 * k, r.centerY() + Ui.halfCap(meta), meta);
        y = r.bottom + 40 * k + Ui.halfCap(heading) * 2;
        c.drawText(Ui.tr("WHAT’S NEW"), x, y, heading);
        y += 22 * k;

        // The buttons at the bottom, the note above them, the changes in the room between.
        float bTop = home.h - (PAD_BOTTOM + BUTTON_H) * k;
        float noteTop = bTop - 26 * k - noteLayout.getHeight();
        drawLayout(c, noteLayout, x, noteTop);
        float limit = noteTop - 24 * k;
        for (StaticLayout l : notes) {
            if (y + l.getHeight() > limit) break;   // what doesn't fit is left out, never cut
            home.fill.setColor(0xE6FFFFFF);
            c.drawCircle(x + 6 * k, y + l.getLineBaseline(0) - Ui.halfCap(bullet), 3.5f * k, home.fill);
            drawLayout(c, l, x + 24 * k, y);
            y += l.getHeight() + 12 * k;
        }
        float gap = 16 * k, bw = (right - x - gap) / 2;
        drawButton(c, 0, Ui.tr("Install"), x, bTop, bw);
        drawButton(c, 1, Ui.tr("Later"), x + bw + gap, bTop, bw);
        c.restore();
    }

    private float drawLayout(Canvas c, StaticLayout l, float x, float y) {
        c.save();
        c.translate(x, y);
        l.draw(c);
        c.restore();
        return y + l.getHeight();
    }

    private void drawButton(Canvas c, int i, String text, float x, float y, float bw) {
        float k = home.k, f = Ui.clamp(buttons[i].value), rad = BUTTON_H * k / 2;
        r.set(x, y, x + bw, y + BUTTON_H * k);
        home.fill.setColor(home.blend(0x0DF3EDE2, FOCUS, f));
        c.drawRoundRect(r, rad, rad, home.fill);
        if (f < 0.99f) {
            home.border.setStrokeWidth(1 * k);
            home.border.setColor(Ui.alpha(IVORY, 0.12f * (1 - f)));
            c.drawRoundRect(r, rad, rad, home.border);
            home.border.setStrokeWidth(2 * k);
        }
        if (f > 0.01f) {   // the launcher's focus: an ivory ring a little outside
            ring.setColor(Ui.alpha(IVORY, f));
            r.inset(-4.5f * k, -4.5f * k);
            c.drawRoundRect(r, rad + 4.5f * k, rad + 4.5f * k, ring);
            r.inset(4.5f * k, 4.5f * k);
        }
        button.setColor(home.blend(IVORY, INK, f));
        c.drawText(text, r.centerX(), r.centerY() + Ui.halfCap(button), button);
    }

    /** Paints and wrapped text, once per opening (and screen scale): nothing is measured while it moves. */
    private void layOut(float k) {
        laidOutK = k;
        title = Ui.text(Ui.displaySemibold, 72 * k, IVORY);
        titleIt = Ui.text(Ui.displayItalic, 72 * k, IVORY);
        chip = Ui.text(Ui.semibold, 21 * k, IVORY);
        heading = Ui.text(Ui.semibold, 19 * k, ACCENT);
        heading.setLetterSpacing(0.08f);
        button = Ui.text(Ui.semibold, 25 * k, IVORY);
        button.setTextAlign(Paint.Align.CENTER);
        ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * k);
        sub = new TextPaint(Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF));
        meta = new TextPaint(Ui.text(Ui.medium, 19 * k, 0x99FFFFFF));
        bullet = new TextPaint(Ui.text(Ui.medium, 23 * k, 0xE6FFFFFF));
        note = new TextPaint(Ui.text(Ui.medium, 19 * k, 0x99FFFFFF));
        int width = Math.round((W - 2 * PAD_X) * k);
        subLayout = wrap(Ui.tr("A new version of VYRO is ready."), sub, width, 2);
        noteLayout = wrap(Ui.tr("Android will ask you to confirm. Your settings, tabs and pins stay as they are."), note, width, 3);
        notes.clear();
        List<String> lines = offer.notes.isEmpty() ? java.util.Collections.singletonList(Ui.tr("No notes for this release."))
                : offer.notes;
        for (String n : lines) notes.add(wrap(n, bullet, width - Math.round(24 * k), 2));
    }

    private static StaticLayout wrap(String text, TextPaint p, int width, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.18f).setIncludePad(false)
                .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).build();
    }
}
