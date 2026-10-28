package ll;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextField;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * LegacyLyrion as the phone's standby wallpaper. Sony Ericsson phones offer MIDlets with
 * "SEMC-StandbyApplication: Y" under Settings > Display > Wallpaper > Application. This is a
 * separate, light suite: it reads the server and the controlled player from LegacyLyrion's own
 * settings (a record store LegacyLyrion shares), and shows the current artwork, centred over a
 * blurred, enlarged copy of itself. It polls Lyrion only while the standby screen is visible, and is
 * passive, except that # pressed twice opens its settings (also when started from the Applications
 * menu): what to show when nothing has played for a while, the glow, a picture of your choice, or
 * nothing at all (it quits, handing the screen back to the phone's own wallpaper).
 */
public final class Standby extends MIDlet implements Runnable, CommandListener {
    static final int IDLE_GLOW = 0, IDLE_PICTURE = 1, IDLE_HANDBACK = 2;
    private static final String SETUP = "Open LegacyLyrion once to set it up";
    private static final int MARGIN = 34;              // the phone's status bar and soft keys

    private final Lms lms = new Lms();
    private String player = "";
    private Face face;
    private volatile boolean running, kick;

    private String track = "", sub = "", note = "";
    private boolean playing;
    private int time, dur;
    private Image cover;
    private String artKey = "";

    private Display display;
    private int idleMode = IDLE_GLOW, idleMinutes = 1;
    private String picture = "", pictureLoaded = "";
    private Image idleImage;
    private long idleSince = System.currentTimeMillis();
    private FileAccess files;
    private boolean filesTried;

    private Form opts;
    private ChoiceGroup oMode;
    private TextField oWait;
    private StringItem oPic;
    private List browser;
    private String browseUrl;
    private Vector browseItems;
    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cBack = new Command("Back", Command.BACK, 2);
    private final Command cPick = new Command("Choose picture", Command.SCREEN, 2);

    protected void startApp() {
        if (face == null) {
            display = Display.getDisplay(this);
            face = new Face();
            loadOwn();
            load();
            running = true;
            new Thread(this).start();
        }
        if (display.getCurrent() == null) display.setCurrent(face);
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {
        running = false;
    }

    /** Server and player from LegacyLyrion's settings (MIDlet-Vendor h33ted, MIDlet-Name LegacyLyrion). */
    private void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("legacylyrion", "h33ted", "LegacyLyrion");
            byte[] b = rs.getNumRecords() > 0 ? rs.getRecord(1) : null;
            rs.closeRecordStore();
            if (b != null) {
                String[] f = split(new String(b, "UTF-8"));
                if (f.length > 0) lms.host = f[0];
                if (f.length > 1 && f[1].length() > 0) lms.port = f[1];
                if (f.length > 2) player = f[2];
            }
        } catch (Throwable ignored) {}
        note = lms.host.length() == 0 || player.length() == 0 ? SETUP : "";
    }

    public void run() {
        while (running) {
            if (face.isShown()) {
                if (player.length() == 0) load();
                if (player.length() > 0) poll();
                long now = System.currentTimeMillis();
                if (playing) idleSince = 0;
                else if (idleSince == 0) idleSince = now;
                if (idle() && idleMode == IDLE_HANDBACK) {      // give the screen back to the phone
                    running = false;
                    notifyDestroyed();
                    return;
                }
                if (idleMode == IDLE_PICTURE && !picture.equals(pictureLoaded)) loadPicture();
                face.repaint();
            }
            // while shown: every 8 s when playing (the next track), 30 s when not; hidden: just wait
            long wait = !face.isShown() ? 3000 : playing ? 8000 : 30000;
            for (long t = 0; t < wait && running && !kick; t += 250) {
                try { Thread.sleep(250); } catch (InterruptedException e) {}
            }
            kick = false;
        }
    }

    private void poll() {
        try {
            Hashtable r = lms.call(player, new String[]{"status", "-", "1", "tags:alcK"});
            playing = "play".equals(Json.str(r, "mode"));
            time = Json.toInt(Json.str(r, "time"), 0);
            dur = Json.toInt(Json.str(r, "duration"), 0);
            Vector loop = Json.arr(r, "playlist_loop");
            Hashtable t = loop != null && loop.size() > 0 && loop.elementAt(0) instanceof Hashtable
                    ? (Hashtable) loop.elementAt(0) : null;
            if (t == null) {
                track = "Nothing playing";
                sub = "";
                cover = null;
                artKey = "";
                return;
            }
            track = str(t, "title");
            String artist = str(t, "artist"), album = str(t, "album");
            sub = album.length() > 0 && artist.length() > 0 ? artist + " - " + album : artist + album;
            String icon = Json.str(t, "artwork_url");
            if (icon == null && Json.str(t, "coverid") != null) icon = "/music/" + Json.str(t, "coverid") + "/cover.png";
            if (icon == null) icon = "/music/current/cover.png?player=" + Lms.urlEncode(player);
            if (!icon.equals(artKey)) {
                artKey = icon;
                int s = face.coverSize();
                String url;
                if (icon.startsWith("/music/")) {
                    int q = icon.indexOf('?');
                    String path = q >= 0 ? icon.substring(0, q) : icon;
                    url = path.substring(0, path.lastIndexOf('/') + 1) + "cover_" + s + "x" + s + "_p.png"
                            + (q >= 0 ? icon.substring(q) : "");
                } else {
                    url = lms.art(icon, s);
                }
                cover = lms.image(url);
            }
            note = "";
        } catch (Exception e) {
            note = "Can't reach Lyrion";
        }
    }

    /** Nothing has played for the wait set in the settings. */
    private boolean idle() {
        return idleSince != 0 && System.currentTimeMillis() - idleSince >= idleMinutes * 60000L;
    }

    // ------------------------------------------------------------------ own settings (RMS "standby")

    private void loadOwn() {
        try {
            RecordStore rs = RecordStore.openRecordStore("standby", true);
            byte[] b = rs.getNumRecords() > 0 ? rs.getRecord(1) : null;
            rs.closeRecordStore();
            if (b == null) return;
            String[] f = split(new String(b, "UTF-8"));
            if (f.length > 0 && f[0].length() == 1) idleMode = f[0].charAt(0) - '0';
            if (f.length > 1) idleMinutes = minutes(f[1], 1);
            if (f.length > 2) picture = f[2];
        } catch (Throwable ignored) {}
    }

    private void saveOwn() {
        try {
            byte[] b = (idleMode + "\n" + idleMinutes + "\n" + picture).getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("standby", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable ignored) {}
    }

    private static int minutes(String s, int dflt) {
        try {
            return Math.max(0, Math.min(120, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return dflt;
        }
    }

    // ------------------------------------------------------------------ settings screen (# twice)

    void showOptions() {
        opts = new Form("LegacyLyrion Standby");
        oMode = new ChoiceGroup("When nothing plays", Choice.EXCLUSIVE,
                new String[]{"Orange glow", "My picture", "Phone's own wallpaper (experiment)"}, null);
        oMode.setSelectedIndex(idleMode >= 0 && idleMode <= 2 ? idleMode : IDLE_GLOW, true);
        oWait = new TextField("After (minutes)", String.valueOf(idleMinutes), 3, TextField.NUMERIC);
        oPic = new StringItem("Picture", picture.length() == 0 ? "(none chosen)" : picture.substring(picture.lastIndexOf('/') + 1));
        opts.append(oMode);
        opts.append(oWait);
        opts.append(oPic);
        opts.append(new StringItem(null, "\"Phone's own wallpaper\" closes this app when nothing plays, so the phone "
                + "can show its theme; it may only come back once standby is shown again."));
        opts.addCommand(cSave);
        opts.addCommand(cPick);
        opts.addCommand(cBack);
        opts.setCommandListener(this);
        display.setCurrent(opts);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == opts) {
            if (c == cSave) {
                idleMode = oMode.getSelectedIndex();
                idleMinutes = minutes(oWait.getString(), 1);
                saveOwn();
                pictureLoaded = "";
                kick = true;
                display.setCurrent(face);
            } else if (c == cPick) {
                browse(null);
            } else {
                display.setCurrent(face);
            }
        } else if (d == browser) {
            if (c == cBack) {
                display.setCurrent(opts);
                return;
            }
            int i = browser.getSelectedIndex();
            if (i < 0 || browseItems == null || i >= browseItems.size()) return;
            String target = (String) browseItems.elementAt(i);
            if (target == null) {
                browse(null);
            } else if (target.endsWith("/")) {
                browse(target);
            } else {                                        // a picture
                picture = target;
                oPic.setText(target.substring(target.lastIndexOf('/') + 1));
                display.setCurrent(opts);
            }
        }
    }

    /** Lists a folder (or the memory roots when url is null) in a List; file access runs off the UI thread. */
    private void browse(final String url) {
        final FileAccess fa = files();
        if (fa == null) {
            oPic.setText("This phone has no file access for Java");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                Vector targets = new Vector();
                List l = new List(url == null ? "Choose a picture" : url.substring(8), Choice.IMPLICIT);
                try {
                    if (url == null) {
                        Vector r = fa.roots();
                        for (int i = 0; i < r.size(); i++) {
                            String u = (String) r.elementAt(i);
                            l.append(u.substring(8), null);
                            targets.addElement(u);
                        }
                    } else {
                        String up = url.substring(0, url.lastIndexOf('/', url.length() - 2) + 1);
                        l.append("..", null);
                        targets.addElement(up.length() <= 8 ? null : up);
                        Vector v = fa.list(url);
                        for (int i = 0; i < v.size(); i++) {
                            String n = (String) v.elementAt(i);
                            l.append(n, null);
                            targets.addElement(url + n);
                        }
                    }
                } catch (Throwable t) {
                    l.append("Can't read: " + t.getMessage(), null);
                    targets.addElement(url);
                }
                browseItems = targets;
                browseUrl = url;
                browser = l;
                l.addCommand(cBack);
                l.setCommandListener(Standby.this);
                display.setCurrent(l);
            }
        }).start();
    }

    private FileAccess files() {
        if (!filesTried) {
            filesTried = true;
            try {
                Class.forName("javax.microedition.io.file.FileConnection");
                files = (FileAccess) Class.forName("ll.JsrFiles").newInstance();
            } catch (Throwable t) {
                files = null;
            }
        }
        return files;
    }

    private void loadPicture() {
        pictureLoaded = picture;
        idleImage = null;
        FileAccess fa = files();
        if (fa == null || picture.length() == 0) return;
        try {
            idleImage = fa.load(picture);
        } catch (Throwable t) {                             // too big, gone, or not allowed: the glow instead
            idleImage = null;
        }
    }

    private static String str(Hashtable t, String k) {
        String v = Json.str(t, k);
        return v == null ? "" : v;
    }

    private static String[] split(String s) {
        Vector v = new Vector();
        int st = 0;
        for (int k = 0; k <= s.length(); k++) {
            if (k == s.length() || s.charAt(k) == '\n') {
                v.addElement(s.substring(st, k));
                st = k + 1;
            }
        }
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    /** The wallpaper: the artwork centred over a blurred, darkened, screen-filling copy of it. */
    private final class Face extends Canvas {
        private Image bg, bgSrc;
        private int shadow;

        Face() {
            setFullScreenMode(true);
        }

        int coverSize() {
            return Math.max(48, Math.min(getWidth(), getHeight()) - 48);
        }

        protected void showNotify() {
            kick = true;                                // fresh state as soon as standby shows
        }

        private long lastHash;

        /** # twice: the settings. */
        protected void keyPressed(int k) {
            if (k != KEY_POUND) return;
            long now = System.currentTimeMillis();
            if (now - lastHash < 800) {
                lastHash = 0;
                showOptions();
            } else {
                lastHash = now;
            }
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            g.setClip(0, 0, w, h);
            if (idle() && idleMode == IDLE_PICTURE && idleImage != null) {   // your picture, centred
                g.setColor(0x000000);
                g.fillRect(0, 0, w, h);
                g.drawImage(idleImage, w / 2, h / 2, Graphics.VCENTER | Graphics.HCENTER);
                return;
            }
            Image c = idle() ? null : cover;
            if (c == null) {                            // nothing yet: the glow, a note glyph, any message
                Style.background(g, w, h);
                Style.glyph(g, Style.G_NOTE, w / 2, h / 2, Math.min(w, h) / 4, 0x3a3a3a);
                if (note.length() > 0) {
                    g.setFont(Style.SMALL);
                    g.setColor(Style.TEXT_DIM);
                    g.drawString(note, w / 2, h / 2 + Math.min(w, h) / 6, Graphics.TOP | Graphics.HCENTER);
                }
                return;
            }
            if (bgSrc != c || bg == null || bg.getWidth() != w || bg.getHeight() != h) {
                try {
                    bg = blurred(c, w, h);
                } catch (Throwable t) {                 // out of memory: plain glow behind the cover
                    bg = null;
                }
                bgSrc = c;
            }
            if (bg != null) g.drawImage(bg, 0, 0, Graphics.TOP | Graphics.LEFT);
            else Style.background(g, w, h);

            int s = Math.min(coverSize(), Math.min(c.getWidth(), c.getHeight()));
            int ax = (w - s) / 2, ay = (h - s) / 2;
            for (int k = 3; k >= 1; k--) {              // a soft shadow in the background's own colour
                g.setColor(mix(shadow, 0x000000, 4 - k, 4));
                g.drawRect(ax - k, ay - k + 1, s + 2 * k - 1, s + 2 * k - 1);
            }
            g.clipRect(ax, ay, s, s);
            g.drawImage(c, w / 2, h / 2, Graphics.VCENTER | Graphics.HCENTER);
            g.setClip(0, 0, w, h);
        }

        /**
         * The cover cut to the screen's shape, averaged down to a small grid, softened twice,
         * darkened, and stretched back up smoothly (bilinear), one row at a time.
         */
        private Image blurred(Image c, int w, int h) {
            int cw = c.getWidth(), ch = c.getHeight();
            int[] src = new int[cw * ch];
            c.getRGB(src, 0, cw, 0, 0, cw, ch);
            int sw = cw, sh = ch;
            if (h * cw > w * ch) sw = Math.max(1, ch * w / h);
            else sh = Math.max(1, cw * h / w);
            int sx0 = (cw - sw) / 2, sy0 = (ch - sh) / 2;
            int gw = 10, gh = Math.max(4, gw * h / w);
            int[] r = new int[gw * gh], gr = new int[gw * gh], b = new int[gw * gh];
            for (int gy = 0; gy < gh; gy++) {
                int y0 = sy0 + gy * sh / gh, y1 = Math.max(y0 + 1, sy0 + (gy + 1) * sh / gh);
                for (int gx = 0; gx < gw; gx++) {
                    int x0 = sx0 + gx * sw / gw, x1 = Math.max(x0 + 1, sx0 + (gx + 1) * sw / gw);
                    int n = 0, tr = 0, tg = 0, tb = 0;
                    for (int y = y0; y < y1; y += 2) {
                        for (int x = x0; x < x1; x += 2) {
                            int p = src[y * cw + x];
                            tr += (p >> 16) & 255;
                            tg += (p >> 8) & 255;
                            tb += p & 255;
                            n++;
                        }
                    }
                    int i = gy * gw + gx;
                    r[i] = tr / n;
                    gr[i] = tg / n;
                    b[i] = tb / n;
                }
            }
            src = null;
            for (int pass = 0; pass < 2; pass++) {
                r = soften(r, gw, gh);
                gr = soften(gr, gw, gh);
                b = soften(b, gw, gh);
            }
            int ar = 0, ag = 0, ab = 0;
            for (int i = 0; i < r.length; i++) {        // darker, so the sharp cover stands out
                r[i] = r[i] * 60 / 100;
                gr[i] = gr[i] * 60 / 100;
                b[i] = b[i] * 60 / 100;
                ar += r[i];
                ag += gr[i];
                ab += b[i];
            }
            shadow = ((ar / r.length / 2) << 16) | ((ag / r.length / 2) << 8) | (ab / r.length / 2);

            Image out = Image.createImage(w, h);
            Graphics og = out.getGraphics();
            int[] row = new int[w];
            int[] lr = new int[gw], lg = new int[gw], lb = new int[gw];
            for (int y = 0; y < h; y++) {
                int fy = h > 1 ? y * (gh - 1) * 256 / (h - 1) : 0;
                int iy = Math.min(fy >> 8, gh - 1), iy2 = Math.min(iy + 1, gh - 1), ty = fy & 255;
                for (int gx = 0; gx < gw; gx++) {       // this screen row, at grid resolution
                    int a = iy * gw + gx, d = iy2 * gw + gx;
                    lr[gx] = r[a] + (r[d] - r[a]) * ty / 256;
                    lg[gx] = gr[a] + (gr[d] - gr[a]) * ty / 256;
                    lb[gx] = b[a] + (b[d] - b[a]) * ty / 256;
                }
                for (int x = 0; x < w; x++) {
                    int fx = w > 1 ? x * (gw - 1) * 256 / (w - 1) : 0;
                    int ix = Math.min(fx >> 8, gw - 1), ix2 = Math.min(ix + 1, gw - 1), tx = fx & 255;
                    int pr = lr[ix] + (lr[ix2] - lr[ix]) * tx / 256;
                    int pg = lg[ix] + (lg[ix2] - lg[ix]) * tx / 256;
                    int pb = lb[ix] + (lb[ix2] - lb[ix]) * tx / 256;
                    row[x] = 0xff000000 | (pr << 16) | (pg << 8) | pb;
                }
                og.drawRGB(row, 0, w, 0, y, w, 1, false);
            }
            return out;
        }

        /** 3x3 box blur over the small grid (edges repeat). */
        private int[] soften(int[] v, int gw, int gh) {
            int[] o = new int[v.length];
            for (int y = 0; y < gh; y++) {
                for (int x = 0; x < gw; x++) {
                    int t = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = Math.max(0, Math.min(gh - 1, y + dy));
                        for (int dx = -1; dx <= 1; dx++) {
                            t += v[yy * gw + Math.max(0, Math.min(gw - 1, x + dx))];
                        }
                    }
                    o[y * gw + x] = t / 9;
                }
            }
            return o;
        }

        private int mix(int a, int b, int n, int d) {
            int r = (((a >> 16) & 255) * (d - n) + ((b >> 16) & 255) * n) / d;
            int g = (((a >> 8) & 255) * (d - n) + ((b >> 8) & 255) * n) / d;
            int bl = ((a & 255) * (d - n) + (b & 255) * n) / d;
            return (r << 16) | (g << 8) | bl;
        }
    }
}
