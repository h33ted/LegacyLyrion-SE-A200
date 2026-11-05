package ll;

import java.io.InputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.HttpConnection;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;

/**
 * Updates through the LegacyLyrion Updates plugin on the Lyrion server (plain HTTP, so it works
 * where these phones can no longer reach GitHub). Reads legacylyrion/update/<channel>.txt (j2me for
 * the A200 build, aino for the Aino build; see Build.CHANNEL), compares its
 * versionCode with this build's (MIDlet-Version packed as MAJOR*10000 + MINOR*100 + PATCH, the
 * plugin's own convention), and offers the update; "Update" hands the generated JAD to the phone's
 * installer and exits. Sony Ericsson keeps the settings (RMS) because MIDlet-Name and -Vendor stay.
 */
final class Updater implements CommandListener {
    private final LegacyLyrion app;
    private Form form;
    private String jad;
    private int offered;
    private final Command cUpdate = new Command("Update", Command.OK, 1);
    private final Command cLater = new Command("Later", Command.BACK, 2);
    private final Command cSkip = new Command("Skip this version", Command.SCREEN, 3);

    Updater(LegacyLyrion app) {
        this.app = app;
    }

    /** This build's versionCode. */
    int myCode() {
        return code(app.getAppProperty("MIDlet-Version"));
    }

    /** "1.4.2" -> 10402; stops at the first part that isn't a number ("0.9.0-beta" -> 900). */
    static int code(String v) {
        if (v == null) return 0;
        int[] p = new int[3];
        int k = 0, i = 0;
        while (k < 3 && i < v.length()) {
            int n = 0, start = i;
            while (i < v.length() && Character.isDigit(v.charAt(i))) n = n * 10 + (v.charAt(i++) - '0');
            if (i == start) break;
            p[k++] = n;
            if (i < v.length() && v.charAt(i) == '.') i++;
            else break;
        }
        return p[0] * 10000 + p[1] * 100 + p[2];
    }

    /** Looks for an update in the background; manual: also says "up to date" or why it couldn't check. */
    void check(final boolean manual) {
        if (app.lms.host.length() == 0) return;
        if (manual) app.shell.toast("Checking for updates...");
        LegacyLyrion.bg(new Runnable() {
            public void run() {
                try {
                    String[] f = fetch(app.lms.base() + "/legacylyrion/update/" + Build.CHANNEL + ".txt");
                    if (f == null) {
                        app.log("Update: the server has no LegacyLyrion Updates plugin");
                        if (manual) app.shell.toast("The Lyrion server has no LegacyLyrion Updates plugin");
                        return;
                    }
                    String version = f[0], url = f[2], notes = f[3];
                    int code = Json.toInt(f[1], 0);
                    if (version == null || version.length() == 0 || version.equals("null") || code <= 0) {
                        if (manual) app.shell.toast("No update published on the server");
                        return;
                    }
                    app.log("Update: server has " + version + " (" + code + "), this is " + myCode());
                    if (code <= myCode()) {
                        if (manual) app.shell.toast("Up to date (" + app.getAppProperty("MIDlet-Version") + ")");
                        return;
                    }
                    if (!manual && code == app.skipUpdate) return;
                    if (url == null || url.length() == 0) {
                        if (manual) app.shell.toast("The update has no install link");
                        return;
                    }
                    offer(version, code, url, notes);
                } catch (Exception e) {
                    app.log("Update: " + e);
                    if (manual) app.shell.toast("Couldn't check: " + e.getMessage());
                }
            }
        });
    }

    /** {version, code, jad, notes} from the .txt endpoint, or null if the plugin isn't there (404). */
    private String[] fetch(String url) throws Exception {
        HttpConnection c = null;
        InputStream in = null;
        try {
            c = (HttpConnection) Connector.open(url);
            c.setRequestProperty("Connection", "close");   // a new connection each time (see Lms)
            int rc = c.getResponseCode();
            if (rc == HttpConnection.HTTP_NOT_FOUND) return null;
            if (rc != HttpConnection.HTTP_OK) throw new Exception("HTTP " + rc);
            in = c.openInputStream();
            String s = new String(Lms.readAll(in), "UTF-8");
            String[] out = new String[4];
            int st = 0;
            while (st < s.length()) {
                int nl = s.indexOf('\n', st);
                String line = s.substring(st, nl < 0 ? s.length() : nl);
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(0, eq), v = line.substring(eq + 1).trim();
                    if (k.equals("notes")) {                // last, and may span lines
                        out[3] = s.substring(st + eq + 1).trim();
                        break;
                    }
                    if (k.equals("version")) out[0] = v;
                    else if (k.equals("code")) out[1] = v;
                    else if (k.equals("jad")) out[2] = v;
                }
                if (nl < 0) break;
                st = nl + 1;
            }
            return out;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (c != null) c.close(); } catch (Exception ignored) {}
        }
    }

    private void offer(final String version, int code, String url, final String notes) {
        jad = url;
        offered = code;
        app.display.callSerially(new Runnable() {
            public void run() {
                form = new Form("LegacyLyrion " + version);
                form.append(new StringItem(null, "An update is available on your Lyrion server.\n"
                        + "Installed: " + app.getAppProperty("MIDlet-Version") + "\n"));
                if (notes != null && notes.length() > 0) form.append(new StringItem("What's new", notes));
                form.append(new StringItem(null, "\nYour settings are kept."));
                form.addCommand(cUpdate);
                form.addCommand(cLater);
                form.addCommand(cSkip);
                form.setCommandListener(Updater.this);
                app.display.setCurrent(form);
            }
        });
    }

    public void commandAction(Command c, Displayable d) {
        if (c == cUpdate) {
            app.log("Update: installing " + jad);
            try {
                app.platformRequest(jad);                    // the phone's installer takes it from here
            } catch (Exception e) {
                app.log("Update: " + e);
                app.display.setCurrent(app.shell);
                app.shell.toast("Couldn't start the install: " + e.getMessage());
                return;
            }
            app.exit();
            return;
        }
        if (c == cSkip) {
            app.skipUpdate = offered;
            app.save();
        }
        app.display.setCurrent(app.shell);
    }
}
