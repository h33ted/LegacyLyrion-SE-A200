package ll;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.io.SocketConnection;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;

/**
 * SlimProto client: makes the phone a real Squeezebox player (TCP 3483), like squeezelite.
 * Lyrion sends strm/audg/setd; we answer with STAT events. Audio is the MP3 stream Lyrion asks
 * us to fetch over HTTP, played through MMAPI either:
 *  - CHUNKED (default): download ~CHUNK bytes at a time, cut at MP3 frame boundaries, play each
 *    piece as its own Player with the next one prefetched. Works on firmware that can't play an
 *    endless stream; costs a very short gap between pieces.
 *  - DIRECT: hand the live HTTP stream to one Player. Gapless if the firmware supports it.
 */
final class Slim implements Runnable, PlayerListener {
    static final int MODE_CHUNKED = 0, MODE_DIRECT = 1;
    private static final int CHUNK = 256 * 1024;  // ~16 s at 128 kbit/s, ~6.5 s at 320 kbit/s

    private final LegacyLyrion app;
    private final String host, mac, name;
    private final int mode;
    private volatile boolean running = true;
    private SocketConnection sock;
    private DataOutputStream out;

    // stream state
    private int streamGen;
    private HttpConnection http;
    private InputStream audioIn;
    private final Vector chunks = new Vector();
    private boolean eof, paused, started;
    private Player cur, ready;
    private long playedMs;          // duration of finished chunks
    private int volume = 80;
    private long bytesIn;

    Slim(LegacyLyrion app, String host, String mac, String name, int mode) {
        this.app = app;
        this.host = host;
        this.mac = mac;
        this.name = name;
        this.mode = mode;
    }

    void start() {
        new Thread(this).start();
    }

    void stop() {
        running = false;
        stopStream();
        try { if (sock != null) sock.close(); } catch (Exception e) {}
    }

    // ------------------------------------------------------------------ control connection

    public void run() {
        while (running) {
            try {
                app.log("SlimProto: connecting to " + host + ":3483");
                sock = (SocketConnection) Connector.open("socket://" + host + ":3483");
                try { sock.setSocketOption(SocketConnection.DELAY, 0); } catch (Exception e) {}
                DataInputStream in = sock.openDataInputStream();
                out = sock.openDataOutputStream();
                helo();
                app.log("SlimProto: registered as " + name);
                app.shell.toast("This phone is a Lyrion player: " + name);
                while (running) {
                    int len = in.readUnsignedShort();
                    byte[] op = new byte[4];
                    in.readFully(op);
                    byte[] data = new byte[Math.max(0, len - 4)];
                    if (data.length > 0) in.readFully(data);
                    frame(new String(op), data);
                }
            } catch (Throwable t) {
                if (running) app.log("SlimProto: connection lost (" + t + ")");
            }
            out = null;
            try { if (sock != null) sock.close(); } catch (Exception e) {}
            if (!running) break;
            try { Thread.sleep(4000); } catch (InterruptedException e) { break; }
        }
    }

    private void helo() throws Exception {
        String caps = "Model=squeezelite,ModelName=" + name.replace(',', ' ') + ",Firmware=JavaME,MaxSampleRate=48000,mp3";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeByte(12);            // device id: squeezelite
        d.writeByte(1);             // revision
        d.write(macBytes(mac));
        d.write(new byte[16]);      // uuid
        d.writeShort(0x8000);       // wlan channels / flags
        d.writeLong(0);             // bytes received
        d.writeByte('E');
        d.writeByte('N');
        d.write(caps.getBytes("UTF-8"));
        send("HELO", b.toByteArray());
    }

    private synchronized void send(String tag, byte[] payload) {
        DataOutputStream o = out;
        if (o == null) return;
        try {
            o.write(tag.getBytes());
            o.writeInt(payload.length);
            o.write(payload);
            o.flush();
        } catch (Exception e) {
            app.log("SlimProto: send failed " + e);
        }
    }

    void stat(String event, int serverTs) {
        try {
            long ms = elapsedMs();
            ByteArrayOutputStream b = new ByteArrayOutputStream(53);
            DataOutputStream d = new DataOutputStream(b);
            d.write(event.getBytes());
            d.writeByte(0); d.writeByte(0); d.writeByte(0);
            d.writeInt(CHUNK * 2);                      // buffer size
            d.writeInt(chunks.size() * CHUNK);          // buffer fullness
            d.writeLong(bytesIn);                       // bytes received
            d.writeShort(0xffff);                       // signal strength (wired)
            d.writeInt((int) System.currentTimeMillis()); // jiffies
            d.writeInt(CHUNK);                          // output buffer size
            d.writeInt(cur != null ? CHUNK / 2 : 0);    // output buffer fullness
            d.writeInt((int) (ms / 1000));              // elapsed seconds
            d.writeShort(0);                            // voltage
            d.writeInt((int) ms);                       // elapsed ms
            d.writeInt(serverTs);                       // server timestamp (echo)
            d.writeShort(0);                            // error code
            send("STAT", b.toByteArray());
        } catch (Exception e) {}
    }

    private void frame(String op, byte[] p) {
        if (op.equals("strm") && p.length >= 24) {
            char sub = (char) p[0];
            if (sub == 't') {
                stat("STMt", int32(p, 14));
                return;
            }
            app.log("SlimProto: strm " + sub);
            if (sub == 's') {
                int port = ((p[18] & 255) << 8) | (p[19] & 255);
                String ip = (p[20] | p[21] | p[22] | p[23]) == 0 ? host
                        : (p[20] & 255) + "." + (p[21] & 255) + "." + (p[22] & 255) + "." + (p[23] & 255);
                String req = p.length > 24 ? new String(p, 24, p.length - 24) : "";
                String path = "/stream.mp3?player=" + mac;
                if (req.startsWith("GET ")) {
                    int e = req.indexOf(" HTTP/");
                    if (e > 4) path = req.substring(4, e).trim();
                }
                boolean auto = p[1] == '1' || p[1] == '3';
                startStream("http://" + ip + ":" + (port > 0 ? port : 9000) + path, auto);
            } else if (sub == 'p') {
                pause();
                stat("STMp", 0);
            } else if (sub == 'u') {
                resume();
                stat("STMr", 0);
            } else if (sub == 'q' || sub == 'f') {
                stopStream();
                stat("STMf", 0);
            } else {
                stat("STMt", 0);
            }
        } else if (op.equals("audg") && p.length >= 14) {
            long left = ((long) int32(p, 10)) & 0xffffffffL;      // 16.16 fixed point
            volume = gainToPercent(left);
            app.log("Volume: gain " + left + " -> level " + volume);
            applyVolume(cur);
            applyVolume(ready);
        } else if (op.equals("setd") && p.length == 1 && p[0] == 0) {
            byte[] n = name.getBytes();
            byte[] r = new byte[n.length + 2];
            System.arraycopy(n, 0, r, 1, n.length);
            send("SETD", r);
        }
    }

    // ------------------------------------------------------------------ audio

    private synchronized void startStream(final String url, final boolean auto) {
        stopStream();
        final int gen = ++streamGen;
        paused = !auto;
        started = false;
        eof = false;
        playedMs = 0;
        bytesIn = 0;
        app.log("Audio: " + (mode == MODE_CHUNKED ? "chunked" : "direct") + " " + url);
        stat("STMc", 0);
        new Thread(new Runnable() {
            public void run() {
                try {
                    http = (HttpConnection) Connector.open(url);
                    int rc = http.getResponseCode();
                    if (rc != HttpConnection.HTTP_OK) throw new Exception("HTTP " + rc);
                    audioIn = http.openInputStream();
                    stat("STMh", 0);
                    if (mode == MODE_DIRECT) direct(gen);
                    else download(gen);
                } catch (Throwable t) {
                    if (gen == streamGen) {
                        app.error("Playback failed", t);
                        stat("STMn", 0);
                    }
                }
            }
        }).start();
    }

    private void direct(int gen) throws Exception {
        Player p = Manager.createPlayer(audioIn, "audio/mpeg");
        p.addPlayerListener(this);
        p.realize();
        p.prefetch();
        if (gen != streamGen) { p.close(); return; }
        cur = p;
        applyVolume(p);
        stat("STMl", 0);
        if (!paused) begin();
    }

    /**
     * Reads the HTTP stream and cuts it into pieces of whole MP3 frames. It walks the stream frame
     * by frame using each header's length, so a piece never ends in a half frame and never starts
     * on a false sync inside audio data (both can upset a phone's decoder). Bytes that are not part
     * of a valid frame (tags, garbage after a glitch) are dropped.
     */
    private void download(int gen) throws Exception {
        byte[] buf = new byte[CHUNK + 16384];
        int len = 0;        // bytes held in buf
        int pos = 0;        // next frame header; everything before it is whole frames
        boolean synced = false;
        while (gen == streamGen) {
            if (len == buf.length) {                    // full and still no frame boundary: drop junk
                int keep = Math.min(len, 8);
                System.arraycopy(buf, len - keep, buf, 0, keep);
                len = keep;
                pos = 0;
                synced = false;
            }
            int n = audioIn.read(buf, len, buf.length - len);
            if (n < 0) break;
            len += n;
            bytesIn += n;
            while (pos + 4 <= len) {
                int fl = synced ? frameLen(buf, pos) : -1;
                if (fl < 0) {                           // find the next header confirmed by the one after it
                    int k = pos;
                    boolean need = false;
                    for (; k + 4 <= len; k++) {
                        int a = frameLen(buf, k);
                        if (a < 0) continue;
                        if (k + a + 4 > len) { need = true; break; }
                        if (frameLen(buf, k + a) > 0) break;
                    }
                    if (k > pos) {                      // cut the junk out
                        System.arraycopy(buf, k, buf, pos, len - k);
                        len -= k - pos;
                    }
                    if (need || pos + 4 > len) break;   // wait for more data
                    fl = frameLen(buf, pos);
                    if (fl < 0) break;
                    synced = true;
                }
                if (pos + fl > len) break;              // frame not complete yet
                pos += fl;
                if (pos >= CHUNK) {
                    byte[] chunk = new byte[pos];
                    System.arraycopy(buf, 0, chunk, 0, pos);
                    System.arraycopy(buf, pos, buf, 0, len - pos);
                    len -= pos;
                    pos = 0;
                    offer(gen, chunk);
                    synchronized (this) {               // keep at most two pieces waiting
                        while (gen == streamGen && chunks.size() >= 2) {
                            try { wait(500); } catch (InterruptedException e) {}
                        }
                    }
                }
            }
        }
        if (gen != streamGen) return;
        if (pos > 0) {
            byte[] chunk = new byte[pos];
            System.arraycopy(buf, 0, chunk, 0, pos);
            offer(gen, chunk);
        }
        synchronized (this) {
            eof = true;
            if (cur == null && ready == null && chunks.isEmpty()) finished();
        }
    }

    private static final int[] KBPS_V1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] KBPS_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] RATES = {44100, 48000, 32000};

    /** Length in bytes of the MPEG audio layer III frame whose header is at o, or -1 if it isn't one. */
    private static int frameLen(byte[] b, int o) {
        if (o < 0 || o + 3 >= b.length) return -1;
        int h1 = b[o] & 255, h2 = b[o + 1] & 255, h3 = b[o + 2] & 255;
        if (h1 != 0xff || (h2 & 0xe0) != 0xe0) return -1;
        int ver = (h2 >> 3) & 3;                    // 3 = MPEG 1, 2 = MPEG 2, 0 = MPEG 2.5
        if (ver == 1 || ((h2 >> 1) & 3) != 1) return -1;
        int bi = h3 >> 4, si = (h3 >> 2) & 3, pad = (h3 >> 1) & 1;
        if (bi == 0 || bi == 15 || si == 3) return -1;
        int rate = RATES[si] >> (ver == 3 ? 0 : ver == 2 ? 1 : 2);
        return ver == 3 ? 144000 * KBPS_V1[bi] / rate + pad : 72000 * KBPS_V2[bi] / rate + pad;
    }

    private synchronized void offer(int gen, byte[] chunk) {
        if (gen != streamGen) return;
        chunks.addElement(chunk);
        if (cur == null) {                      // first piece, or recovering from an underrun
            if (!started) stat("STMl", 0);
            if (!paused) begin();
        } else if (ready == null) {
            prepareNext();
        }
    }

    private synchronized void begin() {
        if (mode == MODE_DIRECT) {
            if (cur == null) return;
            try { cur.start(); applyVolume(cur); } catch (Exception e) { app.error("Playback failed", e); }
        } else {
            if (cur == null) playNextChunk();
        }
        if (!started && cur != null) {
            started = true;
            stat("STMs", 0);
        }
    }

    private synchronized void playNextChunk() {
        if (ready == null) prepareNext();
        Player p = ready;
        ready = null;
        if (p == null) {
            if (eof) finished();
            else {
                app.log("Audio: buffer underrun");
                stat("STMo", 0);
            }
            return;
        }
        cur = p;
        try {
            p.start();
            applyVolume(p);                 // some firmware resets the level on start
        } catch (Exception e) {
            app.error("Playback failed", e);
        }
        prepareNext();
    }

    private synchronized void prepareNext() {
        if (ready != null || chunks.isEmpty()) return;
        byte[] c = (byte[]) chunks.elementAt(0);
        chunks.removeElementAt(0);
        notifyAll();
        try {
            Player p = Manager.createPlayer(new ByteArrayInputStream(c), "audio/mpeg");
            p.addPlayerListener(this);
            p.realize();
            p.prefetch();
            applyVolume(p);
            ready = p;
        } catch (Exception e) {
            app.error("Can't decode MP3 on this phone", e);
        }
    }

    public void playerUpdate(Player p, String event, Object data) {
        try {
            update(p, event, data);
        } catch (Throwable t) {               // never let an exception escape into the phone's media thread
            app.fault("player event " + event, t);
        }
    }

    private void update(Player p, String event, Object data) {
        if (event == PlayerListener.END_OF_MEDIA) {
            synchronized (this) {
                if (p != cur) return;
                try { playedMs += p.getDuration() / 1000; } catch (Exception e) {}
                cur = null;
                try { p.close(); } catch (Exception e) {}
                if (mode == MODE_DIRECT) finished();
                else if (!paused) playNextChunk();
            }
        } else if (event == PlayerListener.ERROR) {
            app.error("Media player error", new Exception(String.valueOf(data)));
            stat("STMn", 0);
        }
    }

    /**
     * Reported only once the track has actually played out: STMd (decoder done) makes Lyrion send
     * the next track, which would cut off pieces still queued if it came earlier. Costs a short
     * pause between tracks; fine for a prototype.
     */
    private void finished() {
        app.log("Audio: track finished");
        stat("STMd", 0);
        stat("STMu", 0);
    }

    private synchronized void pause() {
        paused = true;
        if (cur != null) try { cur.stop(); } catch (Exception e) {}
    }

    private synchronized void resume() {
        paused = false;
        if (cur != null) {
            try { cur.start(); } catch (Exception e) {}
        } else {
            begin();
        }
    }

    synchronized void stopStream() {
        streamGen++;
        notifyAll();
        if (cur != null) try { cur.close(); } catch (Exception e) {}
        if (ready != null) try { ready.close(); } catch (Exception e) {}
        cur = null;
        ready = null;
        chunks.removeAllElements();
        try { if (audioIn != null) audioIn.close(); } catch (Exception e) {}
        try { if (http != null) http.close(); } catch (Exception e) {}
        audioIn = null;
        http = null;
    }

    /**
     * Lyrion sends a gain, not the slider value: its 0-100 slider spans -50 dB to 0 dB, so
     * slider 50 arrives as a gain of about 0.06. Feeding that straight to the phone's 0-100
     * volume made everything below about 80 near silent. This turns the gain back into the
     * slider position: one step per 0.5 dB.
     */
    private static int gainToPercent(long gain) {
        if (gain <= 0) return 0;
        double t = 65536;
        for (int v = 100; v > 0; v--) {
            if (gain >= t - 1) return v;
            t /= 1.0592537;                 // 10^(0.5 / 20)
        }
        return 0;
    }

    private void applyVolume(Player p) {
        if (p == null) return;
        try {
            VolumeControl vc = (VolumeControl) p.getControl("VolumeControl");
            if (vc != null) vc.setLevel(volume);
        } catch (Exception e) {}
    }

    private long elapsedMs() {
        long t = playedMs;
        Player p = cur;
        if (p != null) {
            try { t += Math.max(0, p.getMediaTime() / 1000); } catch (Exception e) {}
        }
        return t;
    }

    // ------------------------------------------------------------------ util

    private static int int32(byte[] p, int o) {
        return ((p[o] & 255) << 24) | ((p[o + 1] & 255) << 16) | ((p[o + 2] & 255) << 8) | (p[o + 3] & 255);
    }

    private static byte[] macBytes(String mac) {
        byte[] b = new byte[6];
        int k = 0, st = 0;
        for (int i = 0; i <= mac.length() && k < 6; i++) {
            if (i == mac.length() || mac.charAt(i) == ':') {
                b[k++] = (byte) Integer.parseInt(mac.substring(st, i), 16);
                st = i + 1;
            }
        }
        return b;
    }
}
