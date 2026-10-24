package ll;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

/** A Jive menu entry (Spotty or Lyrion) plus the logic Squeezebox controllers use to turn it into a command. */
final class Item {
    String title = "";
    String sub = "";
    String icon;
    String type;
    Hashtable raw;

    static Item fromJive(Hashtable o) {
        Item it = new Item();
        it.raw = o;
        String text = Json.str(o, "text");
        if (text == null) text = "";
        int nl = text.indexOf('\n');
        it.title = nl >= 0 ? text.substring(0, nl) : text;
        it.sub = stripSeconds(nl >= 0 ? text.substring(nl + 1).replace('\n', ' ') : "");
        it.icon = Json.str(o, "icon");
        if (it.icon == null) it.icon = Json.str(o, "icon-id");
        if (it.icon == null) it.icon = Json.str(Json.obj(o, "presetParams"), "icon");
        it.type = Json.str(o, "type");
        if (it.sub.startsWith("Followers")) it.sub = "Artist";
        return it;
    }

    /** YouTube Music lines end in the length in seconds ("Daft Punk - Homework - 430"); drop it. */
    static String stripSeconds(String sub) {
        int k = sub.lastIndexOf('-');
        if (k < 1 || sub.charAt(k - 1) != ' ' || k + 2 >= sub.length() || sub.length() - k - 2 > 5) return sub;
        for (int i = k + 2; i < sub.length(); i++) if (!Character.isDigit(sub.charAt(i))) return sub;
        return sub.substring(0, k - 1);
    }

    private Hashtable go() {
        Hashtable a = Json.obj(raw, "actions");
        return a == null ? null : Json.obj(a, "go");
    }

    boolean isTrack() {
        if (raw.containsKey("playControlParams") || "audio".equals(type)) return true;
        Hashtable pp = Json.obj(raw, "presetParams");
        return pp != null && "audio".equals(Json.str(pp, "favorites_type")) && go() == null;
    }

    boolean isSearch() {
        return "search".equals(type) || "text".equals(type);
    }

    String nextWindow() {
        Hashtable g = go();
        return g == null ? null : Json.str(g, "nextWindow");
    }

    /** Command for a Jive action ("go", "play", "add", "add-hold", "playControl"), or null. */
    String[] command(Hashtable base, String action, int count) {
        Hashtable act = null;
        boolean fromBase = false;
        Hashtable ia = Json.obj(raw, "actions");
        if (ia != null) act = Json.obj(ia, action);
        if (act == null && base != null) {
            Hashtable ba = Json.obj(base, "actions");
            if (ba != null) {
                act = Json.obj(ba, action);
                fromBase = act != null;
            }
        }
        if (act == null) return null;
        Vector cmd = Json.arr(act, "cmd");
        if (cmd == null || cmd.size() == 0) return null;

        Hashtable kv = new Hashtable();
        Vector order = new Vector();
        putAll(kv, order, Json.obj(act, "params"));
        if (fromBase) {
            String ip = Json.str(act, "itemsParams");
            if (ip != null) {
                Hashtable extra = Json.obj(raw, ip);
                if (extra == null) return null;
                putAll(kv, order, extra);
            }
        }
        Vector out = new Vector();
        for (int k = 0; k < cmd.size(); k++) out.addElement(cmd.elementAt(k).toString());
        String idx = (String) kv.get("_index");
        String qty = (String) kv.get("_quantity");
        if ("items".equals(cmd.elementAt(cmd.size() - 1))) {
            out.addElement(idx != null ? idx : "0");
            out.addElement(count > 0 ? String.valueOf(count) : (qty != null ? qty : "100"));
        }
        for (int k = 0; k < order.size(); k++) {
            String key = (String) order.elementAt(k);
            if (key.equals("_index") || key.equals("_quantity") || key.equals("isContextMenu")) continue;
            out.addElement(key + ":" + kv.get(key));
        }
        String[] a = new String[out.size()];
        out.copyInto(a);
        return a;
    }

    private static void putAll(Hashtable kv, Vector order, Hashtable o) {
        if (o == null) return;
        for (Enumeration e = o.keys(); e.hasMoreElements(); ) {
            String k = (String) e.nextElement();
            if (!kv.containsKey(k)) order.addElement(k);
            kv.put(k, o.get(k).toString());
        }
    }

    static String[] withInput(String[] cmd, String text) {
        if (cmd == null) return null;
        String[] out = new String[cmd.length];
        for (int k = 0; k < cmd.length; k++) {
            String t = cmd[k];
            int p = t.indexOf("__TAGGEDINPUT__");
            out[k] = p < 0 ? t : t.substring(0, p) + text + t.substring(p + "__TAGGEDINPUT__".length());
        }
        return out;
    }

    /** From a "what should I do" menu reply, the go-command of the entry with this Jive style. */
    static String[] menuChoice(Hashtable r, String style) {
        Vector loop = Json.arr(r, "item_loop");
        for (int k = 0; loop != null && k < loop.size(); k++) {
            Object o = loop.elementAt(k);
            if (!style.equals(Json.str(o, "style"))) continue;
            Hashtable go = Json.obj(Json.obj(o, "actions"), "go");
            Vector c = Json.arr(go, "cmd");
            if (c == null) return null;
            Vector out = new Vector();
            for (int i = 0; i < c.size(); i++) out.addElement(c.elementAt(i).toString());
            Hashtable p = Json.obj(go, "params");
            if (p != null) {
                for (Enumeration e = p.keys(); e.hasMoreElements(); ) {
                    String key = (String) e.nextElement();
                    out.addElement(key + ":" + p.get(key));
                }
            }
            String[] a = new String[out.size()];
            out.copyInto(a);
            return a;
        }
        return null;
    }
}
