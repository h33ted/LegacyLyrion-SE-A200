package ll;

import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

/**
 * Walkman-style Now Playing: artwork, title, progress, round transport buttons, volume.
 * Keys: left/right (4/6) skip, centre (5) play/pause, up/down (2/8) volume.
 */
final class NowPlayingScreen extends Screen implements Runnable {
    private String track = "", artist = "", album = "", artKey = "";
    private boolean playing;
    private int time, dur, vol;
    private Image cover;
    private volatile boolean running;
    private int bx, by, br; // transport layout for taps
    private int volY;

    NowPlayingScreen(LegacyLyrion app) {
        super(app, "Now playing");
        leftLabel = "More";
        rightLabel = "Back";
    }

    private volatile int gen;

    void shown() {
        running = true;
        gen++;
        new Thread(this).start();
    }

    void hidden() {
        running = false;
        gen++;
    }

    public void run() {
        int my = gen;                               // one poller per visit, even when reopened quickly
        try {
            while (running && my == gen) {
                poll();
                for (int k = 0; k < 4 && running && my == gen; k++) {  // poll every 4 s, tick the clock in between
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
                    if (playing && time < dur) time++;
                    app.shell.repaint();
                }
            }
        } catch (Throwable t) {
            app.fault("now playing", t);
        }
    }

    private void poll() {
        try {
            Hashtable r = app.lms.call(app.player, new String[]{"status", "-", "1", "tags:alcK"});
            playing = "play".equals(Json.str(r, "mode"));
            time = Json.toInt(Json.str(r, "time"), 0);
            dur = Json.toInt(Json.str(r, "duration"), 0);
            vol = Math.abs(Json.toInt(Json.str(r, "mixer volume"), 0));
            Vector loop = Json.arr(r, "playlist_loop");
            Hashtable t = loop != null && loop.size() > 0 && loop.elementAt(0) instanceof Hashtable
                    ? (Hashtable) loop.elementAt(0) : null;
            if (t == null) {
                track = "Nothing playing";
                artist = Json.str(r, "player_name") == null ? "" : Json.str(r, "player_name");
                album = "";
                cover = null;
                artKey = "";
            } else {
                track = Json.str(t, "title") == null ? "" : Json.str(t, "title");
                artist = Json.str(t, "artist") == null ? "" : Json.str(t, "artist");
                album = Json.str(t, "album") == null ? "" : Json.str(t, "album");
                String icon = Json.str(t, "artwork_url");
                if (icon == null && Json.str(t, "coverid") != null) icon = "/music/" + Json.str(t, "coverid") + "/cover.png";
                if (icon == null) icon = "/music/current/cover.png?player=" + Lms.urlEncode(app.player);
                String key = icon;
                if (!key.equals(artKey)) {
                    artKey = key;
                    int s = coverSize();
                    String url;
                    if (icon.startsWith("/music/")) {
                        int q = icon.indexOf('?');
                        String path = q >= 0 ? icon.substring(0, q) : icon;
                        String query = q >= 0 ? icon.substring(q) : "";
                        url = path.substring(0, path.lastIndexOf('/') + 1) + "cover_" + s + "x" + s + "_p.png" + query;
                    } else {
                        url = app.lms.art(icon, s);
                    }
                    cover = app.lms.image(url);
                }
            }
            app.setNowPlaying(track, artist);
            centreLabel = playing ? "Pause" : "Play";
        } catch (Exception e) {
            track = "Can't reach the player";
            artist = e.getMessage() == null ? "" : e.getMessage();
        }
        app.shell.repaint();
    }

    private int coverSize() {
        int w = Math.min(app.shell.lw(), app.shell.lh());   // same artwork size either way up
        int tall = Math.max(app.shell.lw(), app.shell.lh()) - 34;   // content height upright
        // leave room for the title, progress, buttons and volume under it (240x320 phones)
        return Math.max(48, Math.min(Math.min(w - 60, 180), tall - 178));
    }

    private int barX, barW;

    void paint(Graphics g, int w, int h) {
        int s = coverSize();
        boolean wide = w > h;
        // landscape: artwork on the left, everything else in a column on the right
        int s2 = wide ? Math.min(s, h - 12) : s;
        int ax = wide ? 10 + s2 / 2 : w / 2;
        int ay = wide ? (h - s2) / 2 : 10;
        g.setColor(0x333333);
        g.drawRect(ax - s2 / 2 - 1, ay - 1, s2 + 1, s2 + 1);
        if (cover != null) {
            g.clipRect(ax - s2 / 2, ay, s2, s2);       // centre-crop if the slot is smaller
            g.drawImage(cover, ax, ay + s2 / 2, Graphics.VCENTER | Graphics.HCENTER);
            g.setClip(0, 0, w, h);
        } else {
            g.setColor(0x202020);
            g.fillRect(ax - s2 / 2, ay, s2, s2);
            Style.glyph(g, Style.G_NOTE, ax, ay + s2 / 2, s2 / 3, 0x555555);
        }
        int left = wide ? s2 + 20 : 0;              // the column the text and controls use
        int cw = w - left;
        int cx = left + cw / 2;
        int y = wide ? 8 : ay + s2 + 10;

        g.setFont(Style.BIG);
        g.setColor(Style.TEXT);
        g.drawString(Style.fit(track, Style.BIG, cw - 16), cx, y, Graphics.TOP | Graphics.HCENTER);
        y += Style.BIG.getHeight();
        g.setFont(Style.SMALL);
        g.setColor(Style.TEXT_DIM);
        String sub = album.length() > 0 ? artist + " - " + album : artist;
        g.drawString(Style.fit(sub, Style.SMALL, cw - 16), cx, y, Graphics.TOP | Graphics.HCENTER);
        y += Style.SMALL.getHeight() + 8;

        // progress
        int px = left + (wide ? 4 : 16), pw = cw - (wide ? 20 : 32);
        g.setColor(0x3a3a3a);
        g.fillRoundRect(px, y, pw, 4, 4, 4);
        if (dur > 0) {
            g.setColor(Style.ACCENT);
            g.fillRoundRect(px, y, Math.max(4, pw * Math.min(time, dur) / dur), 4, 4, 4);
        }
        y += 7;
        g.setColor(Style.TEXT_DIM);
        g.drawString(clock(time), px, y, Graphics.TOP | Graphics.LEFT);
        g.drawString(dur > 0 ? clock(dur) : "", px + pw, y, Graphics.TOP | Graphics.RIGHT);
        y += Style.SMALL.getHeight() + 6;

        // transport: Walkman-style round buttons
        int room = h - y - 22;                      // leave space for the volume bar
        br = Math.max(14, Math.min(28, wide ? room / 2 : (h - y) / 3));
        by = y + br;
        bx = cx;
        circle(g, cx - br * 3, by, br * 3 / 4, Style.G_PREV, false);
        circle(g, cx, by, br, playing ? Style.G_PAUSE : Style.G_PLAY, true);
        circle(g, cx + br * 3, by, br * 3 / 4, Style.G_NEXT, false);
        y = by + br + (wide ? 6 : 10);

        // volume: a little lower where the screen has room for it (Aino)
        if (!wide && h - y > 50) y += 8;
        volY = y;
        barX = px + 14;
        barW = pw - 28;
        g.setColor(Style.TEXT_DIM);
        g.drawString("-", px, y - 3, Graphics.TOP | Graphics.LEFT);
        g.drawString("+", px + pw, y - 3, Graphics.TOP | Graphics.RIGHT);
        g.setColor(0x3a3a3a);
        g.fillRoundRect(barX, y + 3, barW, 4, 4, 4);
        g.setColor(0xbbbbbb);
        g.fillRoundRect(barX, y + 3, barW * vol / 100, 4, 4, 4);
    }

    /** Transport button: dark glass disc with a thin grey ring, white symbol (larger for play). */
    private void circle(Graphics g, int x, int y, int r, int glyph, boolean main) {
        Style.glyph(g, Style.G_DOT, x, y, 2 * r, main ? 0x2c2c2c : 0x161616);
        Style.glyph(g, Style.G_RING, x, y, 2 * r, main ? 0xa0a0a0 : 0x5a5a5a);
        Style.glyph(g, glyph, x, y, main ? r * 4 / 5 : r * 3 / 4, main ? Style.TEXT : 0xd0d0d0);
    }

    private static String clock(int s) {
        int sec = s % 60;
        return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    void tap(int x, int y, int w, int h) {
        if (Math.abs(y - by) <= br + 6) {
            if (Math.abs(x - bx) <= br + 6) select();
            else if (x < bx) send(new String[]{"playlist", "index", "-1"});
            else send(new String[]{"playlist", "index", "+1"});
        } else if (y >= volY - 10 && y <= volY + 18) {
            int pct = (x - barX) * 100 / Math.max(1, barW);
            send(new String[]{"mixer", "volume", String.valueOf(Math.max(0, Math.min(100, pct)))});
        }
    }

    void key(int game, int keyCode) {
        if (game == Canvas.LEFT || keyCode == Canvas.KEY_NUM4) send(new String[]{"playlist", "index", "-1"});
        else if (game == Canvas.RIGHT || keyCode == Canvas.KEY_NUM6) send(new String[]{"playlist", "index", "+1"});
        else if (game == Canvas.UP || keyCode == Canvas.KEY_NUM2) send(new String[]{"mixer", "volume", "+5"});
        else if (game == Canvas.DOWN || keyCode == Canvas.KEY_NUM8) send(new String[]{"mixer", "volume", "-5"});
    }

    void select() {
        playing = !playing;
        centreLabel = playing ? "Pause" : "Play";
        app.shell.repaint();
        send(playing ? new String[]{"play"} : new String[]{"pause", "1"});
    }

    String[] options() {
        return new String[]{"Shuffle on/off", "Repeat: off/all/one", "Choose player", "Clear queue"};
    }

    void option(int i) {
        if (i == 0) send(new String[]{"playlist", "shuffle"});
        else if (i == 1) send(new String[]{"playlist", "repeat"});
        else if (i == 2) app.openPlayers();
        else if (i == 3) send(new String[]{"playlist", "clear"});
    }

    private void send(final String[] cmd) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    app.lms.call(app.player, cmd);
                } catch (Exception e) {
                    app.shell.toast("Failed: " + e.getMessage());
                }
                poll();
            }
        }).start();
    }
}
