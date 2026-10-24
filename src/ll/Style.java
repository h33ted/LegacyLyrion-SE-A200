package ll;

import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Look of Sony Ericsson's own 2009 apps on the A200 phones (Aino, Satio-era PlayNow, Walkman):
 * black background lit by a soft orange glow from the right, translucent dark panels with a thin
 * grey frame, grey text that turns orange inside a thin orange-red focus frame, and a black
 * soft-key bar with bold labels.
 */
final class Style {
    static final int BG = 0x000000;
    static final int TEXT = 0xffffff, TEXT_DIM = 0xb4b4b4, TEXT_FAINT = 0x7a7a7a;
    static final int FOCUS = 0xff7f2a, FOCUS_FRAME = 0xd9481c, FOCUS_SUB = 0xc8763c;
    static final int FRAME = 0x5c5c5c, LINE = 0x2a2a2a, ACCENT = 0xff7a1a;
    static final int ICON = 0x8c8c8c, ICON_FOCUS = 0xffffff;
    // still used by the Now Playing transport button
    static final int HI_TOP = 0xffa62b, HI_BOTTOM = 0xd35400;
    static final int BG_TOP = BG;   // colour used to punch holes into glyphs

    static final Font TITLE = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    static final Font ROW = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_MEDIUM);
    static final Font ROW_BOLD = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
    static final Font SMALL = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_SMALL);
    static final Font BIG = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);
    static final Font MENU = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_PLAIN, Font.SIZE_LARGE);
    static final Font MENU_BOLD = Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_LARGE);

    private Style() {}

    static int mix(int a, int b, int num, int den) {
        int r = ((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * num / den;
        int g = ((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * num / den;
        int bl = (a & 255) + ((b & 255) - (a & 255)) * num / den;
        return (r << 16) | (g << 8) | bl;
    }

    /** Vertical gradient, drawn in 2px bands to stay quick on 2009 hardware. */
    static void gradient(Graphics g, int x, int y, int w, int h, int top, int bottom) {
        for (int k = 0; k < h; k += 2) {
            g.setColor(mix(top, bottom, k, Math.max(1, h)));
            g.fillRect(x, y + k, w, Math.min(2, h - k));
        }
    }

    // ------------------------------------------------------------------ background glow

    private static Image glowA, glowB;      // portrait and landscape, so turning doesn't recompute

    /** Black with the orange glow coming in from the right edge; computed once per screen size. */
    static void background(Graphics g, int w, int h) {
        g.drawImage(glowFor(w, h), 0, 0, Graphics.TOP | Graphics.LEFT);
    }

    static synchronized Image glowFor(int w, int h) {
        if (glowA != null && glowA.getWidth() == w && glowA.getHeight() == h) return glowA;
        if (glowB != null && glowB.getWidth() == w && glowB.getHeight() == h) return glowB;
        Image img = makeGlow(w, h);
        glowB = glowA;
        glowA = img;
        return img;
    }

    /** Builds the backgrounds for both ways up ahead of time (called when rotation is switched on). */
    static void prewarm(int w, int h) {
        glowFor(h, w);
        glowFor(w, h);
    }

    private static Image makeGlow(int w, int h) {
        int[] px = new int[w * h];
        int cx = w + w / 6, cy = h * 2 / 5;      // centre just off the right edge
        int rx = Math.max(1, w * 19 / 20), ry = Math.max(1, h * 3 / 5);
        int cx2 = w + w / 12, cy2 = h / 2;        // a tighter, brighter core
        int rx2 = Math.max(1, w * 2 / 5), ry2 = Math.max(1, h / 4);
        for (int y = 0; y < h; y++) {
            int dy = (y - cy) * 1000 / ry, dy2 = (y - cy2) * 1000 / ry2;
            for (int x = 0; x < w; x++) {
                int dx = (x - cx) * 1000 / rx;
                int d = dx * dx + dy * dy;          // 0 .. 1 000 000 inside the ellipse
                int a = d >= 1000000 ? 0 : (1000000 - d) / 1000;      // 0..1000
                a = a * a / 1000;                                     // soft falloff
                int dx2 = (x - cx2) * 1000 / rx2;
                int d2 = dx2 * dx2 + dy2 * dy2;
                int b = d2 >= 1000000 ? 0 : (1000000 - d2) / 1000;
                b = b * b / 1000;
                int r = (a * 150 + b * 80) / 1000, gr = (a * 56 + b * 40) / 1000, bl = (a * 12 + b * 4) / 1000;
                px[y * w + x] = 0xff000000 | (Math.min(255, r) << 16) | (Math.min(255, gr) << 8) | Math.min(255, bl);
            }
        }
        return Image.createRGBImage(px, w, h, false);
    }

    // ------------------------------------------------------------------ panels and focus

    private static final java.util.Hashtable glass = new java.util.Hashtable();

    private static synchronized Image glassFor(String kind, int w, int h, int top, int bottom, int alpha) {
        String key = kind + w + "x" + h;
        Image p = (Image) glass.get(key);
        if (p == null) {
            if (glass.size() > 8) glass.clear();
            p = translucent(w, h, top, bottom, alpha);
            glass.put(key, p);
        }
        return p;
    }

    /** Translucent dark panel with a thin grey frame (lists, menus, messages). */
    static void panel(Graphics g, int x, int y, int w, int h) {
        if (w <= 4 || h <= 4) return;
        Image p = glassFor("p", w, h, 0x2e2e2e, 0x0c0c0c, 0xd8);
        g.drawImage(p, x, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(FRAME);
        g.drawRoundRect(x, y, w - 1, h - 1, 6, 6);
    }

    /** The focused entry of the home menu: lighter glass box with a grey frame. */
    static void focusBox(Graphics g, int x, int y, int w, int h) {
        if (w <= 4 || h <= 4) return;
        Image p = glassFor("b", w, h, 0x3c3c3c, 0x141414, 0xc8);
        g.drawImage(p, x, y, Graphics.TOP | Graphics.LEFT);
        g.setColor(0x9a9a9a);
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(0x4a4a4a);
        g.drawLine(x + 1, y + 1, x + w - 2, y + 1);
    }

    private static Image translucent(int w, int h, int top, int bottom, int alpha) {
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int c = (alpha << 24) | mix(top, bottom, Math.min(y, h / 2), Math.max(1, h / 2));
            for (int x = 0; x < w; x++) px[y * w + x] = c;
        }
        // round the corners a little
        int[] cut = {2, 1};
        for (int k = 0; k < cut.length; k++) {
            for (int j = 0; j < cut[k]; j++) {
                px[k * w + j] = 0;
                px[k * w + w - 1 - j] = 0;
                px[(h - 1 - k) * w + j] = 0;
                px[(h - 1 - k) * w + w - 1 - j] = 0;
            }
        }
        return Image.createRGBImage(px, w, h, true);
    }

    /** Thin orange-red frame around the focused row (the row text turns orange). */
    static void focusFrame(Graphics g, int x, int y, int w, int h) {
        g.setColor(FOCUS_FRAME);
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(0x5a1e08);
        g.drawRect(x + 1, y + 1, w - 3, h - 3);
    }

    /** Word-wraps text into lines no wider than w (long words are split). */
    static java.util.Vector wrap(String s, Font f, int w) {
        java.util.Vector lines = new java.util.Vector();
        if (s == null) return lines;
        int start = 0;
        while (start < s.length()) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? s.length() : nl;
            String para = s.substring(start, end);
            while (para.length() > 0) {
                int n = para.length();
                while (n > 1 && f.substringWidth(para, 0, n) > w) n--;
                if (n < para.length()) {
                    int sp = para.lastIndexOf(' ', n);
                    if (sp > 0) n = sp;
                }
                lines.addElement(para.substring(0, n).trim());
                para = para.substring(n).trim();
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return lines;
    }

    static String fit(String s, Font f, int w) {
        if (s == null) return "";
        if (f.stringWidth(s) <= w) return s;
        String dots = "...";
        int dw = f.stringWidth(dots);
        int n = s.length();
        while (n > 0 && f.substringWidth(s, 0, n) + dw > w) n--;
        return s.substring(0, n) + dots;
    }

    // ------------------------------------------------------------------ icons
    //
    // Icons are drawn as shapes at four times their size, then scaled down with each output pixel
    // averaging 16 samples: smooth, sharp edges on hardware that can't anti-alias. Each
    // (icon, size, colour) is built once and cached.

    static final int G_PLAY = 0, G_PAUSE = 1, G_NEXT = 2, G_PREV = 3, G_NOTE = 4, G_FOLDER = 5, G_PERSON = 6,
            G_DISC = 7, G_SEARCH = 8, G_BACK = 9, G_LIST = 10, G_GEAR = 11, G_RESUME = 12, G_GLOBE = 13,
            G_PEOPLE = 14, G_NEWS = 15, G_PLAYBOX = 16, G_HEART = 17, G_CLOUD = 18, G_SERVER = 19, G_SPEAKER = 20,
            G_CLOCK = 21, G_DOT = 22, G_RING = 23, G_STREAM = 24, G_TUBE = 25;

    private static final int SS = 4;                    // supersampling factor
    private static final java.util.Hashtable icons = new java.util.Hashtable();

    static void glyph(Graphics g, int kind, int cx, int cy, int s, int color) {
        Image img = icon(kind, s, color);
        if (img != null) g.drawImage(img, cx, cy, Graphics.VCENTER | Graphics.HCENTER);
    }

    static Image icon(int kind, int s, int color) {
        String key = kind + "/" + s + "/" + color;
        Image img = (Image) icons.get(key);
        if (img != null) return img;
        try {
            int n = s + 4, big = n * SS;
            Image canvas = Image.createImage(big, big);
            Graphics bg = canvas.getGraphics();
            bg.setColor(0x000000);
            bg.fillRect(0, 0, big, big);
            draw(bg, kind, big / 2, big / 2, s * SS, 0xffffff, 0x000000);
            int[] px = new int[big * big];
            canvas.getRGB(px, 0, big, 0, 0, big, big);
            int[] out = new int[n * n];
            int rgb = color & 0xffffff;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    int sum = 0;
                    for (int j = 0; j < SS; j++) {
                        int row = (y * SS + j) * big + x * SS;
                        for (int i = 0; i < SS; i++) sum += (px[row + i] >> 8) & 255;
                    }
                    out[y * n + x] = ((sum / (SS * SS)) << 24) | rgb;
                }
            }
            img = Image.createRGBImage(out, n, n, true);
            if (icons.size() > 120) icons.clear();
            icons.put(key, img);
            return img;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ring: a filled circle with its middle punched out, t thick. */
    private static void ring(Graphics g, int cx, int cy, int r, int t, int c, int bg) {
        g.setColor(c);
        g.fillArc(cx - r, cy - r, 2 * r, 2 * r, 0, 360);
        g.setColor(bg);
        g.fillArc(cx - r + t, cy - r + t, 2 * (r - t), 2 * (r - t), 0, 360);
        g.setColor(c);
    }

    private static void frame(Graphics g, int x, int y, int w, int h, int t, int c, int bg) {
        g.setColor(c);
        g.fillRect(x, y, w, h);
        g.setColor(bg);
        g.fillRect(x + t, y + t, w - 2 * t, h - 2 * t);
        g.setColor(c);
    }

    /** Draws icon 'kind' centred on (cx, cy), s across, in colour c; holes are painted in bg. */
    private static void draw(Graphics g, int kind, int cx, int cy, int s, int c, int bg) {
        g.setColor(c);
        int h = s / 2;
        int t = Math.max(1, s / 12);                    // stroke width, scales with the icon
        switch (kind) {
            case G_PLAY:
                g.fillTriangle(cx - h * 2 / 3, cy - h, cx - h * 2 / 3, cy + h, cx + h, cy);
                break;
            case G_PAUSE:
                g.fillRect(cx - h * 3 / 4, cy - h, h / 2, s);
                g.fillRect(cx + h / 4, cy - h, h / 2, s);
                break;
            case G_NEXT:
                g.fillTriangle(cx - h, cy - h * 3 / 4, cx - h, cy + h * 3 / 4, cx, cy);
                g.fillTriangle(cx - h / 4, cy - h * 3 / 4, cx - h / 4, cy + h * 3 / 4, cx + h * 3 / 4, cy);
                g.fillRect(cx + h * 3 / 4, cy - h * 3 / 4, t * 2, h * 3 / 2);
                break;
            case G_PREV:
                g.fillTriangle(cx + h, cy - h * 3 / 4, cx + h, cy + h * 3 / 4, cx, cy);
                g.fillTriangle(cx + h / 4, cy - h * 3 / 4, cx + h / 4, cy + h * 3 / 4, cx - h * 3 / 4, cy);
                g.fillRect(cx - h * 3 / 4 - t * 2, cy - h * 3 / 4, t * 2, h * 3 / 2);
                break;
            case G_NOTE:
                g.fillArc(cx - h * 3 / 4, cy + h / 4, h, h * 3 / 4, 0, 360);
                g.fillRect(cx + h / 4 - t, cy - h, t * 2, h * 5 / 4 + h / 8);
                g.fillTriangle(cx + h / 4, cy - h, cx + h * 3 / 4 + t, cy - h / 2, cx + h / 4, cy - h / 4);
                break;
            case G_FOLDER:
                g.fillRect(cx - h, cy - h / 2, s, h + h / 2);
                g.fillRect(cx - h, cy - h * 3 / 4, h, h / 3);
                break;
            case G_PERSON:
                g.fillArc(cx - h / 2, cy - h, h, h, 0, 360);
                g.fillArc(cx - h, cy + h / 8, s, h * 2, 0, 180);
                break;
            case G_DISC:
                g.fillArc(cx - h, cy - h, s, s, 0, 360);
                g.setColor(bg);
                g.fillArc(cx - h / 4, cy - h / 4, h / 2, h / 2, 0, 360);
                break;
            case G_SEARCH: {
                int r = h * 5 / 8, ox = cx - h / 4, oy = cy - h / 4;
                ring(g, ox, oy, r, t + t / 2, c, bg);
                int k = r * 7 / 10;                     // handle from the rim, out to the corner
                g.fillTriangle(ox + k - t, oy + k + t, ox + k + t, oy + k - t, cx + h, cy + h - t * 2);
                g.fillTriangle(ox + k - t, oy + k + t, cx + h - t * 2, cy + h, cx + h, cy + h - t * 2);
                break;
            }
            case G_BACK:
                g.fillTriangle(cx - h, cy, cx, cy - h, cx, cy + h);
                g.fillRect(cx, cy - h / 4, h, h / 2);
                break;
            case G_LIST:
                for (int k = -1; k <= 1; k++) g.fillRect(cx - h, cy + k * h * 2 / 3 - t, s, t * 2);
                break;
            case G_GEAR:
                for (int k = 0; k < 8; k++) g.fillArc(cx - h, cy - h, s, s, k * 45 - 11, 22);
                g.fillArc(cx - h * 3 / 4, cy - h * 3 / 4, h * 3 / 2, h * 3 / 2, 0, 360);
                g.setColor(bg);
                g.fillArc(cx - h / 3, cy - h / 3, h * 2 / 3, h * 2 / 3, 0, 360);
                break;
            case G_RESUME:
                g.fillArc(cx - h, cy - h, s, s, 0, 360);
                g.setColor(bg);
                g.fillTriangle(cx - h / 3, cy - h / 2, cx - h / 3, cy + h / 2, cx + h / 2, cy);
                break;
            case G_GLOBE:
                ring(g, cx, cy, h, t, c, bg);
                g.fillArc(cx - h / 2, cy - h, h, s, 0, 360);
                g.setColor(bg);
                g.fillArc(cx - h / 2 + t, cy - h + t, h - 2 * t, s - 2 * t, 0, 360);
                g.setColor(c);
                g.fillRect(cx - t / 2, cy - h, t, s);
                g.fillRect(cx - h, cy - t / 2, s, t);
                g.fillRect(cx - h * 4 / 5, cy - h / 2 - t / 2, h * 8 / 5, t);
                g.fillRect(cx - h * 4 / 5, cy + h / 2 - t / 2, h * 8 / 5, t);
                break;
            case G_PEOPLE:
                g.fillArc(cx - h / 2, cy - h, h, h, 0, 360);
                g.fillArc(cx - h, cy + h / 8, s, h * 2, 0, 180);
                break;
            case G_NEWS:
                frame(g, cx - h, cy - h, s, s, t, c, bg);
                g.fillRect(cx - h + t * 3, cy - h + t * 3, h - t * 2, h - t * 2);
                for (int k = 0; k < 3; k++) g.fillRect(cx + t * 2, cy - h + t * 3 + k * t * 3, h - t * 5, t + t / 2);
                for (int k = 0; k < 2; k++) g.fillRect(cx - h + t * 3, cy + t * 2 + k * t * 3, s - t * 6, t + t / 2);
                break;
            case G_PLAYBOX:
                frame(g, cx - h, cy - h * 3 / 4, s, h * 3 / 2, t, c, bg);
                g.fillTriangle(cx - h / 3, cy - h / 3, cx - h / 3, cy + h / 3, cx + h / 3, cy);
                break;
            case G_HEART:
                g.fillArc(cx - h, cy - h * 3 / 4, h + t, h + t, 0, 360);
                g.fillArc(cx - t, cy - h * 3 / 4, h + t, h + t, 0, 360);
                g.fillTriangle(cx - h + t / 2, cy - h / 8 + t, cx + h - t / 2, cy - h / 8 + t, cx, cy + h);
                break;
            case G_CLOUD:                                       // streaming: a cloud with a note cut out
                g.fillArc(cx - h, cy - h / 6, h, h * 5 / 6, 0, 360);
                g.fillArc(cx - h / 2, cy - h * 2 / 3, h + h / 4, h + h / 4, 0, 360);
                g.fillArc(cx + h / 6, cy - h / 4, h * 5 / 6, h * 3 / 4, 0, 360);
                g.fillRect(cx - h / 2, cy + h / 6, h + h / 2, h / 2 + 1);
                draw(g, G_NOTE, cx, cy + h / 8, h, bg, c);
                break;
            case G_SERVER:                                      // local library: two stacked drive units
                for (int k = 0; k < 2; k++) {
                    int y0 = cy - h + k * (h + t);
                    g.setColor(c);
                    g.fillRoundRect(cx - h, y0, s, h - t, h / 3, h / 3);
                    g.setColor(bg);
                    g.fillRect(cx - h + t * 3, y0 + (h - t) / 2 - t / 2, h, t);
                    g.fillArc(cx + h / 2 - t, y0 + (h - t) / 2 - t, t * 2, t * 2, 0, 360);
                }
                g.setColor(c);
                break;
            case G_SPEAKER: {                                   // players: speaker and two waves
                for (int k = 2; k >= 1; k--) {
                    int r = h * k / 2 + h / 6;
                    g.setColor(c);
                    g.fillArc(cx - r, cy - r, 2 * r, 2 * r, -45, 90);
                    g.setColor(bg);
                    g.fillArc(cx - r + t + t / 2, cy - r + t + t / 2, 2 * (r - t - t / 2), 2 * (r - t - t / 2), -50, 100);
                }
                g.setColor(bg);
                g.fillRect(cx - h, cy - h, h + t, s);
                g.setColor(c);
                g.fillRect(cx - h, cy - h / 3, h / 2, h * 2 / 3);
                g.fillTriangle(cx - h / 2, cy - h / 3, cx, cy - h, cx, cy + h);
                g.fillTriangle(cx - h / 2, cy + h / 3, cx - h / 2, cy - h / 3, cx, cy + h);
                break;
            }
            case G_STREAM: {                                    // Spotify section: two arcs over a dome
                int ccx = cx, ccy = cy + 2 * h;                 // arcs share a centre below the icon
                int[] outer = {h * 59 / 20, h * 46 / 20};
                int band = h * 7 / 20;
                for (int k = 0; k < 2; k++) {
                    int r = outer[k], ri = r - band;
                    int span = k == 0 ? 19 : 22;
                    g.setColor(c);
                    g.fillArc(ccx - r, ccy - r, 2 * r, 2 * r, 90 - span, 2 * span);
                    g.setColor(bg);
                    g.fillArc(ccx - ri, ccy - ri, 2 * ri, 2 * ri, 90 - span - 2, 2 * span + 4);
                }
                g.setColor(c);
                int d = h * 3 / 4;
                g.fillArc(cx - d, cy + h - d, 2 * d, 2 * d, 0, 180);
                break;
            }
            case G_TUBE:                                        // YouTube Music section: play button in a ring
                ring(g, cx, cy, h, t + t / 2, c, bg);
                g.fillTriangle(cx - h / 3, cy - h * 2 / 5, cx - h / 3, cy + h * 2 / 5, cx + h * 2 / 5 + t / 2, cy);
                break;
            case G_DOT:
                g.fillArc(cx - h, cy - h, s, s, 0, 360);
                break;
            case G_RING:
                ring(g, cx, cy, h, Math.max(SS, s / 28), c, bg);
                break;
            case G_CLOCK:
                ring(g, cx, cy, h, t + t / 2, c, bg);
                g.fillRect(cx - t / 2 - t / 4, cy - h + t * 4, t + t / 2, h - t * 4);
                g.fillRect(cx - t / 2, cy - t / 2 - t / 4, h - t * 4, t + t / 2);
                break;
        }
    }
}
