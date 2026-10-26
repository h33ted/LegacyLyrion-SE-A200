package ll;

import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.game.Sprite;

/**
 * The one full-screen Canvas. Draws the Sony Ericsson chrome around the current Screen:
 * gradient title strip, mini "now playing" strip, soft-key bar (More / Select / Back),
 * and the "More" pop-up menu. Routes both keypad and touch input.
 */
final class Shell extends Canvas {
    static Shell instance;
    private static final javax.microedition.lcdui.Font SOFT_SMALL = javax.microedition.lcdui.Font.getFont(
            javax.microedition.lcdui.Font.FACE_PROPORTIONAL, javax.microedition.lcdui.Font.STYLE_BOLD,
            javax.microedition.lcdui.Font.SIZE_SMALL);
    final LegacyLyrion app;
    private final Vector stack = new Vector();

    // pop-up ("More") menu
    private String[] menu;
    private int menuFocus;
    private int menuX, menuY, menuW, menuRowH, menuTop;

    // touch tracking
    private int pressX, pressY, lastY;
    private boolean dragging;

    // toast
    private String toast;
    private long toastUntil;

    Shell(LegacyLyrion app) {
        this.app = app;
        instance = this;
        setFullScreenMode(true);
    }

    // ------------------------------------------------------------------ rotation
    //
    // The phone doesn't rotate Java apps itself, so the Shell does: in landscape it draws the
    // whole UI into a sideways image and turns it with drawRegion, and maps touch and the
    // D-pad back. The accelerometer is read by Tilt (JSR-256).

    static final int ROT_AUTO = 0, ROT_REVERSED = 1, ROT_OFF = 2;
    private volatile int rotMode = ROT_OFF;
    private volatile int rot;              // 0 upright, 1 content turned 90 deg clockwise, 3 anticlockwise
    private int pending = -1;
    private long pendingSince;
    private Image frame;
    private boolean tiltRunning;
    private static final int ROTATE_DELAY_MS = 300;
    private volatile int phys;             // which way the phone is held, even while the UI stays upright
    private volatile boolean dockWanted;   // docked mode needs the accelerometer even with rotation off
    private double gX, gY;                 // last gravity reading, to notice movement
    private volatile boolean flat;         // lying on its back: not "sideways", whatever it was before
    private volatile long movedAt = System.currentTimeMillis();

    void setRotationMode(int mode) {
        rotMode = mode;
        if (mode == ROT_OFF && !app.docked()) setRot(0);
        if (mode != ROT_OFF || dockWanted) startTilt();
    }

    /** Docked mode (Aino) watches which way the phone lies even when rotation is off. */
    void setDockWanted(boolean on) {
        dockWanted = on;
        if (on) startTilt();
    }

    private void startTilt() {
        if (tiltRunning) return;
        try {
            Runnable r = (Runnable) Class.forName("ll.Tilt").newInstance();
            tiltRunning = true;
            new Thread(r).start();
            new Thread(new Runnable() {                 // build the sideways background now, not mid-turn
                public void run() {
                    try { Style.prewarm(getWidth(), getHeight()); } catch (Throwable ignored) {}
                }
            }).start();
        } catch (Throwable t) {
            app.log("Rotation: this phone has no sensor API (" + t + ")");
        }
    }

    boolean tiltWanted() {
        boolean want = rotMode != ROT_OFF || dockWanted;
        if (!want) tiltRunning = false;
        return want;
    }

    /** Called by Tilt about ten times a second with the gravity reading. */
    void tilt(double x, double y, double z) {
        double ax = Math.abs(x), ay = Math.abs(y);
        flat = Math.abs(z) > ax + ay;
        if (Math.abs(x - gX) + Math.abs(y - gY) > (ax + ay) * 0.06 + 0.01) movedAt = System.currentTimeMillis();
        gX = x;
        gY = y;
        int o;
        if (ax > ay * 2) o = (x > 0) == (rotMode != ROT_REVERSED) ? 1 : 3;
        else if (ay > ax * 2) o = 0;
        else return;                                  // flat or in between: keep what we have
        long now = System.currentTimeMillis();
        if (o != pending) {
            pending = o;
            pendingSince = now;
            return;
        }
        if (now - pendingSince < ROTATE_DELAY_MS) return;
        phys = o;
        if (o != rot && (rotMode != ROT_OFF || app.docked())) setRot(o);
    }

    private void setRot(int r) {
        if (r != 0 && getWidth() > getHeight()) r = 0;  // already landscape (firmware rotated it)
        if (r == rot) return;
        rot = r;
        frame = null;
        if (chooser == null) menu = null;       // a prompt stays up through a turn
        dragging = false;
        repaint();
    }

    /** Width and height of the UI as drawn (swapped while turned). */
    int lw() {
        return rot == 0 ? getWidth() : getHeight();
    }

    int lh() {
        return rot == 0 ? getHeight() : getWidth();
    }

    /** How long the phone has lain still, from the accelerometer (docked mode). */
    long stillFor() {
        return System.currentTimeMillis() - movedAt;
    }

    /** True while the phone stands sideways (or the firmware itself runs the app in landscape). */
    boolean sideways() {
        return (phys != 0 && !flat) || getWidth() > getHeight();
    }

    /** Docked mode turns the UI with the phone even when Settings says "Always upright". */
    void dockRotation(boolean on) {
        if (on) {
            if (phys != rot) setRot(phys);
        } else if (rotMode == ROT_OFF) {
            setRot(0);
        }
    }

    private int ux(int x, int y) {
        return rot == 1 ? y : rot == 3 ? getHeight() - 1 - y : x;
    }

    private int uy(int x, int y) {
        return rot == 1 ? getWidth() - 1 - x : rot == 3 ? x : y;
    }

    /** Physical D-pad direction to the direction on the turned UI. */
    private int turn(int g) {
        if (rot == 1) {
            if (g == UP) return LEFT;
            if (g == DOWN) return RIGHT;
            if (g == LEFT) return DOWN;
            if (g == RIGHT) return UP;
        } else if (rot == 3) {
            if (g == UP) return RIGHT;
            if (g == DOWN) return LEFT;
            if (g == LEFT) return UP;
            if (g == RIGHT) return DOWN;
        }
        return g;
    }

    private int turnKey(int keyCode) {
        if (rot == 0) return keyCode;
        int g = keyCode == KEY_NUM2 ? UP : keyCode == KEY_NUM8 ? DOWN : keyCode == KEY_NUM4 ? LEFT
                : keyCode == KEY_NUM6 ? RIGHT : 0;
        if (g == 0) return keyCode;
        g = turn(g);
        return g == UP ? KEY_NUM2 : g == DOWN ? KEY_NUM8 : g == LEFT ? KEY_NUM4 : KEY_NUM6;
    }

    protected void sizeChanged(int w, int h) {
        if (rot != 0 && w > h) setRot(0);
        frame = null;
        repaint();
    }

    // ------------------------------------------------------------------ navigation

    Screen top() {
        return stack.isEmpty() ? null : (Screen) stack.lastElement();
    }

    void push(Screen s) {
        Screen t = top();
        if (t != null) t.hidden();
        stack.addElement(s);
        dismissMenu();
        s.shown();
        repaint();
    }

    void back() {
        dismissMenu();
        if (stack.size() <= 1) {
            app.exit();
            return;
        }
        Screen t = top();
        t.hidden();
        stack.removeElementAt(stack.size() - 1);
        top().shown();
        repaint();
    }

    boolean holds(Screen s) {
        return stack.contains(s);
    }

    /** Takes a screen out of the stack wherever it is (docked mode ending under another screen). */
    void remove(Screen s) {
        if (!stack.contains(s) || stack.size() <= 1) return;
        boolean wasTop = top() == s;
        if (wasTop) {
            dismissMenu();
            s.hidden();
        }
        stack.removeElement(s);
        if (wasTop) top().shown();
        repaint();
    }

    // ------------------------------------------------------------------ prompts

    /** Answer to prompt(): the chosen row, or -1 when dismissed. */
    interface Chooser {
        void chose(int k);
    }

    private Chooser chooser;
    private String menuTitle = "Options";

    /** A question drawn like the More menu, e.g. "Docked?". */
    void prompt(String title, String[] items, Chooser c) {
        dismissMenu();
        menu = items;
        menuFocus = 0;
        menuTitle = title;
        chooser = c;
        repaint();
    }

    /** Closes an open prompt without answering it. */
    void closePrompt() {
        if (chooser == null) return;
        chooser = null;
        menu = null;
        menuTitle = "Options";
        repaint();
    }

    private void dismissMenu() {
        Chooser c = chooser;
        chooser = null;
        menuTitle = "Options";
        menu = null;
        if (c != null) c.chose(-1);
    }

    void toast(String msg) {
        toast = msg;
        // longer messages stay longer: 2.5 s plus about 1 s per 25 characters
        toastUntil = System.currentTimeMillis() + 2500 + (msg == null ? 0 : msg.length() * 40);
        repaint();
        final long until = toastUntil;
        new Thread(new Runnable() {          // one timer per toast: redraw once it has expired
            public void run() {
                try { Thread.sleep(Math.max(50, until - System.currentTimeMillis() + 50)); } catch (InterruptedException e) {}
                repaint();
            }
        }).start();
    }

    // ------------------------------------------------------------------ layout

    /** No title strip: like Sony Ericsson's own apps, each screen draws its own heading. */
    private int headerH() {
        return 0;
    }

    private int softH() {
        Screen t = top();
        if (t != null && menu == null && t.bare()) return 0;
        return Style.BIG.getHeight() + 8;
    }

    private boolean showMini() {
        Screen t = top();
        return !(t instanceof NowPlayingScreen) && !(t instanceof HomeScreen) && app.npTitle != null && !(t != null && t.bare());
    }

    private int miniH() {
        return showMini() ? Style.SMALL.getHeight() + 8 : 0;
    }

    // ------------------------------------------------------------------ painting

    protected void paint(Graphics g) {
        try {
            int r = rot;
            if (r == 0) {
                draw(g);
                return;
            }
            int w = getWidth(), h = getHeight();
            Image f = frame;
            if (f == null || f.getWidth() != h || f.getHeight() != w) {
                frame = null;
                f = frame = Image.createImage(h, w);
            }
            draw(f.getGraphics());
            g.setClip(0, 0, w, h);
            g.drawRegion(f, 0, 0, h, w, r == 1 ? Sprite.TRANS_ROT90 : Sprite.TRANS_ROT270, 0, 0, Graphics.TOP | Graphics.LEFT);
        } catch (Throwable t) {
            app.fault("drawing " + (top() == null ? "" : top().title), t);
        }
    }

    private void draw(Graphics g) {
        int w = lw(), h = lh();
        Screen s = top();
        g.setClip(0, 0, w, h);
        Style.background(g, w, h);
        if (s == null) return;

        int sh = softH(), mh = miniH();
        int contentH = h - sh - mh;

        // content
        g.setClip(0, 0, w, contentH);
        s.paint(g, w, contentH);
        g.setClip(0, 0, w, h);

        // mini now-playing line
        int my = h - sh - mh;
        if (mh > 0) {
            g.setColor(0x000000);
            g.fillRect(0, my, w, mh);
            g.setColor(Style.LINE);
            g.drawLine(0, my, w, my);
            Style.glyph(g, Style.G_NOTE, 14, my + mh / 2, 11, Style.ACCENT);
            g.setFont(Style.SMALL);
            g.setColor(Style.TEXT_DIM);
            String t = app.npArtist != null && app.npArtist.length() > 0 ? app.npTitle + " - " + app.npArtist : app.npTitle;
            g.drawString(Style.fit(t, Style.SMALL, w - 34), 28, my + 4, Graphics.TOP | Graphics.LEFT);
        }

        // soft-key bar: black, bold labels, the centre one larger (none on a bare screen)
        int sy = h - sh;
        if (sh > 0) softKeys(g, s, w, sy, sh);

        // pop-up menu
        if (menu != null) paintMenu(g, w, sy);
        toast(g, w, sy, mh);
    }

    private void softKeys(Graphics g, Screen s, int w, int sy, int sh) {
        g.setColor(0x000000);
        g.fillRect(0, sy, w, sh);
        g.setColor(0x262626);
        g.drawLine(0, sy, w, sy);
        g.setColor(s.ink());
        String l, c, r;
        if (menu != null) {
            l = null;
            c = "Select";
            r = "Back";
        } else {
            l = s.leftDirect() || s.options() != null ? s.leftLabel : null;
            c = s.centreLabel;
            r = stack.size() > 1 ? s.rightLabel : "Exit";
        }
        // side labels get the space left of / right of the centre one; a long one ("Minimise")
        // drops to the small bold font, and is shortened only if even that doesn't fit
        int room = (w - (c == null ? 0 : Style.BIG.stringWidth(c))) / 2 - 12;
        javax.microedition.lcdui.Font side = Style.ROW_BOLD;
        if ((l != null && side.stringWidth(l) > room) || (r != null && side.stringWidth(r) > room)) side = SOFT_SMALL;
        g.setFont(side);
        int ty = sy + (sh - side.getHeight()) / 2;
        if (l != null) g.drawString(Style.fit(l, side, room), 6, ty, Graphics.TOP | Graphics.LEFT);
        if (r != null) g.drawString(Style.fit(r, side, room), w - 6, ty, Graphics.TOP | Graphics.RIGHT);
        if (c != null) {
            g.setFont(Style.BIG);
            g.drawString(c, w / 2, sy + (sh - Style.BIG.getHeight()) / 2, Graphics.TOP | Graphics.HCENTER);
        }
    }

    /** Message: full width, wrapped, so whole error messages are readable. */
    private void toast(Graphics g, int w, int sy, int mh) {
        if (toast != null && System.currentTimeMillis() < toastUntil) {
            g.setFont(Style.SMALL);
            java.util.Vector lines = Style.wrap(toast, Style.SMALL, w - 28);
            int lh = Style.SMALL.getHeight();
            int th = lines.size() * lh + 14;
            int tyy = Math.max(4, sy - mh - th - 6);
            Style.panel(g, 6, tyy, w - 12, th);
            g.setColor(Style.TEXT);
            for (int k = 0; k < lines.size(); k++) {
                g.drawString((String) lines.elementAt(k), 14, tyy + 7 + k * lh, Graphics.TOP | Graphics.LEFT);
            }
        }
    }

    /** The "More" menu: a panel headed "Options", entries in grey, the focused one framed in orange. */
    private void paintMenu(Graphics g, int w, int bottom) {
        menuRowH = Style.ROW.getHeight() + 10;
        int head = Style.TITLE.getHeight() + 10;
        menuW = w - 16;
        int mh = head + menuRowH * menu.length + 8;
        menuX = 8;
        menuY = Math.max(4, bottom - mh - 6);
        menuTop = menuY + head;
        g.setColor(0x101010);                       // solid behind the glass, so the list doesn't show through
        g.fillRoundRect(menuX, menuY, menuW, mh, 6, 6);
        Style.panel(g, menuX, menuY, menuW, mh);
        g.setFont(Style.TITLE);
        g.setColor(Style.TEXT);
        g.drawString(menuTitle, menuX + 10, menuY + 6, Graphics.TOP | Graphics.LEFT);
        g.setFont(Style.ROW);
        for (int k = 0; k < menu.length; k++) {
            int y = menuTop + k * menuRowH;
            boolean hi = k == menuFocus;
            if (hi) Style.focusFrame(g, menuX + 6, y, menuW - 12, menuRowH);
            g.setColor(hi ? Style.FOCUS : Style.TEXT_DIM);
            g.drawString(Style.fit(menu[k], Style.ROW, menuW - 28), menuX + 12, y + 5, Graphics.TOP | Graphics.LEFT);
        }
    }

    // ------------------------------------------------------------------ keypad

    private static final int SOFT_LEFT_SE = -6, SOFT_RIGHT_SE = -7, CLEAR_SE = -8, SOFT_LEFT_ALT = -21, SOFT_RIGHT_ALT = -22;

    private int game(int keyCode) {
        try {
            return getGameAction(keyCode);
        } catch (Exception e) {
            return 0; // soft keys throw on some firmware
        }
    }

    protected void keyPressed(int keyCode) {
        try { handleKey(keyCode, false); } catch (Throwable t) { app.fault("key " + keyCode, t); }
    }

    protected void keyRepeated(int keyCode) {
        try { handleKey(keyCode, true); } catch (Throwable t) { app.fault("key " + keyCode, t); }
    }

    private void handleKey(int keyCode, boolean repeat) {
        Screen s = top();
        if (s == null) return;
        boolean left = keyCode == SOFT_LEFT_SE || keyCode == SOFT_LEFT_ALT;
        boolean right = keyCode == SOFT_RIGHT_SE || keyCode == SOFT_RIGHT_ALT || keyCode == CLEAR_SE;
        if ((left || right) && menu == null && s.bare()) {   // soft keys hidden: the first press just shows them
            s.key(0, 0);
            repaint();
            return;
        }
        if (menu == null) {                             // typing into a field goes first
            if (keyCode == CLEAR_SE && s.backspace()) { repaint(); return; }
            if (!left && !right && s.typed(keyCode, repeat)) { repaint(); return; }
        }
        int g = left || right ? 0 : turn(game(keyCode));
        keyCode = turnKey(keyCode);
        boolean fire = g == FIRE || keyCode == KEY_NUM5;

        if (menu != null) {
            if (repeat && !(g == UP || g == DOWN)) return;
            if (g == UP || keyCode == KEY_NUM2) menuFocus = (menuFocus - 1 + menu.length) % menu.length;
            else if (g == DOWN || keyCode == KEY_NUM8) menuFocus = (menuFocus + 1) % menu.length;
            else if (fire || left) chooseMenu(menuFocus);
            else if (right) dismissMenu();
            repaint();
            return;
        }
        if (repeat && !(g == UP || g == DOWN || g == LEFT || g == RIGHT)) return;
        if (left) leftKey(s);
        else if (right) back();
        else if (fire) s.select();
        else s.key(g, keyCode);
        repaint();
    }

    private void leftKey(Screen s) {
        if (s.leftDirect()) s.left();
        else openMenu(s);
    }

    private void openMenu(Screen s) {
        String[] o = s.options();
        if (o == null || o.length == 0) return;
        chooser = null;
        menuTitle = "Options";
        menu = o;
        menuFocus = 0;
    }

    private void chooseMenu(int k) {
        Screen s = top();
        Chooser c = chooser;
        chooser = null;
        menuTitle = "Options";
        menu = null;
        repaint();
        if (c != null) c.chose(k);
        else if (s != null) s.option(k);
    }

    // ------------------------------------------------------------------ touch

    protected void pointerPressed(int px, int py) {
        int x = ux(px, py), y = uy(px, py);
        pressX = x;
        pressY = y;
        lastY = y;
        dragging = false;
    }

    protected void pointerDragged(int x, int y) {
        try { dragTo(ux(x, y), uy(x, y)); } catch (Throwable t) { app.fault("drag", t); }
    }

    private void dragTo(int x, int y) {
        if (!dragging && (Math.abs(y - pressY) > 8 || Math.abs(x - pressX) > 8)) dragging = true;
        if (dragging && menu == null) {
            Screen s = top();
            int hh = headerH();
            if (s != null && pressY > hh && pressY < lh() - softH() - miniH()) s.drag(y - lastY);
            lastY = y;
            repaint();
        }
    }

    protected void pointerReleased(int x, int y) {
        try { release(ux(x, y), uy(x, y)); } catch (Throwable t) { app.fault("tap", t); }
    }

    private void release(int x, int y) {
        if (dragging) {
            dragging = false;
            return;
        }
        Screen s = top();
        if (s == null) return;
        int w = lw(), h = lh(), hh = headerH(), sh = softH(), mh = miniH();

        if (menu != null) {
            if (x >= menuX && x <= menuX + menuW && y >= menuY && y < h - sh) {
                int k = (y - menuTop) / Math.max(1, menuRowH);
                if (k >= 0 && k < menu.length) {
                    menuFocus = k;
                    chooseMenu(k);
                    return;
                }
            } else if (y >= h - sh && x > w * 2 / 3) {
                dismissMenu();
            } else if (y >= h - sh && x > w / 3) {
                chooseMenu(menuFocus);
                return;
            } else {
                dismissMenu();
            }
            repaint();
            return;
        }

        if (y >= h - sh) {                       // soft keys, by thirds
            if (x < w / 3) leftKey(s);
            else if (x < w * 2 / 3) s.select();
            else back();
        } else if (mh > 0 && y >= h - sh - mh) {        // mini now-playing strip
            app.openNowPlaying();
        } else {
            s.tap(x, y - hh, w, h - hh - sh - mh);
        }
        repaint();
    }
}
