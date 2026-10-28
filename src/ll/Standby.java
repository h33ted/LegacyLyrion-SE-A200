package ll;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * LegacyLyrion as the phone's standby wallpaper. Sony Ericsson phones offer MIDlets with
 * "SEMC-StandbyApplication: Y" under Settings > Display > Wallpaper > Application. This is a
 * separate, light suite: it reads the server and the controlled player from LegacyLyrion's own
 * settings (a record store LegacyLyrion shares), and shows the artwork and what's playing. It polls
 * Lyrion only while the standby screen is visible, and is passive: keys belong to the phone.
 */
public final class Standby extends MIDlet implements Runnable {
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

    protected void startApp() {
        if (face == null) {
            face = new Face();
            load();
            running = true;
            new Thread(this).start();
        }
        Display.getDisplay(this).setCurrent(face);
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
                face.repaint();
            }
            // while shown: every 5 s when playing (progress), 15 s when not; hidden: just wait
            long wait = !face.isShown() ? 3000 : playing ? 5000 : 15000;
            for (long t = 0; t < wait && running && !kick; t += 250) {
                try { Thread.sleep(250); } catch (InterruptedException e) {}
                if (playing && dur > 0 && time < dur && t % 1000 == 0 && face.isShown()) {
                    time++;
                    face.repaint();
                }
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

    /** The wallpaper: the glow, the artwork, and the track under (portrait) or beside it (landscape). */
    private final class Face extends Canvas {
        Face() {
            setFullScreenMode(true);
        }

        int coverSize() {
            int w = getWidth(), h = getHeight();
            return w > h ? Math.max(48, Math.min(h - 2 * MARGIN, w / 2 - 20))
                    : Math.max(48, Math.min(w - 56, (h - 2 * MARGIN) * 52 / 100));
        }

        protected void showNotify() {
            kick = true;                                // fresh state as soon as standby shows
        }

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            g.setClip(0, 0, w, h);
            Style.background(g, w, h);
            int s = coverSize();
            boolean wide = w > h;
            int ax = wide ? 14 : (w - s) / 2, ay = wide ? (h - s) / 2 : MARGIN + 8;
            if (note.equals(SETUP)) {
                g.setFont(Style.ROW);
                g.setColor(Style.TEXT_DIM);
                g.drawString(note, w / 2, h / 2, Graphics.BASELINE | Graphics.HCENTER);
                return;
            }
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

            int left = wide ? ax + s + 16 : 12, right = w - 12, cw = right - left;
            int block = Style.BIG.getHeight() + Style.SMALL.getHeight() + 14;
            int y = wide ? (h - block) / 2 : ay + s + 12;
            int anchor = wide ? Graphics.LEFT : Graphics.HCENTER, tx = wide ? left : w / 2;
            g.setFont(Style.BIG);
            g.setColor(Style.TEXT);
            g.drawString(Style.fit(track, Style.BIG, cw), tx, y, Graphics.TOP | anchor);
            y += Style.BIG.getHeight();
            g.setFont(Style.SMALL);
            g.setColor(Style.TEXT_DIM);
            String line = note.length() > 0 ? note : sub;
            g.drawString(Style.fit(line, Style.SMALL, cw), tx, y, Graphics.TOP | anchor);
            y += Style.SMALL.getHeight() + 8;

            // progress, with a small play/pause mark
            int bx = left + 14, bw = cw - 14;
            Style.glyph(g, playing ? Style.G_PLAY : Style.G_PAUSE, left + 5, y + 2, 10, Style.TEXT_DIM);
            g.setColor(0x3a3a3a);
            g.fillRoundRect(bx, y, bw, 3, 3, 3);
            if (dur > 0) {
                g.setColor(Style.ACCENT);
                g.fillRoundRect(bx, y, Math.max(3, bw * Math.min(time, dur) / dur), 3, 3, 3);
            }
        }
    }
}
