package ll;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Hashtable;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Image;

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

    private Hashtable send(String player, String[] cmd) throws Exception {
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
            String text = new String(readAll(in), "UTF-8");
            Hashtable res = Json.obj(Json.parse(text), "result");
            if (res == null) throw new Exception("empty reply");
            return res;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }

    /** PNG artwork, resized by the server (phones of this age decode PNG reliably). */
    Image image(String path) {
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(path.startsWith("http") ? path : base() + path);
            c.setRequestProperty("Connection", "close");
            if (c.getResponseCode() != HttpConnection.HTTP_OK) return null;
            in = c.openInputStream();
            byte[] d = readAll(in);
            return Image.createImage(d, 0, d.length);
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
