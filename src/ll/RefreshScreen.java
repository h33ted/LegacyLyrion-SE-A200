package ll;

import java.util.Vector;

import javax.microedition.lcdui.Graphics;

/**
 * "Refreshing library": fetches every home artwork strip that is missing or a few days old in
 * one go, with a progress bar, so nothing loads (and stutters) while the home menu scrolls.
 * Shown on the first start and about twice a week after that; Skip goes straight to the menu
 * (old strips stay, missing ones load when they're shown).
 */
final class RefreshScreen extends Screen implements Runnable {
    private final HomeScreen home;
    private final Vector todo;                  // HomeScreen.Entry
    private final int per;                      // pictures per strip
    private volatile int done;
    private volatile String now = "";
    private volatile boolean cancelled;

    RefreshScreen(LegacyLyrion app, HomeScreen home, Vector todo, int per) {
        super(app, "LegacyLyrion");
        this.home = home;
        this.todo = todo;
        this.per = Math.max(1, per);
        leftLabel = "";
        centreLabel = "";
        rightLabel = "Skip";
    }

    void start() {
        new Thread(this).start();
    }

    public void run() {
        int k = 0;
        for (; k < todo.size() && !cancelled; k++) {
            HomeScreen.Entry e = (HomeScreen.Entry) todo.elementAt(k);
            now = e.label;
            done = k * per;
            app.shell.repaint();
            home.fetchStrip(e, this);
        }
        for (int j = k; j < todo.size(); j++) ((HomeScreen.Entry) todo.elementAt(j)).loading = false;   // skipped
        if (cancelled) return;
        done = todo.size() * per;
        app.shell.repaint();
        app.display.callSerially(new Runnable() {
            public void run() {
                if (app.shell.holds(RefreshScreen.this)) app.shell.remove(RefreshScreen.this);
            }
        });
    }

    /** One more picture in (from HomeScreen.fetchStrip). */
    void picture() {
        done++;
        app.shell.repaint();
    }

    void hidden() {
        cancelled = true;                       // Skip (Back), or anything else covering it
    }

    void paint(Graphics g, int w, int h) {
        int total = todo.size() * per;
        int cy = h * 2 / 5;
        g.setFont(Style.MENU_BOLD);
        g.setColor(Style.TEXT);
        g.drawString("Refreshing library", w / 2, cy - Style.MENU_BOLD.getHeight() - 8, Graphics.TOP | Graphics.HCENTER);
        g.setFont(Style.SMALL);
        g.setColor(Style.TEXT_DIM);
        g.drawString(Style.fit(now, Style.SMALL, w - 32), w / 2, cy, Graphics.TOP | Graphics.HCENTER);
        int bx = 28, bw = w - 56, by = cy + Style.SMALL.getHeight() + 10, bh = 6;
        g.setColor(0x262626);
        g.fillRect(bx, by, bw, bh);
        g.setColor(Style.ACCENT);
        g.fillRect(bx, by, bw * Math.min(done, total) / Math.max(1, total), bh);
        g.setColor(Style.FRAME);
        g.drawRect(bx - 1, by - 1, bw + 1, bh + 1);
        g.setColor(Style.TEXT_FAINT);
        g.drawString("Artwork is kept on the phone for a few days", w / 2, by + bh + 12, Graphics.TOP | Graphics.HCENTER);
    }
}
