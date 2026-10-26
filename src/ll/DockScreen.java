package ll;

import java.util.Calendar;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Docked Now Playing (Aino in its stand), modelled on the phone's standby clock. By default only
 * the artwork, a large white clock in the phone's own font with the date under it, and the title
 * and album. Touching the screen (or any key) shows the transport buttons and soft keys for a few
 * seconds. Same keys, polling and options as Now Playing; Undock leaves docked mode.
 */
final class DockScreen extends NowPlayingScreen {
    private static final Font DATE = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    private static final Font CLOCK_FALLBACK = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_LARGE);
    private static final int SHOW_MS = 5000;
    private static final int CLOCK_ALPHA = 75;               // percent: slightly see-through, like the standby clock
    private static final int SCROLL_PAUSE = 1500, SCROLL_SPEED = 30, SCROLL_GAP = 40;   // ms, px/s, px

    private Image clockImg;                                  // the clock over its piece of background, redrawn each minute
    private String clockKey;
    private long scrollStart;
    private String scrollKey;
    private volatile boolean scrolling, animating;
    private volatile int animGen;

    private BigText big;
    private boolean bigTried;
    private long showUntil;

    DockScreen(LegacyLyrion app) {
        super(app);
        title = "Docked";
        rightLabel = "Undock";
    }

    /** Artwork fills the height of the landscape screen. */
    int coverSize() {
        return Math.max(64, Math.min(Math.min(app.shell.lw(), app.shell.lh()) - 24, 216));
    }

    private boolean controls() {
        return System.currentTimeMillis() < showUntil;
    }

    private void reveal() {
        showUntil = System.currentTimeMillis() + SHOW_MS;
        app.shell.repaint();
    }

    /** Landscape and untouched: no soft keys, the whole screen is the dock. */
    boolean bare() {
        return app.shell.lw() > app.shell.lh() && !controls();
    }

    void paint(Graphics g, int w, int h) {
        if (w <= h) {                                // held upright (docked by hand): normal layout
            super.paint(g, w, h);
            return;
        }
        boolean ctl = controls();

        // artwork
        int s = Math.min(h - 24, w / 2 - 16);
        int ax = 12, ay = (h - s) / 2;
        if (cover != null) {
            g.clipRect(ax, ay, s, s);
            g.drawImage(cover, ax + s / 2, ay + s / 2, Graphics.VCENTER | Graphics.HCENTER);
            g.setClip(0, 0, w, h);
        } else {
            g.setColor(0x202020);
            g.fillRect(ax, ay, s, s);
            Style.glyph(g, Style.G_NOTE, ax + s / 2, ay + s / 2, s / 3, 0x555555);
        }

        // right column, everything aligned to its right edge like the standby clock
        int left = ax + s + 16, right = w - 12, cw = right - left;
        BigText b = bigText();
        int px = Math.min(cw * 10 / 26, h * 2 / 5);
        int clockH = b != null ? px * 82 / 100 : CLOCK_FALLBACK.getHeight();
        int br2 = ctl ? Math.max(10, Math.min(16, h / 14)) : 0;
        int total = clockH + 8 + DATE.getHeight() + 14 + Style.BIG.getHeight() + Style.SMALL.getHeight()
                + (ctl ? 12 + 2 * br2 + 6 : 0);
        int y = Math.max(4, (h - total) / 2);

        Calendar c = Calendar.getInstance();
        int hh = c.get(Calendar.HOUR_OF_DAY), mm = c.get(Calendar.MINUTE);
        String clock = hh + ":" + (mm < 10 ? "0" : "") + mm;
        if (b != null) {
            try {
                drawClock(g, b, clock, right, y - px / 5, px, w, h);   // the SVG box has room above the digits
            } catch (Throwable t) {
                app.log("Dock clock: SVG failed (" + t + ")");
                big = null;
                b = null;
            }
        }
        if (b == null) {
            g.setFont(CLOCK_FALLBACK);
            g.setColor(Style.TEXT);
            g.drawString(clock, right, y, Graphics.TOP | Graphics.RIGHT);
        }
        y += clockH + 8;
        g.setFont(DATE);
        g.setColor(Style.TEXT);
        int day = c.get(Calendar.DAY_OF_MONTH), mon = c.get(Calendar.MONTH) + 1;
        g.drawString((day < 10 ? "0" : "") + day + "." + (mon < 10 ? "0" : "") + mon + "." + c.get(Calendar.YEAR),
                right, y, Graphics.TOP | Graphics.RIGHT);
        y += DATE.getHeight() + 14;

        // title and album, scrolling when they don't fit
        String sub = album.length() > 0 ? album : artist;
        long now = System.currentTimeMillis();
        if (!(track + "\n" + sub).equals(scrollKey)) {
            scrollKey = track + "\n" + sub;
            scrollStart = now;
        }
        g.setColor(Style.TEXT);
        boolean moving = line(g, track, Style.BIG, left, right, y, w, h, now);
        y += Style.BIG.getHeight();
        g.setColor(Style.TEXT_DIM);
        moving |= line(g, sub, Style.SMALL, left, right, y, w, h, now);
        scrolling = moving;
        y += Style.SMALL.getHeight();

        volY = -1000;                               // no volume bar here: up/down keys still work
        if (!ctl) {
            br = 0;
            by = -1000;
            return;
        }
        // shown after a touch: progress and transport
        y += 8;
        g.setColor(0x3a3a3a);
        g.fillRoundRect(left, y, cw, 3, 3, 3);
        if (dur > 0) {
            g.setColor(Style.ACCENT);
            g.fillRoundRect(left, y, Math.max(3, cw * Math.min(time, dur) / dur), 3, 3, 3);
        }
        y += 10;
        int cx = left + cw / 2;
        br = br2;
        by = y + br;
        bx = cx;
        circle(g, cx - br * 3, by, br * 3 / 4, Style.G_PREV, false);
        circle(g, cx, by, br, playing ? Style.G_PAUSE : Style.G_PLAY, true);
        circle(g, cx + br * 3, by, br * 3 / 4, Style.G_NEXT, false);
    }

    /** The clock from a cached image: SVG is rendered only when the minute or the layout changes. */
    private void drawClock(Graphics g, BigText b, String text, int right, int y0, int px, int w, int h) throws Exception {
        int iw = px * 3, ih = px * 6 / 5, x0 = right - iw;
        String k = text + "/" + px + "/" + right + "/" + y0 + "/" + w + "x" + h;
        if (!k.equals(clockKey)) {
            Image img = Image.createImage(iw, ih);
            Graphics ig = img.getGraphics();
            ig.translate(-x0, -y0);                       // the background behind the clock, so translucency works
            Style.background(ig, w, h);
            ig.translate(x0, y0);
            b.draw(ig, text, iw, 0, px, CLOCK_ALPHA);
            clockImg = img;
            clockKey = k;
        }
        g.drawImage(clockImg, x0, y0, Graphics.TOP | Graphics.LEFT);
    }

    /**
     * One line, right-aligned when it fits; otherwise it waits, then scrolls round, marquee-style.
     * Returns true while it is scrolling.
     */
    private boolean line(Graphics g, String text, Font f, int left, int right, int y, int w, int h, long now) {
        g.setFont(f);
        int cw = right - left, tw = f.stringWidth(text);
        if (tw <= cw) {
            g.drawString(text, right, y, Graphics.TOP | Graphics.RIGHT);
            return false;
        }
        int loop = tw + SCROLL_GAP;
        long cycle = SCROLL_PAUSE + (long) loop * 1000 / SCROLL_SPEED;
        long ph = (now - scrollStart) % cycle;
        int off = ph < SCROLL_PAUSE ? 0 : (int) ((ph - SCROLL_PAUSE) * SCROLL_SPEED / 1000);
        g.clipRect(left, y, cw, f.getHeight());
        g.drawString(text, left - off, y, Graphics.TOP | Graphics.LEFT);
        g.drawString(text, left - off + loop, y, Graphics.TOP | Graphics.LEFT);
        g.setClip(0, 0, w, h);
        return true;
    }

    void shown() {
        super.shown();
        animating = true;
        final int my = ++animGen;                         // one animator per visit
        new Thread(new Runnable() {                       // about 14 frames a second, only while text scrolls
            public void run() {
                while (animating && my == animGen) {
                    try { Thread.sleep(70); } catch (InterruptedException e) {}
                    if (scrolling && animating) app.shell.repaint();
                }
            }
        }).start();
    }

    void hidden() {
        animating = false;
        super.hidden();
    }

    /** SvgText if the phone has JSR-226, else null (MIDP's largest font is used). */
    private BigText bigText() {
        if (!bigTried) {
            bigTried = true;
            try {
                Class.forName("javax.microedition.m2g.ScalableGraphics");
                big = (BigText) Class.forName("ll.SvgText").newInstance();
                app.log("Dock clock: SVG");
            } catch (Throwable t) {
                app.log("Dock clock: no SVG, using the largest font");
                big = null;
            }
        }
        return big;
    }

    void tap(int x, int y, int w, int h) {
        boolean was = controls();
        reveal();
        if (was) super.tap(x, y, w, h);              // the first touch only shows the buttons
    }

    void key(int game, int keyCode) {
        reveal();
        super.key(game, keyCode);
    }

    void select() {
        reveal();
        super.select();
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
