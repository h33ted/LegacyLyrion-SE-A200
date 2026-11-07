package ll;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * The start menu, in the style of Sony Ericsson's 2009 media apps: a thin rail on the left with
 * the two sections (Spotify, and the music stored on the Lyrion server), a vertical menu of big grey entries, and the focused entry opened up into a
 * glass box with a preview underneath (search field, artwork strip, or a line of text). The box
 * stays put and the menu slides past it.
 */
final class HomeScreen extends Screen implements Runnable {

    static final int P_NONE = 0, P_SEARCH = 1, P_THUMBS = 2, P_TEXT = 3;

    static final class Entry {
        final String label;
        final int glyph, action, preview;
        final String spotty;            // Spotty top-level menu to take artwork from (P_THUMBS)
        int sec;                        // SPOTIFY, YOUTUBE or LOCAL
        Vector thumbs;                  // Images, once loaded
        boolean loading;
        String fit;                     // label shortened for fitW, so it isn't re-measured every frame
        int fitW = -1;

        String fitted(javax.microedition.lcdui.Font f, int w) {
            if (w != fitW) {
                fit = Style.fit(label, f, w);
                fitW = w;
            }
            return fit;
        }

        Entry(String label, int glyph, int action, int preview, String spotty) {
            this.label = label;
            this.glyph = glyph;
            this.action = action;
            this.preview = preview;
            this.spotty = spotty;
        }
    }

    static final int SPOTIFY = 0, LOCAL = 1, YOUTUBE = 2;
    private int[] RAIL = {SPOTIFY, YOUTUBE, LOCAL};   // top to bottom; only the sources the server has
    private final Vector[] sections = {new Vector(), new Vector(), new Vector()};
    private Vector entries = sections[SPOTIFY];
    private int section;
    private int focus;
    private volatile int anim;          // slide offset in pixels, eases back to 0
    private boolean animating;
    private int[] tops = new int[0], bottoms = new int[0];
    private int railX, noteY, boxY;
    private int dragAcc;
    private boolean onRail;             // left key moved the focus to the source rail

    static HomeScreen last;             // for Settings > Refresh artwork

    HomeScreen(LegacyLyrion app) {
        super(app, "LegacyLyrion");
        last = this;
        leftLabel = "Hide";
    }

    boolean leftDirect() {
        return true;
    }

    void left() {
        app.minimise();
    }

    private long lastStar;

    // ------------------------------------------------------------------ typing into the search box
    //
    // With Search focused, the keypad types straight into the box, multi-tap style: press 2 three
    // times for "c", wait a moment (or press another key) to move on. 0 is a space, 1 punctuation,
    // hold a key for its digit, # switches between letters and digits, C deletes, centre searches.
    // Tapping the box (or pressing centre while it's empty) opens the phone's own text input.

    private static final String[] TAPS = {" 0", ".,?!'-&1", "abc2", "def3", "ghi4", "jkl5", "mno6", "pqrs7", "tuv8", "wxyz9"};
    private static final int TAP_MS = 900;
    private final StringBuffer query = new StringBuffer();
    private boolean digits;
    private int tapKey = -1, tapIndex;
    private long tapTime;

    private boolean searchFocused() {
        return focus < entries.size() && ((Entry) entries.elementAt(focus)).preview == P_SEARCH;
    }

    private boolean pending() {
        return tapKey >= 0 && System.currentTimeMillis() - tapTime < TAP_MS;
    }

    boolean typed(int keyCode, boolean repeat) {
        if (onRail || !searchFocused()) return false;
        if (keyCode == Canvas.KEY_POUND) {
            if (!repeat) digits = !digits;
            tapKey = -1;
            return true;
        }
        if (keyCode < Canvas.KEY_NUM0 || keyCode > Canvas.KEY_NUM9) return false;
        int d = keyCode - Canvas.KEY_NUM0;
        if (repeat) {                                   // held: the digit itself
            if (!digits && tapKey == d && query.length() > 0) query.setCharAt(query.length() - 1, (char) ('0' + d));
            tapKey = -1;
            return true;
        }
        if (digits) {
            query.append((char) ('0' + d));
            tapKey = -1;
        } else if (d == tapKey && pending() && query.length() > 0) {
            tapIndex = (tapIndex + 1) % TAPS[d].length();
            query.setCharAt(query.length() - 1, TAPS[d].charAt(tapIndex));
            tapTime = System.currentTimeMillis();
        } else {
            tapKey = d;
            tapIndex = 0;
            tapTime = System.currentTimeMillis();
            query.append(TAPS[d].charAt(0));
        }
        redrawLater();
        return true;
    }

    boolean backspace() {
        if (!searchFocused() || query.length() == 0) return false;
        query.setLength(query.length() - 1);
        tapKey = -1;
        return true;
    }

    /** Repaints once the multi-tap window has passed, so the underline turns into a cursor. */
    private void redrawLater() {
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(TAP_MS + 50); } catch (InterruptedException e) {}
                app.shell.repaint();
            }
        }).start();
    }

    void add(int sec, String label, int glyph, int action, int preview, String param) {
        Entry e = new Entry(label, glyph, action, preview, param);
        e.sec = sec;
        sections[sec].addElement(e);
    }

    /** Switches between the Spotify and the local-library menu (rail icons, or left/right). */
    void setSection(int sec) {
        show(sec);
        app.setSource(section);                         // a choice: remembered
    }

    /** Shows a source without remembering it as the user's choice (start-up, before the check). */
    void show(int sec) {
        if (sec < 0 || sec >= sections.length) sec = SPOTIFY;
        section = sec;
        entries = sections[sec];
        if (focus >= entries.size()) focus = Math.max(0, entries.size() - 1);
        anim = 0;
        app.shell.repaint();
    }

    /**
     * Shows only the sources this server has on the rail (the library is always there), and
     * moves off a source whose plugin has gone.
     */
    void setAvailable(boolean spotty, boolean youtube) {
        int n = 1 + (spotty ? 1 : 0) + (youtube ? 1 : 0);
        int[] r = new int[n];
        int k = 0;
        if (spotty) r[k++] = SPOTIFY;
        if (youtube) r[k++] = YOUTUBE;
        r[k] = LOCAL;
        RAIL = r;
        boolean here = false;
        for (k = 0; k < r.length; k++) if (r[k] == section) here = true;
        if (!here) setSection(r[0]);
        app.shell.repaint();
    }

    private int railPos() {
        for (int k = 0; k < RAIL.length; k++) if (RAIL[k] == section) return k;
        return 0;
    }

    int section() {
        return section;
    }

    // ------------------------------------------------------------------ metrics

    private static int pitch() {
        return Style.MENU.getHeight() + 20;
    }

    private static int thumb() {
        return 34;
    }

    private static int previewH(Entry e) {
        if (e.preview == P_THUMBS) return thumb() + 10;
        if (e.preview == P_NONE) return 4;
        return Style.ROW.getHeight() + 16;
    }

    // ------------------------------------------------------------------ painting

    void paint(Graphics g, int w, int h) {
        int n = entries.size();
        if (tops.length != n) {
            tops = new int[n];
            bottoms = new int[n];
        }
        railX = Math.max(36, w / 6);
        int P = pitch();
        int iconX = railX + 34, textX = railX + 58;
        boxY = h * 43 / 100 - P / 2 + anim;
        long t0 = System.currentTimeMillis();

        // rail
        g.setColor(0x3a3a3a);
        g.drawLine(railX, 0, railX, h);
        g.setColor(0x1a1a1a);
        g.drawLine(railX + 1, 0, railX + 1, h);
        noteY = h * 36 / 100;
        int[] rail = RAIL;
        for (int k = 0; k < rail.length; k++) {
            int glyph = rail[k] == SPOTIFY ? Style.G_STREAM : rail[k] == YOUTUBE ? Style.G_TUBE : Style.G_SERVER;
            railIcon(g, glyph, railX / 2, noteY + k * (P + 6), section == rail[k]);
        }

        long t1 = System.currentTimeMillis();
        Entry f = n > 0 ? (Entry) entries.elementAt(focus) : null;
        int bh = f == null ? 0 : P + previewH(f);

        for (int k = 0; k < n; k++) {
            Entry e = (Entry) entries.elementAt(k);
            int top;
            if (k < focus) top = boxY - (focus - k) * P;
            else if (k == focus) top = boxY;
            else top = boxY + bh + (k - focus - 1) * P;
            tops[k] = top;
            bottoms[k] = k == focus ? top + bh : top + P;
            if (bottoms[k] < 0 || top > h) continue;
            if (k == focus) continue;               // drawn last, on top
            Style.glyph(g, e.glyph, iconX, top + P / 2, 24, Style.ICON);
            g.setFont(Style.MENU);
            g.setColor(Style.TEXT_DIM);
            g.drawString(e.fitted(Style.MENU, w - textX - 8), textX, top + (P - Style.MENU.getHeight()) / 2,
                    Graphics.TOP | Graphics.LEFT);
        }
        long t2 = System.currentTimeMillis();
        if (f != null) paintFocus(g, f, w, iconX, textX, P, bh);
        long t3 = System.currentTimeMillis();
        partRail += t1 - t0;
        partEntries += t2 - t1;
        partFocus += t3 - t2;
        if (++partFrames >= 60) {                       // where the home screen's time goes, for Diagnostics
            app.log("Home parts: rail " + partRail / partFrames + " ms, entries " + partEntries / partFrames
                    + " ms, focus box " + partFocus / partFrames + " ms");
            partFrames = 0;
            partRail = partEntries = partFocus = 0;
        }
    }

    private int partFrames;
    private long partRail, partEntries, partFocus;

    private void railIcon(Graphics g, int glyph, int x, int y, boolean on) {
        if (on) {                                   // the section glows; brighter while the rail has the keys
            int r = 22;
            Style.halo(g, x, y, r, onRail ? 210 : 120);
            g.setColor(0x3a3a3a);                   // the halo is drawn solid, so put the rail line back
            g.drawLine(railX, y - r, railX, y + r);
            g.setColor(0x1a1a1a);
            g.drawLine(railX + 1, y - r, railX + 1, y + r);
        }
        Style.glyph(g, glyph, x, y, 26, on ? Style.ICON_FOCUS : 0x5a5a5a);
    }

    /** Forgets the artwork strips so they're fetched again (Settings > Refresh artwork). */
    void refreshArt() {
        for (int s = 0; s < sections.length; s++) {
            for (int k = 0; k < sections[s].size(); k++) {
                Entry e = (Entry) sections[s].elementAt(k);
                e.thumbs = null;
                e.loading = false;
            }
        }
        artReady = false;
        app.shell.repaint();
        LegacyLyrion.bg(new Runnable() {
            public void run() {
                prepareArt();
            }
        });
    }

    volatile boolean artReady;          // strips may be loaded lazily (after prepareArt, or if it couldn't run)

    /** Most pictures a strip ever shows (landscape), so turning the phone needs no new fetch. */
    static int maxThumbs() {
        Shell s = Shell.instance;
        int w = Math.max(s.lw(), s.lh());
        int railX = Math.max(36, w / 6);
        int pw = w - (railX + 10) - 6 - 16;
        return Math.max(1, (pw + 2) / (thumb() + 2));
    }

    private String artKey(Entry e) {
        return app.lms.host + "|" + e.sec + "|" + e.spotty + "|" + thumb();
    }

    /**
     * Once the sources are known: shows every cached strip at once, and if any is missing or a
     * few days old, puts RefreshScreen on top to fetch those with a progress bar. Runs off the
     * UI thread (it reads RMS).
     */
    void prepareArt() {
        Vector todo = new Vector();
        int[] rail = RAIL;
        for (int r = 0; r < rail.length; r++) {
            Vector v = sections[rail[r]];
            for (int k = 0; k < v.size(); k++) {
                Entry e = (Entry) v.elementAt(k);
                if (e.preview != P_THUMBS || e.spotty == null) continue;
                Object[] c = ArtCache.get(artKey(e));
                if (c != null) e.thumbs = decode((Vector) c[1]);
                long age = c == null ? -1 : System.currentTimeMillis() - ((Long) c[0]).longValue();
                if (c == null || age < 0 || age >= ArtCache.MAX_AGE) {
                    e.loading = true;                   // RefreshScreen has it
                    todo.addElement(e);
                }
            }
        }
        artReady = true;
        app.shell.repaint();
        if (todo.size() == 0) return;
        final RefreshScreen rs = new RefreshScreen(app, this, todo, maxThumbs());
        app.display.callSerially(new Runnable() {
            public void run() {
                app.shell.push(rs);
                rs.start();
            }
        });
    }

    /** Fetches an entry's strip from the server, keeps it in ArtCache and shows it; false if it failed. */
    boolean fetchStrip(Entry e, RefreshScreen progress) {
        int count = maxThumbs();
        Vector imgs = new Vector(), raw = new Vector();
        try {
            Vector items = e.spotty.startsWith("local:") ? app.peekLocal(e.spotty.substring(6), count)
                    : app.peek(e.sec, e.spotty, count);
            for (int k = 0; k < items.size() && k < count; k++) {
                Item it = (Item) items.elementAt(k);
                byte[] b = null;
                try {
                    String url = app.lms.art(it.icon, thumb());
                    if (url != null) b = app.lms.imageBytes(url);
                } catch (Exception ignored) {}
                raw.addElement(b);
                imgs.addElement(decode(b));
                if (progress != null) progress.picture();
            }
        } catch (Exception ex) {
            app.log("Home artwork, " + e.label + ": " + ex.getMessage());
            return false;
        }
        String err = ArtCache.put(artKey(e), raw);
        if (err != null) app.log("Home artwork, " + e.label + ": not saved (" + err + ")");
        e.thumbs = imgs;
        app.shell.repaint();
        return true;
    }

    private static Vector decode(Vector raw) {
        Vector v = new Vector();
        for (int k = 0; k < raw.size(); k++) v.addElement(decode((byte[]) raw.elementAt(k)));
        return v;
    }

    private static Image decode(byte[] b) {
        if (b == null) return null;
        try {
            return Image.createImage(b, 0, b.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private void paintFocus(Graphics g, Entry e, int w, int iconX, int textX, int P, int bh) {
        int bx = railX + 10, bw = w - bx - 6;
        int py = boxY + P - 2, px = bx + 8, pw = bw - 16;
        int fh = Style.ROW.getHeight() + 8;
        boolean search = e.preview == P_SEARCH;
        // the glass is worked out for where the box comes to rest and slides along with it, so it
        // never pops in; the boxes for each kind of entry stay cached, so sliding costs one drawImage
        Style.focusBox(g, bx, boxY, bw, bh, px, py, search ? pw : 0, fh, boxY - anim);
        Style.glyph(g, e.glyph, iconX - 2, boxY + P / 2, 32, Style.ICON_FOCUS);
        g.setFont(Style.MENU_BOLD);
        g.setColor(Style.TEXT);
        g.drawString(Style.fit(e.label, Style.MENU_BOLD, w - textX - 10), textX, boxY + (P - Style.MENU_BOLD.getHeight()) / 2,
                Graphics.TOP | Graphics.LEFT);

        if (e.preview == P_SEARCH) {                    // the field itself is drawn into the glass box
            g.setFont(Style.ROW);
            if (query.length() > 0) {                   // what's being typed, with a cursor
                String q = query.toString();
                int tw = Style.ROW.stringWidth(q);
                int room = pw - 14;
                int x = px + 6 - Math.max(0, tw - room);    // keep the end in view
                int ox = g.getClipX(), oy = g.getClipY(), ow = g.getClipWidth(), oh = g.getClipHeight();
                g.clipRect(px + 2, py, pw - 4, fh);
                g.setColor(Style.TEXT);
                g.drawString(q, x, py + 4, Graphics.TOP | Graphics.LEFT);
                int cx2 = x + tw;
                if (pending()) {                        // multi-tap letter still changeable: underline it
                    int lw = Style.ROW.charWidth(q.charAt(q.length() - 1));
                    g.setColor(Style.ACCENT);
                    g.fillRect(cx2 - lw, py + fh - 5, lw, 2);
                } else {
                    g.setColor(Style.ACCENT);
                    g.fillRect(cx2 + 1, py + 4, 2, Style.ROW.getHeight());
                }
                g.setClip(ox, oy, ow, oh);
            } else {                                    // last search as a faint hint
                g.setColor(Style.TEXT_FAINT);
                String q = app.lastQuery == null ? "" : app.lastQuery;
                g.drawString(Style.fit(q, Style.ROW, pw - 12), px + 6, py + 4, Graphics.TOP | Graphics.LEFT);
            }
            g.setFont(Style.SMALL);
            g.setColor(Style.TEXT);
            g.drawString(digits ? "123" : "abc", px + pw, py - 2, Graphics.BOTTOM | Graphics.RIGHT);
        } else if (e.preview == P_THUMBS) {
            int t = thumb();
            int count = Math.max(1, (pw + 2) / (t + 2));
            int x0 = px + (pw - (count * (t + 2) - 2)) / 2;
            Vector th = e.thumbs;
            if (th == null && !e.loading && artReady) loadThumbs(e);
            for (int k = 0; k < count; k++) {
                int x = x0 + k * (t + 2);
                Image img = th != null && k < th.size() ? (Image) th.elementAt(k) : null;
                if (img != null) {
                    g.drawImage(img, x + t / 2, py + 2 + t / 2, Graphics.VCENTER | Graphics.HCENTER);
                } else {
                    g.setColor(0x1e1e1e);
                    g.fillRect(x, py + 2, t, t);
                    g.setColor(0x2e2e2e);
                    g.drawRect(x, py + 2, t - 1, t - 1);
                }
            }
        } else if (e.preview == P_TEXT) {
            g.setFont(Style.SMALL);
            g.setColor(Style.TEXT_DIM);
            g.drawString(Style.fit(previewText(e), Style.SMALL, pw), px, py + 3, Graphics.TOP | Graphics.LEFT);
        }
    }

    private String previewText(Entry e) {
        switch (e.action) {
            case LegacyLyrion.A_NOW:
                if (app.npTitle == null) return "Nothing playing";
                return app.npArtist != null && app.npArtist.length() > 0 ? app.npTitle + " - " + app.npArtist : app.npTitle;
            case LegacyLyrion.A_PLAYERS:
                return "Playing on: " + app.playerLabel();
            case LegacyLyrion.A_BROWSE:
                return "Top tracks, new releases, genres";
            case LegacyLyrion.A_APP:
                return "On YouTube Music";
            case LegacyLyrion.A_LOCAL:
                return "In your library on " + app.lms.host;
            case LegacyLyrion.A_SETTINGS:
                return "Server " + app.lms.host + ", rotation, diagnostics";
        }
        return "";
    }

    /** A strip not prepared up front (Skip, or the source check failed): the cache, else the server. */
    private void loadThumbs(final Entry e) {
        e.loading = true;
        LegacyLyrion.bg(new Runnable() {
            public void run() {
                Object[] cached = ArtCache.get(artKey(e));
                if (cached != null) e.thumbs = decode((Vector) cached[1]);
                else if (!fetchStrip(e, null)) e.thumbs = new Vector();
                app.shell.repaint();
            }
        });
    }

    // ------------------------------------------------------------------ movement

    private void move(int to) {
        int n = entries.size();
        if (n == 0) return;
        to = (to % n + n) % n;
        if (to == focus) return;
        int d = to - focus;
        focus = to;
        // slide: start where the menu was and ease into place (wrapping just snaps)
        anim = Math.abs(d) == 1 ? d * pitch() : 0;
        if (anim != 0) animate();
        app.shell.repaint();
    }

    private synchronized void animate() {
        if (animating) return;
        animating = true;
        new Thread(this).start();
    }

    public void run() {
        try {
            while (anim != 0) {
                int a = anim * 55 / 100;
                anim = Math.abs(a) < 2 ? 0 : a;
                app.shell.repaint();
                try { Thread.sleep(30); } catch (InterruptedException e) { break; }
            }
        } finally {
            synchronized (this) {
                animating = false;
            }
        }
    }

    void key(int game, int keyCode) {
        if (onRail) {                                   // the source rail has the focus
            if (game == Canvas.UP || keyCode == Canvas.KEY_NUM2) setSection(RAIL[(railPos() + RAIL.length - 1) % RAIL.length]);
            else if (game == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) setSection(RAIL[(railPos() + 1) % RAIL.length]);
            else if (game == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6) onRail = false;
            return;
        }
        if (game == Canvas.UP || keyCode == Canvas.KEY_NUM2) move(focus - 1);
        else if (game == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) move(focus + 1);
        else if (game == Canvas.LEFT || keyCode == Canvas.KEY_NUM4) onRail = true;
        else if (keyCode == Canvas.KEY_STAR) {         // ** opens Diagnostics
            long now = System.currentTimeMillis();
            if (now - lastStar < 1200) {
                lastStar = 0;
                app.showLog();
            } else {
                lastStar = now;
            }
        }
    }

    void tap(int x, int y, int w, int h) {
        onRail = false;
        if (x < railX) {                            // rail: the sources
            int k = (y - noteY + (pitch() + 6) / 2) / (pitch() + 6);
            setSection(RAIL[Math.max(0, Math.min(RAIL.length - 1, k))]);
            return;
        }
        for (int k = 0; k < tops.length; k++) {
            if (y >= tops[k] && y < bottoms[k]) {
                if (k == focus) select();
                else move(k);
                return;
            }
        }
    }

    void drag(int dy) {
        dragAcc += dy;
        int P = pitch();
        int n = entries.size();
        while (dragAcc <= -P / 2) {                 // finger up: next entry; touch stops at the ends
            if (focus >= n - 1) {
                dragAcc = 0;
                break;
            }
            dragAcc += P;
            move(focus + 1);
        }
        while (dragAcc >= P / 2) {
            if (focus <= 0) {
                dragAcc = 0;
                break;
            }
            dragAcc -= P;
            move(focus - 1);
        }
    }

    void shown() {
        dragAcc = 0;
    }

    void select() {
        if (onRail) {                                   // centre key on the rail: back to the menu
            onRail = false;
            return;
        }
        if (searchFocused() && query.length() > 0) {
            String q = query.toString().trim();
            query.setLength(0);
            tapKey = -1;
            if (q.length() > 0) app.searchFor(q);
            return;
        }
        if (focus < entries.size()) {
            Entry e = (Entry) entries.elementAt(focus);
            Item param = new Item();
            param.title = e.spotty == null ? "" : e.spotty;
            app.activate(null, new ListScreen.Row(e.label, null, e.glyph, e.action, param));
        }
    }
}
