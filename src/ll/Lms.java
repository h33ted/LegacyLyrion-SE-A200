package ll;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Image;
import javax.microedition.rms.RecordStore;

/** Lyrion JSON-RPC over plain HTTP (no TLS needed on the LAN). Call from worker threads only. */
final class Lms {
    String host = "";
    String port = "9000";

    String base() {
        return "http://" + host + ":" + port;
    }

    /**
     * Sends one command. Every request asks for its own connection ("Connection: close"): these
     * phones otherwise reuse a kept-alive socket that Lyrion has already closed after a few idle
     * seconds, and the request dies with "response empty". Read-only queries are retried once if
     * a request fails anyway; commands that change the queue are not, so nothing plays or queues twice.
     */
    Hashtable call(String player, String[] cmd) throws Exception {
        boolean query = false;
        for (int k = 0; k < cmd.length && k < 2; k++) {
            String t = cmd[k];
            if (t.equals("items") || t.equals("status") || t.equals("players") || t.equals("apps")
                    || t.equals("search") || t.equals("serverstatus")) query = true;
        }
        try {
            return send(player, cmd);
        } catch (java.io.IOException e) {
            if (!query) throw e;
            return send(player, cmd);
        }
    }

    static final long TOPS_AGE = 3L * 3600 * 1000;     // menus that rarely change: asked again every 3 hours

    /**
     * call() for replies that rarely change (the plugins' top menus, which plugins the server has):
     * kept in RMS and reused for maxAge; also used, however old, when the server can't be asked.
     * Only replies that contain the key need are kept, so an error ("not signed in") isn't.
     */
    Hashtable callCached(String player, String[] cmd, long maxAge, String need) throws Exception {
        StringBuffer kb = new StringBuffer(host).append('|').append(player == null ? "" : player);
        for (int k = 0; k < cmd.length; k++) kb.append('|').append(cmd[k]);
        String key = kb.toString();
        String name = "lltop" + Integer.toHexString(key.hashCode());
        String text = null;
        long saved = -1;
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(name, false);
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (in.readUTF().equals(key)) {
                saved = in.readLong();
                byte[] t = new byte[in.readInt()];
                in.readFully(t);
                text = new String(t, "UTF-8");
            }
        } catch (Exception ignored) {
        } finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception ignored) {}
        }
        long age = System.currentTimeMillis() - saved;
        if (text != null && age >= 0 && age < maxAge) {
            Hashtable res = Json.obj(Json.parse(text), "result");
            if (res != null) return res;
        }
        String fresh;
        try {
            fresh = sendText(player, cmd);
        } catch (Exception e) {
            if (text != null) {                                     // offline: the old copy is better than nothing
                Hashtable res = Json.obj(Json.parse(text), "result");
                if (res != null) return res;
            }
            throw e;
        }
        Hashtable res = Json.obj(Json.parse(fresh), "result");
        if (res == null) throw new Exception("empty reply");
        if (need == null || res.get(need) != null) {
            rs = null;
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bo);
                byte[] t = fresh.getBytes("UTF-8");
                out.writeUTF(key);
                out.writeLong(System.currentTimeMillis());
                out.writeInt(t.length);
                out.write(t);
                out.flush();
                byte[] r = bo.toByteArray();
                rs = RecordStore.openRecordStore(name, true);
                if (rs.getNumRecords() == 0) rs.addRecord(r, 0, r.length);
                else rs.setRecord(1, r, 0, r.length);
            } catch (Exception ignored) {
            } finally {
                try { if (rs != null) rs.closeRecordStore(); } catch (Exception ignored) {}
            }
        }
        return res;
    }

    /** Forgets the cached menus (Settings > Refresh artwork does this too). */
    static void clearCached() {
        String[] names = RecordStore.listRecordStores();
        for (int k = 0; names != null && k < names.length; k++) {
            if (names[k].startsWith("lltop")) {
                try { RecordStore.deleteRecordStore(names[k]); } catch (Exception ignored) {}
            }
        }
    }

    private Hashtable send(String player, String[] cmd) throws Exception {
        Hashtable res = Json.obj(Json.parse(sendText(player, cmd)), "result");
        if (res == null) throw new Exception("empty reply");
        return res;
    }

    private String sendText(String player, String[] cmd) throws Exception {
        StringBuffer b = new StringBuffer("{\"id\":1,\"method\":\"slim.request\",\"params\":[");
        b.append(Json.quote(player == null ? "" : player)).append(",[");
        for (int k = 0; k < cmd.length; k++) {
            if (k > 0) b.append(',');
            b.append(Json.quote(cmd[k]));
        }
        b.append("]]}");
        byte[] body = b.toString().getBytes("UTF-8");

        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(base() + "/jsonrpc.js");
            c.setRequestMethod(HttpConnection.POST);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Connection", "close");
            c.setRequestProperty("Content-Length", String.valueOf(body.length));
            OutputStream os = c.openOutputStream();
            os.write(body);
            os.close();
            if (c.getResponseCode() != HttpConnection.HTTP_OK) throw new Exception("HTTP " + c.getResponseCode());
            in = c.openInputStream();
            return new String(readAll(in), "UTF-8");
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }

    /** PNG artwork, resized by the server (phones of this age decode PNG reliably). */
    Image image(String path) {
        byte[] d = imageBytes(path);
        if (d == null) return null;
        try {
            return Image.createImage(d, 0, d.length);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The undecoded PNG, for keeping in ArtCache; null on any failure. */
    byte[] imageBytes(String path) {
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(path.startsWith("http") ? path : base() + path);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != HttpConnection.HTTP_OK) return null;
            in = c.openInputStream();
            return readAll(in);
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }

    /** Server-side resized artwork URL for a Jive icon or local cover, as PNG. */
    String art(String icon, int size) {
        if (icon == null) return null;
        String sz = size + "x" + size;
        if (icon.startsWith("http")) return "/imageproxy/" + urlEncode(icon) + "/image_" + sz + "_p.png";
        if (icon.indexOf('/') < 0) return "/music/" + icon + "/cover_" + sz + "_p.png";   // a Jive "icon-id" (cover id)
        String p = icon.startsWith("/") ? icon : "/" + icon;
        if (p.startsWith("/music/") && (p.endsWith("/cover") || p.endsWith("/cover.jpg") || p.endsWith("/cover.png"))) {
            return p.substring(0, p.lastIndexOf('/') + 1) + "cover_" + sz + "_p.png";
        }
        if (p.startsWith("/imageproxy/") && (p.endsWith("/image.png") || p.endsWith("/image.jpg"))) {
            return p.substring(0, p.lastIndexOf('/') + 1) + "image_" + sz + "_p.png";
        }
        return p;
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static String urlEncode(String s) {
        StringBuffer b = new StringBuffer();
        String hex = "0123456789ABCDEF";
        byte[] bytes;
        try { bytes = s.getBytes("UTF-8"); } catch (Exception e) { bytes = s.getBytes(); }
        for (int k = 0; k < bytes.length; k++) {
            int c = bytes[k] & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-_.~".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(hex.charAt(c >> 4)).append(hex.charAt(c & 15));
            }
        }
        return b.toString();
    }

    static Vector items(Hashtable r) {
        Vector out = new Vector();
        Vector loop = Json.arr(r, "item_loop");
        for (int k = 0; loop != null && k < loop.size(); k++) {
            Object o = loop.elementAt(k);
            if (o instanceof Hashtable) {
                Item it = Item.fromJive((Hashtable) o);
                if (it.title.length() > 0) out.addElement(it);
            }
        }
        return out;
    }
}
