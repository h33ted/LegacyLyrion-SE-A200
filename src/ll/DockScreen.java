package ll;

import java.util.Calendar;

import javax.microedition.lcdui.Graphics;

/**
 * Docked Now Playing (Aino in its stand): large artwork on the left; a big segment clock, the
 * date, the track and small transport buttons on the right. Same keys, polling and options as
 * Now Playing. Back (Undock) leaves docked mode until the charger is unplugged.
 */
final class DockScreen extends NowPlayingScreen {
    private static final String[] DAYS = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTHS = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};
    // segments a b c d e f g as bits 0..6
    private static final int[] SEG = {0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};
    private static final int SEG_OFF = 0x1a1a1a;

    DockScreen(LegacyLyrion app) {
        super(app);
        title = "Docked";
        rightLabel = "Undock";
    }

    /** Artwork fills the height of the landscape screen. */
    int coverSize() {
        int s = Math.min(app.shell.lw(), app.shell.lh()) - Style.BIG.getHeight() - 20;
        return Math.max(64, Math.min(s, 200));
    }

    void paint(Graphics g, int w, int h) {
        if (w <= h) {                                // held upright (docked by hand): normal layout
            super.paint(g, w, h);
            return;
        }
        // artwork
        int s = Math.min(h - 16, w / 2 - 20);
        int ax = 10, ay = (h - s) / 2;
        g.setColor(0x333333);
        g.drawRect(ax - 1, ay - 1, s + 1, s + 1);
        if (cover != null) {
            g.clipRect(ax, ay, s, s);
            g.drawImage(cover, ax + s / 2, ay + s / 2, Graphics.VCENTER | Graphics.HCENTER);
            g.setClip(0, 0, w, h);
        } else {
            g.setColor(0x202020);
            g.fillRect(ax, ay, s, s);
            Style.glyph(g, Style.G_NOTE, ax + s / 2, ay + s / 2, s / 3, 0x555555);
        }

        int left = ax + s + 14, cw = w - left - 8, cx = left + cw / 2;
        int y = Math.max(6, ay);

        // clock
        Calendar c = Calendar.getInstance();
        int hh = c.get(Calendar.HOUR_OF_DAY), mm = c.get(Calendar.MINUTE);
        int dh = Math.min(52, h / 4);
        while (dh > 20 && clockWidth(dh) > cw) dh--;
        drawClock(g, new int[]{hh / 10, hh % 10, mm / 10, mm % 10}, cx - clockWidth(dh) / 2, y, dh);
        y += dh + 6;
        g.setFont(Style.SMALL);
        g.setColor(Style.TEXT_DIM);
        String date = DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
        g.drawString(date, cx, y, Graphics.TOP | Graphics.HCENTER);
        y += Style.SMALL.getHeight() + 10;

        // track
        g.setFont(Style.ROW_BOLD);
        g.setColor(Style.TEXT);
        g.drawString(Style.fit(track, Style.ROW_BOLD, cw), cx, y, Graphics.TOP | Graphics.HCENTER);
        y += Style.ROW_BOLD.getHeight();
        g.setFont(Style.SMALL);
        g.setColor(Style.TEXT_DIM);
        String sub = album.length() > 0 ? artist + " - " + album : artist;
        g.drawString(Style.fit(sub, Style.SMALL, cw), cx, y, Graphics.TOP | Graphics.HCENTER);
        y += Style.SMALL.getHeight() + 6;

        // progress
        g.setColor(0x3a3a3a);
        g.fillRoundRect(left, y, cw, 3, 3, 3);
        if (dur > 0) {
            g.setColor(Style.ACCENT);
            g.fillRoundRect(left, y, Math.max(3, cw * Math.min(time, dur) / dur), 3, 3, 3);
        }
        y += 8;

        // transport, sized to what is left
        br = Math.max(10, Math.min(18, (h - y) / 2 - 2));
        by = y + br;
        bx = cx;
        circle(g, cx - br * 3, by, br * 3 / 4, Style.G_PREV, false);
        circle(g, cx, by, br, playing ? Style.G_PAUSE : Style.G_PLAY, true);
        circle(g, cx + br * 3, by, br * 3 / 4, Style.G_NEXT, false);
        volY = -1000;                               // no volume bar here: up/down keys still work
    }

    private static int clockWidth(int dh) {
        int dw = dh * 11 / 20, gap = Math.max(3, dh / 8), colon = Math.max(6, dh / 4);
        return dw * 4 + gap * 2 + colon * 2;
    }

    private void drawClock(Graphics g, int[] d, int x, int y, int dh) {
        int dw = dh * 11 / 20, gap = Math.max(3, dh / 8), colon = Math.max(6, dh / 4);
        int t = Math.max(3, dh / 9);
        digit(g, d[0], x, y, dw, dh, t);
        x += dw + gap;
        digit(g, d[1], x, y, dw, dh, t);
        x += dw;
        int dx = x + (2 * colon - t) / 2;           // colon centred in its slot, in the app's orange
        g.setColor(Style.ACCENT);
        g.fillRoundRect(dx, y + dh / 3 - t / 2, t, t, t, t);
        g.fillRoundRect(dx, y + dh * 2 / 3 - t / 2, t, t, t, t);
        x += 2 * colon;
        digit(g, d[2], x, y, dw, dh, t);
        x += dw + gap;
        digit(g, d[3], x, y, dw, dh, t);
    }

    private static void digit(Graphics g, int n, int x, int y, int w, int h, int t) {
        int m = SEG[n], half = h / 2, v = half - t - t / 2;
        seg(g, (m & 1) != 0, x + t, y, w - 2 * t, t, t);                  // a top
        seg(g, (m & 2) != 0, x + w - t, y + t, t, v, t);                  // b upper right
        seg(g, (m & 4) != 0, x + w - t, y + half + t / 2, t, v, t);       // c lower right
        seg(g, (m & 8) != 0, x + t, y + h - t, w - 2 * t, t, t);          // d bottom
        seg(g, (m & 16) != 0, x, y + half + t / 2, t, v, t);              // e lower left
        seg(g, (m & 32) != 0, x, y + t, t, v, t);                         // f upper left
        seg(g, (m & 64) != 0, x + t, y + half - t / 2, w - 2 * t, t, t);  // g middle
    }

    private static void seg(Graphics g, boolean on, int x, int y, int w, int h, int r) {
        g.setColor(on ? Style.TEXT : SEG_OFF);
        g.fillRoundRect(x, y, w, h, r, r);
    }

    String[] options() {
        return new String[]{"Undock", "Choose player", "Shuffle on/off", "Repeat: off/all/one"};
    }

    void option(int i) {
        if (i == 0) app.shell.remove(this);
        else if (i == 1) app.openPlayers();
        else if (i == 2) send(new String[]{"playlist", "shuffle"});
        else if (i == 3) send(new String[]{"playlist", "repeat"});
    }
}
