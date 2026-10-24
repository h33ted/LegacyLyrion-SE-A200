package ll;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/** Sony Ericsson style menu list: big rows, rounded orange highlight that follows keys and taps. */
class ListScreen extends Screen {

    /** One row. action: what the app does with it (see LegacyLyrion.A_*). */
    static final class Row {
        String title, sub;
        int glyph;
        int action;
        Item item;

        Row(String title, String sub, int glyph, int action, Item item) {
            this.title = title == null ? "" : title;
            this.sub = sub == null ? "" : sub;
            this.glyph = glyph;
            this.action = action;
            this.item = item;
        }
    }

    /** Replaced as a whole by setRows (never edited in place), so painting can't race a reload. */
    volatile Vector rows = new Vector();
    Hashtable base;          // Jive "base" of the list these rows came from
    String status;           // "Loading...", errors, "Empty"
    int focus, scroll;
    private int viewH = 1;

    ListScreen(LegacyLyrion app, String title) {
        super(app, title);
    }

    /** Rows are taller when any of them has a second line (artist, description). */
    private boolean twoLine() {
        Vector rs = rows;
        for (int k = 0; k < rs.size(); k++) if (((Row) rs.elementAt(k)).sub.length() > 0) return true;
        return false;
    }

    int rowH() {
        return twoLine() ? Style.ROW.getHeight() + Style.SMALL.getHeight() + 12 : Style.ROW.getHeight() + 14;
    }

    Row current() {
        Vector rs = rows;
        int f = focus;
        return f >= 0 && f < rs.size() ? (Row) rs.elementAt(f) : null;
    }

    // panel geometry, in content coordinates
    private static final int PX = 8, PY = 6;
    private int listTop;

    private int headH() {
        return Style.TITLE.getHeight() + 12;
    }

    void paint(Graphics g, int w, int h) {
        int pw = w - 2 * PX, ph = h - PY - 4;
        Style.panel(g, PX, PY, pw, ph);
        g.setFont(Style.TITLE);
        g.setColor(Style.TEXT);
        g.drawString(Style.fit(title, Style.TITLE, pw - 20), PX + 10, PY + 6, Graphics.TOP | Graphics.LEFT);
        listTop = PY + headH();
        viewH = Math.max(1, ph - headH() - 4);
        clamp();                                    // the height changes when the phone turns
        Vector rows = this.rows;
        if (rows.size() == 0) {
            g.setFont(Style.ROW);
            g.setColor(Style.TEXT_FAINT);
            g.drawString(status == null ? "" : status, PX + 12, listTop + 4, Graphics.TOP | Graphics.LEFT);
            return;
        }
        int rh = rowH();
        boolean two = twoLine();
        g.clipRect(PX + 1, listTop, pw - 2, viewH);
        int first = Math.max(0, scroll / rh);
        int tw = pw - 30;
        for (int k = first; k < rows.size(); k++) {
            int y = listTop + k * rh - scroll;
            if (y > listTop + viewH) break;
            Row r = (Row) rows.elementAt(k);
            boolean hi = k == focus;
            if (hi) Style.focusFrame(g, PX + 6, y + 1, pw - 12, rh - 2);
            int tx = PX + 12;
            g.setFont(Style.ROW);
            g.setColor(hi ? Style.FOCUS : Style.TEXT_DIM);
            int ty = two ? y + 5 : y + (rh - Style.ROW.getHeight()) / 2;
            g.drawString(Style.fit(r.title, Style.ROW, tw), tx, ty, Graphics.TOP | Graphics.LEFT);
            if (two && r.sub.length() > 0) {
                g.setFont(Style.SMALL);
                g.setColor(hi ? Style.FOCUS_SUB : Style.TEXT_FAINT);
                g.drawString(Style.fit(r.sub, Style.SMALL, tw), tx, ty + Style.ROW.getHeight(), Graphics.TOP | Graphics.LEFT);
            }
        }
        // scroll thumb
        int total = rows.size() * rh;
        if (total > viewH) {
            int th = Math.max(16, viewH * viewH / total);
            int ty = listTop + (viewH - th) * scroll / Math.max(1, total - viewH);
            g.setColor(0x6a6a6a);
            g.fillRect(PX + pw - 5, ty, 2, th);
        }
    }

    private void clamp() {
        int max = Math.max(0, rows.size() * rowH() - viewH);
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    private void reveal() {
        int rh = rowH();
        if (focus * rh < scroll) scroll = focus * rh;
        if ((focus + 1) * rh > scroll + viewH) scroll = (focus + 1) * rh - viewH;
        clamp();
    }

    void tap(int x, int y, int w, int h) {
        if (y < listTop) return;
        int k = (y - listTop + scroll) / rowH();
        if (k >= 0 && k < rows.size()) {
            focus = k;
            app.shell.repaint();
            select();
        }
    }

    void drag(int dy) {
        scroll -= dy;
        clamp();
    }

    void key(int game, int keyCode) {
        int n = rows.size();
        if (n == 0) return;
        if (game == Canvas.UP || keyCode == Canvas.KEY_NUM2) focus = (focus - 1 + n) % n;
        else if (game == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) focus = (focus + 1) % n;
        else if (game == Canvas.LEFT || keyCode == Canvas.KEY_NUM4) focus = Math.max(0, focus - Math.max(1, viewH / rowH()));
        else if (game == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6) focus = Math.min(n - 1, focus + Math.max(1, viewH / rowH()));
        else return;
        reveal();
    }

    void select() {
        Row r = current();
        if (r != null) app.activate(this, r);
    }

    String[] options() {
        return app.optionsFor(this, current());
    }

    void option(int index) {
        app.option(this, current(), index);
    }

    void setRows(Vector v, Hashtable b, String emptyText) {
        Vector copy = new Vector(v.size());
        for (int k = 0; k < v.size(); k++) copy.addElement(v.elementAt(k));
        base = b;
        focus = 0;
        scroll = 0;
        status = copy.size() == 0 ? emptyText : null;
        rows = copy;
    }
}
