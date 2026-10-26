package ll;

import java.util.Hashtable;
import java.util.Random;
import java.util.Vector;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.ChoiceGroup;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.midlet.MIDlet;
import javax.microedition.rms.RecordStore;

/**
 * LegacyLyrion for Sony Ericsson Aino (A200 platform, Java ME MIDP 2.0 / CLDC 1.1).
 * Prototype: Spotify (via the Spotty plugin) remote for any Lyrion player, plus an
 * experimental "play on this phone" mode that streams MP3 from Lyrion into MMAPI.
 */
public final class LegacyLyrion extends MIDlet implements CommandListener {

    static final int A_SEARCH = 1, A_MIXES = 2, A_PLAYLISTS = 3, A_BROWSE = 4, A_NOW = 5, A_PLAYERS = 6,
            A_SETTINGS = 7, A_ITEM = 8, A_PLAYER_PICK = 9, A_NONE = 10, A_LOCAL = 11, A_APP = 12;

    Display display;
    Shell shell;
    final Lms lms = new Lms();

    String player = "";       // player id (MAC) being controlled
    String phoneMac = "";     // this phone's own id when it plays itself
    boolean phonePlays;
    volatile String npTitle, npArtist;

    private Hashtable appTop;           // top menu of the current plugin (Spotty or YouTube Music)
    private String appTopCmd;
    private Item searchItem;
    private Hashtable searchBase;
    private Slim slim;
    int phoneMode = Slim.MODE_CHUNKED;
    int rotMode = Shell.ROT_AUTO;
    int dockMode = Docking.ASK;
    int dockDelay = 2;                  // seconds sideways and still before docked mode
    Docking docking;                    // only on the Aino
    private ChoiceGroup fRot, fDock;
    private TextField fDockWait;
    private final StringBuffer logBuf = new StringBuffer();
    private boolean started;

    // ------------------------------------------------------------------ lifecycle

    protected void startApp() {
        if (started) {                              // back from the background (minimised)
            try {
                if (display.getCurrent() == null) display.setCurrent(shell);
                shell.repaint();
            } catch (Throwable ignored) {}
            return;
        }
        started = true;
        instance = this;
        display = Display.getDisplay(this);
        shell = new Shell(this);
        load();
        shell.setRotationMode(rotMode);
        if (Docking.supported(this)) {
            docking = new Docking(this);
            shell.setDockWanted(dockMode != Docking.OFF);
            docking.start();
        }
        if (lms.host.length() == 0) showSettings(true);
        else showHome();
        startMiniPoller();
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean unconditional) {
        stopPhone();
    }

    /**
     * Sends the app to the background, music and all (Sony Ericsson phones treat setCurrent(null)
     * as "minimise"). Reopen it from the phone's Activity menu or the Java apps list.
     */
    void minimise() {
        try {
            display.setCurrent(null);
        } catch (Throwable t) {
            shell.toast("This phone can't minimise Java apps from inside the app");
        }
    }

    void exit() {
        destroyApp(true);
        notifyDestroyed();
    }

    // ------------------------------------------------------------------ settings (RMS)

    private void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("legacylyrion", true);
            if (rs.getNumRecords() > 0) {
                String[] f = split(new String(rs.getRecord(1), "UTF-8"));
                if (f.length > 0) lms.host = f[0];
                if (f.length > 1 && f[1].length() > 0) lms.port = f[1];
                if (f.length > 2) player = f[2];
                if (f.length > 3) phoneMac = f[3];
                if (f.length > 4) phonePlays = "1".equals(f[4]);
                if (f.length > 5) phoneMode = "1".equals(f[5]) ? Slim.MODE_DIRECT : Slim.MODE_CHUNKED;
                if (f.length > 6 && f[6].length() == 1) rotMode = f[6].charAt(0) - '0';
                if (f.length > 7 && f[7].length() == 1) {
                    source = f[7].charAt(0) - '0';
                    sourceSaved = true;
                }
                if (f.length > 8 && f[8].length() == 1) dockMode = f[8].charAt(0) - '0';
                if (f.length > 9) dockDelay = seconds(f[9], dockDelay);
            }
            rs.closeRecordStore();
        } catch (Exception ignored) {}
        if (phoneMac.length() == 0) phoneMac = makeMac();
        // Optional preset in the .jad ("LL-Server: 192.168.1.20:9000"), used until changed in Settings
        String preset = getAppProperty("LL-Server");
        if (lms.host.length() == 0 && preset != null && preset.trim().length() > 0) {
            preset = preset.trim();
            int c = preset.indexOf(':');
            lms.host = c > 0 ? preset.substring(0, c) : preset;
            if (c > 0) lms.port = preset.substring(c + 1);
        }
    }

    void save() {
        try {
            String v = lms.host + "\n" + lms.port + "\n" + player + "\n" + phoneMac + "\n" + (phonePlays ? "1" : "0")
                    + "\n" + (phoneMode == Slim.MODE_DIRECT ? "1" : "0") + "\n" + rotMode + "\n" + (sourceSaved ? String.valueOf(source) : "")
                    + "\n" + dockMode + "\n" + dockDelay;
            byte[] b = v.getBytes("UTF-8");
            RecordStore rs = RecordStore.openRecordStore("legacylyrion", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Exception ignored) {}
    }

    /** Docked-mode wait from Settings or RMS: 0 to 120 seconds. */
    private static int seconds(String s, int dflt) {
        try {
            return Math.max(0, Math.min(120, Integer.parseInt(s.trim())));
        } catch (Exception e) {
            return dflt;
        }
    }

    boolean docked() {
        return docking != null && docking.docked;
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

    /** Random locally-administered MAC, kept in RMS, so this phone is its own Lyrion player. */
    private static String makeMac() {
        Random r = new Random(System.currentTimeMillis());
        StringBuffer b = new StringBuffer();
        for (int k = 0; k < 6; k++) {
            int v = r.nextInt(256);
            if (k == 0) v = (v & 0xfc) | 0x02;
            if (k > 0) b.append(':');
            String h = Integer.toHexString(v);
            if (h.length() < 2) b.append('0');
            b.append(h);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ settings form (native, so it wears the phone's theme)

    private Form settingsForm;
    private TextField fHost, fPort;
    private ChoiceGroup fPhone, fMode;
    private Form logForm;
    private final Command cLog = new Command("Diagnostics", Command.SCREEN, 3);
    private final Command cSave = new Command("Save", Command.OK, 1);
    private final Command cBack = new Command("Back", Command.BACK, 2);
    private final Command cSearch = new Command("Search", Command.OK, 1);
    private boolean firstRun;

    void showSettings(boolean first) {
        firstRun = first;
        settingsForm = new Form("LegacyLyrion settings");
        settingsForm.append(new StringItem(null, first
                ? "Enter the address of the computer running Lyrion Music Server (same Wi-Fi)."
                : "Lyrion server"));
        fHost = new TextField("Server IP", lms.host, 64, TextField.ANY);
        fPort = new TextField("Port", lms.port, 5, TextField.NUMERIC);
        fPhone = new ChoiceGroup(null, Choice.MULTIPLE, new String[]{"Play on this phone (" + phoneName() + ")"}, null);
        fPhone.setSelectedIndex(0, phonePlays);
        fMode = new ChoiceGroup("Phone playback", Choice.EXCLUSIVE,
                new String[]{"Chunked (most compatible)", "Direct stream (gapless if supported)"}, null);
        fMode.setSelectedIndex(phoneMode == Slim.MODE_DIRECT ? 1 : 0, true);
        settingsForm.append(fHost);
        settingsForm.append(fPort);
        settingsForm.append(fPhone);
        settingsForm.append(fMode);
        fRot = new ChoiceGroup("Screen rotation", Choice.EXCLUSIVE,
                new String[]{"Turn with the phone", "Turn with the phone (other way)", "Always upright"}, null);
        fRot.setSelectedIndex(rotMode >= 0 && rotMode <= 2 ? rotMode : 0, true);
        settingsForm.append(fRot);
        if (docking != null) {
            fDock = new ChoiceGroup("Docked (lying sideways and still)", Choice.EXCLUSIVE,
                    new String[]{"Ask", "Always", "Off"}, null);
            fDock.setSelectedIndex(dockMode >= 0 && dockMode <= 2 ? dockMode : Docking.ASK, true);
            fDockWait = new TextField("Wait before docking (seconds)", String.valueOf(dockDelay), 3, TextField.NUMERIC);
            settingsForm.append(fDock);
            settingsForm.append(fDockWait);
        }
        settingsForm.append(new StringItem(null, "\n" + credit()));
        settingsForm.addCommand(cSave);
        settingsForm.addCommand(cLog);
        if (!first) settingsForm.addCommand(cBack);
        settingsForm.setCommandListener(this);
        display.setCurrent(settingsForm);
    }

    private TextBox searchBox;

    /** Search typed straight into the home menu's box. */
    void searchFor(String q) {
        searchItem = null;
        runSearch(q);
    }

    /** "LegacyLyrion 0.4.7, made by h33ted", for Settings and Diagnostics. */
    String credit() {
        String v = getAppProperty("MIDlet-Version");
        return "LegacyLyrion" + (v == null ? "" : " " + v) + ", made by h33ted";
    }

    void askSearch() {
        searchBox = new TextBox(source == HomeScreen.LOCAL ? "Search your library"
                : source == HomeScreen.YOUTUBE ? "Search YouTube Music" : "Search Spotify", lastQuery == null ? "" : lastQuery, 80, TextField.ANY);
        searchBox.addCommand(cSearch);
        searchBox.addCommand(cBack);
        searchBox.setCommandListener(this);
        display.setCurrent(searchBox);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == logForm) {
            display.setCurrent(shell);
            return;
        }
        if (c == cLog) {
            showLog();
            return;
        }
        if (d == settingsForm) {
            if (c == cSave) {
                lms.host = fHost.getString().trim();
                lms.port = fPort.getString().trim().length() == 0 ? "9000" : fPort.getString().trim();
                phonePlays = fPhone.isSelected(0);
                phoneMode = fMode.getSelectedIndex() == 1 ? Slim.MODE_DIRECT : Slim.MODE_CHUNKED;
                rotMode = fRot.getSelectedIndex();
                shell.setRotationMode(rotMode);
                if (docking != null) {
                    dockMode = fDock.getSelectedIndex();
                    dockDelay = seconds(fDockWait.getString(), 2);
                    shell.setDockWanted(dockMode != Docking.OFF);
                }
                if (phonePlays) player = phoneMac;
                save();
                appTop = null;
                searchItem = null;
                if (phonePlays) startPhone();   // (re)connects with the new server / mode
                else stopPhone();
                if (firstRun) showHome();
                else display.setCurrent(shell);
            } else {
                display.setCurrent(shell);
            }
        } else if (d == searchBox) {
            display.setCurrent(shell);
            if (c == cSearch && searchBox.getString().trim().length() > 0) runSearch(searchBox.getString().trim());
        }
    }

    // ------------------------------------------------------------------ screens

    void showHome() {
        HomeScreen home = new HomeScreen(this);
        int S = HomeScreen.SPOTIFY, L = HomeScreen.LOCAL;
        home.add(S, "Resume", Style.G_RESUME, A_NOW, HomeScreen.P_TEXT, null);
        home.add(S, "Search", Style.G_SEARCH, A_SEARCH, HomeScreen.P_SEARCH, null);
        home.add(S, "Made for you", Style.G_HEART, A_MIXES, HomeScreen.P_THUMBS, "Home");
        home.add(S, "Playlists", Style.G_NEWS, A_PLAYLISTS, HomeScreen.P_THUMBS, "Playlists");
        home.add(S, "Browse", Style.G_GLOBE, A_BROWSE, HomeScreen.P_TEXT, null);
        home.add(S, "Players", Style.G_SPEAKER, A_PLAYERS, HomeScreen.P_TEXT, null);
        home.add(S, "Options", Style.G_GEAR, A_SETTINGS, HomeScreen.P_TEXT, null);

        int Y = HomeScreen.YOUTUBE;
        home.add(Y, "Resume", Style.G_RESUME, A_NOW, HomeScreen.P_TEXT, null);
        home.add(Y, "Search", Style.G_SEARCH, A_SEARCH, HomeScreen.P_SEARCH, null);
        home.add(Y, "Featured", Style.G_HEART, A_APP, HomeScreen.P_THUMBS, "Featured");
        home.add(Y, "New releases", Style.G_CLOCK, A_APP, HomeScreen.P_THUMBS, "New Releases");
        home.add(Y, "Charts", Style.G_LIST, A_APP, HomeScreen.P_THUMBS, "Charts & Trending");
        home.add(Y, "Moods and genres", Style.G_GLOBE, A_APP, HomeScreen.P_TEXT, "Moods and Genres");
        home.add(Y, "Podcasts", Style.G_NEWS, A_APP, HomeScreen.P_TEXT, "Podcasts");
        home.add(Y, "Players", Style.G_SPEAKER, A_PLAYERS, HomeScreen.P_TEXT, null);
        home.add(Y, "Options", Style.G_GEAR, A_SETTINGS, HomeScreen.P_TEXT, null);

        home.add(L, "Resume", Style.G_RESUME, A_NOW, HomeScreen.P_TEXT, null);
        home.add(L, "Search", Style.G_SEARCH, A_SEARCH, HomeScreen.P_SEARCH, null);
        home.add(L, "Recently added", Style.G_CLOCK, A_LOCAL, HomeScreen.P_THUMBS, "local:albums sort:new");
        home.add(L, "Albums", Style.G_DISC, A_LOCAL, HomeScreen.P_THUMBS, "local:albums sort:random");
        home.add(L, "Artists", Style.G_PERSON, A_LOCAL, HomeScreen.P_TEXT, "local:artists");
        home.add(L, "Genres", Style.G_LIST, A_LOCAL, HomeScreen.P_TEXT, "local:genres");
        home.add(L, "Playlists", Style.G_NEWS, A_LOCAL, HomeScreen.P_TEXT, "local:playlists");
        home.add(L, "Players", Style.G_SPEAKER, A_PLAYERS, HomeScreen.P_TEXT, null);
        home.add(L, "Options", Style.G_GEAR, A_SETTINGS, HomeScreen.P_TEXT, null);
        home.show(source);
        display.setCurrent(shell);
        shell.push(home);
        pickFirstPlayer();
        findSources(home);
        if (phonePlays) startPhone();
    }

    int source;                         // HomeScreen.SPOTIFY, LOCAL or YOUTUBE

    private boolean sourceSaved;        // false until the first-start check (or the user) picked one

    void setSource(int s) {
        if (s == source && sourceSaved) return;
        source = s;
        sourceSaved = true;
        searchItem = null;
        save();
    }

    /**
     * Asks Lyrion which music plugins it has (one "apps" request) and shows only those on the
     * home rail. On the first start it also picks the source: no plugin means the library, one
     * plugin means that one, both means Spotify first with all three on the rail. If the server
     * can't be asked, everything stays as it is and the check runs again next start.
     */
    private void findSources(final HomeScreen home) {
        bg(new Runnable() {
            public void run() {
                Hashtable r;
                try {
                    r = lms.call("", new String[]{"apps", "0", "100"});
                } catch (Exception e) {
                    log("Sources: can't ask the server (" + e.getMessage() + ")");
                    return;
                }
                if (r == null) return;
                Vector loop = Json.arr(r, "appss_loop");      // absent when the server has no apps at all
                boolean spotty = false, youtube = false;
                for (int k = 0; loop != null && k < loop.size(); k++) {
                    String cmd = Json.str(loop.elementAt(k), "cmd");
                    if ("spotty".equals(cmd)) spotty = true;
                    if ("youtubemusic".equals(cmd)) youtube = true;
                }
                log("Sources: Spotify " + (spotty ? "yes" : "no") + ", YouTube Music " + (youtube ? "yes" : "no"));
                boolean first = !sourceSaved;
                home.setAvailable(spotty, youtube);
                if (!first) return;
                if (spotty && youtube) {
                    home.setSection(HomeScreen.SPOTIFY);
                    shell.toast("Found Spotify and YouTube Music");
                } else if (spotty) {
                    home.setSection(HomeScreen.SPOTIFY);
                    shell.toast("Found Spotify");
                } else if (youtube) {
                    home.setSection(HomeScreen.YOUTUBE);
                    shell.toast("Found YouTube Music");
                } else {
                    home.setSection(HomeScreen.LOCAL);
                    shell.toast("No Spotify or YouTube Music plugin found, showing your library");
                }
            }
        });
    }

    /** Lyrion command of the plugin behind the current source. */
    private String appCmd() {
        return source == HomeScreen.YOUTUBE ? "youtubemusic" : "spotty";
    }

    /** Lyrion's own library menus ("albums sort:new", "artists"...), as Jive items like Spotty's. */
    private String[] localCmd(String spec, int count) {
        Vector v = new Vector();
        v.addElement("browselibrary");
        v.addElement("items");
        v.addElement("0");
        v.addElement(String.valueOf(count));
        int sp = spec.indexOf(' ');
        v.addElement("mode:" + (sp < 0 ? spec : spec.substring(0, sp)));
        if (sp > 0) v.addElement(spec.substring(sp + 1));
        v.addElement("menu:1");
        String[] a = new String[v.size()];
        v.copyInto(a);
        return a;
    }

    Vector peekLocal(String spec, int n) throws Exception {
        return Lms.items(lms.call(player, localCmd(spec, n)));
    }

    /** Search the server's own library: artists, albums and songs, each playable or browsable. */
    private void runLocalSearch(final String q) {
        final ListScreen s = new ListScreen(this, "Library: " + q);
        s.status = "Searching...";
        shell.push(s);
        bg(new Runnable() {
            public void run() {
                try {
                    Hashtable r = lms.call(player, new String[]{"search", "0", "40", "term:" + q, "extended:1"});
                    Vector rows = new Vector();
                    addHits(rows, Json.arr(r, "contributors_loop"), "contributor_id", "contributor", "Artist", "artist_id", "albums");
                    addHits(rows, Json.arr(r, "albums_loop"), "album_id", "album", "Album", "album_id", "tracks");
                    addHits(rows, Json.arr(r, "tracks_loop"), "track_id", "track", "Song", "track_id", null);
                    s.setRows(rows, null, "Nothing found in your library");
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    /**
     * Turns plain CLI search hits into Jive-style items, so they open, play and queue through the
     * same code as everything else.
     */
    private void addHits(Vector rows, Vector loop, String idKey, String nameKey, String kind, String param, String opens) {
        for (int k = 0; loop != null && k < loop.size(); k++) {
            Object o = loop.elementAt(k);
            String id = Json.str(o, idKey), name = Json.str(o, nameKey);
            if (id == null || name == null) continue;
            Hashtable raw = new Hashtable();
            raw.put("text", name + "\n" + kind);
            Hashtable actions = new Hashtable();
            if (opens != null) {
                actions.put("go", action(new String[]{"browselibrary", "items"}, "mode", opens, param, id, true));
                raw.put("type", "playlist");
            } else {
                raw.put("type", "audio");
            }
            actions.put("play", action(new String[]{"playlistcontrol"}, "cmd", "load", param, id, false));
            actions.put("add", action(new String[]{"playlistcontrol"}, "cmd", "add", param, id, false));
            actions.put("add-hold", action(new String[]{"playlistcontrol"}, "cmd", "insert", param, id, false));
            raw.put("actions", actions);
            Item it = Item.fromJive(raw);
            rows.addElement(new ListScreen.Row(it.title, it.sub, Style.G_NOTE, A_ITEM, it));
        }
    }

    private static Hashtable action(String[] cmd, String k1, String v1, String k2, String v2, boolean menu) {
        Vector c = new Vector();
        for (int i = 0; i < cmd.length; i++) c.addElement(cmd[i]);
        Hashtable params = new Hashtable();
        params.put(k1, v1);
        params.put(k2, v2);
        if (menu) params.put("menu", "1");
        Hashtable a = new Hashtable();
        a.put("cmd", c);
        a.put("params", params);
        return a;
    }

    String lastQuery;
    private String playerName;

    /** Name of the player being controlled, for the home menu. */
    String playerLabel() {
        if (player.length() == 0) return "none chosen";
        if (player.equalsIgnoreCase(phoneMac)) return "this phone";
        return playerName != null ? playerName : player;
    }

    /** First few entries of a top-level Spotty menu ("Home", "Playlists"), for artwork previews. */
    Vector peek(String spottyTitle, int n) throws Exception {
        Item it = topItem(spottyTitle);
        String[] cmd = it == null ? null : it.command(Json.obj(appTop, "base"), "go", n);
        if (cmd == null) return new Vector();
        Hashtable r = lms.call(player, cmd);
        Vector v = Lms.items(r);
        // a folder of folders (YouTube Music's "Featured" holds "Today's biggest hits"...): look one level down
        boolean art = false;
        for (int k = 0; k < v.size(); k++) if (((Item) v.elementAt(k)).icon != null) art = true;
        if (!art && v.size() > 0) {
            String[] sub = ((Item) v.elementAt(0)).command(Json.obj(r, "base"), "go", n);
            if (sub != null) v = Lms.items(lms.call(player, sub));
        }
        return v;
    }

    void openNowPlaying() {
        if (shell.top() instanceof NowPlayingScreen) return;
        shell.push(new NowPlayingScreen(this));
    }

    void openPlayers() {
        final ListScreen s = new ListScreen(this, "Players");
        s.centreLabel = "Control";
        s.status = "Loading...";
        shell.push(s);
        bg(new Runnable() {
            public void run() {
                try {
                    Hashtable r = lms.call("", new String[]{"players", "0", "50"});
                    Vector loop = Json.arr(r, "players_loop");
                    Vector v = new Vector();
                    for (int k = 0; loop != null && k < loop.size(); k++) {
                        Object o = loop.elementAt(k);
                        String id = Json.str(o, "playerid");
                        String name = Json.str(o, "name");
                        if (id == null) continue;
                        Item it = new Item();
                        it.title = id; // carries the id
                        String sub = (id.equalsIgnoreCase(player) ? "Selected" : "") + (id.equalsIgnoreCase(phoneMac) ? " (this phone)" : "");
                        v.addElement(new ListScreen.Row(name == null ? id : name, sub.trim(), Style.G_PERSON, A_PLAYER_PICK, it));
                    }
                    s.setRows(v, null, "No players are connected to Lyrion");
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    /** Picks a player if none is chosen yet, and looks up the chosen one's name for the home menu. */
    private void pickFirstPlayer() {
        bg(new Runnable() {
            public void run() {
                try {
                    Hashtable r = lms.call("", new String[]{"players", "0", "50"});
                    Vector loop = Json.arr(r, "players_loop");
                    if (loop == null || loop.size() == 0) return;
                    if (player.length() == 0) {
                        player = Json.str(loop.elementAt(0), "playerid");
                        save();
                    }
                    for (int k = 0; k < loop.size(); k++) {
                        if (player.equalsIgnoreCase(Json.str(loop.elementAt(k), "playerid"))) {
                            playerName = Json.str(loop.elementAt(k), "name");
                        }
                    }
                    shell.repaint();
                } catch (Exception ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------ actions

    void activate(ListScreen from, ListScreen.Row r) {
        switch (r.action) {
            case A_SEARCH: askSearch(); break;
            case A_MIXES: openTop("Home", "Made for you"); break;
            case A_PLAYLISTS: openTop("Playlists", "Your playlists"); break;
            case A_BROWSE: openBrowse(); break;
            case A_NOW: openNowPlaying(); break;
            case A_PLAYERS: openPlayers(); break;
            case A_LOCAL: browse(r.title, localCmd(r.item.title.substring(6), 100)); break;
            case A_APP: openTop(r.item.title, r.title); break;
            case A_SETTINGS: showSettings(false); break;
            case A_PLAYER_PICK:
                player = r.item.title;
                playerName = r.title;
                save();
                shell.toast("Controlling " + r.title);
                shell.back();
                break;
            case A_ITEM: openItem(from, r.item); break;
            default: break;
        }
    }

    String[] optionsFor(ListScreen s, ListScreen.Row r) {
        if (r != null && r.item != null && r.action == A_ITEM && (r.item.isTrack() || "playlist".equals(r.item.type))) {
            return new String[]{"Play now", "Play next", "Add to queue", "Now playing"};
        }
        return docking != null ? new String[]{"Now playing", "Docked mode", "Players", "Settings", "Diagnostics"}
                : new String[]{"Now playing", "Players", "Settings", "Diagnostics"};
    }

    void option(ListScreen s, ListScreen.Row r, int i) {
        String[] o = optionsFor(s, r);
        String what = o[i];
        if (what.equals("Now playing")) openNowPlaying();
        else if (what.equals("Players")) openPlayers();
        else if (what.equals("Docked mode")) docking.dockNow();
        else if (what.equals("Settings")) showSettings(false);
        else if (what.equals("Diagnostics")) showLog();
        else {
            String act = what.equals("Play now") ? "play" : what.equals("Play next") ? "add-hold" : "add";
            exec(r.item.command(s.base, act, 0), what.equals("Play now") ? "Playing " + r.item.title : "Queued " + r.item.title);
        }
    }

    private void openItem(ListScreen from, final Item it) {
        if (it.isTrack()) {
            playTrack(from, it);
            return;
        }
        String nw = it.nextWindow();
        if ("parent".equals(nw) || "grandparent".equals(nw) || "refresh".equals(nw) || "nowPlaying".equals(nw)) {
            exec(it.command(from.base, "go", 0), it.title);
            return;
        }
        if (it.isSearch()) {
            searchItem = it;
            searchBase = from.base;
            askSearch();
            return;
        }
        final String[] cmd = it.command(from.base, "go", 100);
        if (cmd == null) {
            exec(it.command(from.base, "play", 0), "Playing " + it.title);
            return;
        }
        browse(it.title, cmd);
    }

    /** Tap on a song: plays it with the rest of the list after it (Lyrion asks when the queue isn't empty). */
    private void playTrack(final ListScreen from, final Item it) {
        final String[] cmd = it.command(from.base, "playControl", 0);
        if (cmd == null) {
            exec(it.command(from.base, "play", 0), "Playing " + it.title);
            return;
        }
        shell.toast("Playing " + it.title);
        bg(new Runnable() {
            public void run() {
                try {
                    Hashtable r = lms.call(player, cmd);
                    String[] choice = Item.menuChoice(r, "item_playall");
                    if (choice == null) choice = Item.menuChoice(r, "item_play");
                    if (choice != null) lms.call(player, choice);
                } catch (Exception e) {
                    failed(cmd, e);
                }
            }
        });
    }

    /** A command that didn't work: shown, and written to Diagnostics with the command itself. */
    private void failed(String[] cmd, Exception e) {
        StringBuffer b = new StringBuffer();
        for (int k = 0; cmd != null && k < cmd.length; k++) b.append(k > 0 ? " " : "").append(cmd[k]);
        log("Failed (" + player + ": " + b + "): " + e);
        shell.toast("Failed: " + e.getMessage());
    }

        void exec(final String[] cmd, final String done) {
        if (cmd == null) {
            shell.toast("Not available");
            return;
        }
        bg(new Runnable() {
            public void run() {
                try {
                    lms.call(player, cmd);
                    if (done != null) shell.toast(done);
                } catch (Exception e) {
                    failed(cmd, e);
                }
            }
        });
    }

    /** Opens a list screen and fills it from a Jive "items" command. */
    void browse(String title, final String[] cmd) {
        final ListScreen s = new ListScreen(this, title);
        s.status = "Loading...";
        shell.push(s);
        bg(new Runnable() {
            public void run() {
                try {
                    Hashtable r = lms.call(player, cmd);
                    fill(s, r);
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    private void fill(ListScreen s, Hashtable r) {
        Vector items = Lms.items(r);
        Vector rows = new Vector();
        for (int k = 0; k < items.size(); k++) {
            Item it = (Item) items.elementAt(k);
            int g = it.isTrack() ? Style.G_NOTE : "Artist".equals(it.sub) ? Style.G_PERSON
                    : "playlist".equals(it.type) ? Style.G_DISC : Style.G_FOLDER;
            rows.addElement(new ListScreen.Row(it.title, it.sub, g, A_ITEM, it));
        }
        s.setRows(rows, Json.obj(r, "base"), "Nothing here");
    }

    // ------------------------------------------------------------------ plugins (Spotty, YouTube Music)

    /** The current plugin's top menu, loaded once ("spotty items 0 100 menu:spotty" and the like). */
    private Hashtable spottyTop() throws Exception {
        String app = appCmd();
        if (appTop == null || !app.equals(appTopCmd)) {
            Hashtable r = lms.call(player, new String[]{app, "items", "0", "100", "menu:" + app});
            if (Json.arr(r, "item_loop") == null) {
                throw new Exception(source == HomeScreen.YOUTUBE ? "YouTube Music plugin is not installed"
                        : "Spotty is not installed or not signed in");
            }
            appTop = r;
            appTopCmd = app;
            searchItem = null;
        }
        return appTop;
    }

    private Item topItem(String title) throws Exception {
        Vector v = Lms.items(spottyTop());
        for (int k = 0; k < v.size(); k++) {
            Item it = (Item) v.elementAt(k);
            if (it.title.equalsIgnoreCase(title)) return it;
        }
        return null;
    }

    private void openTop(final String spottyTitle, String screenTitle) {
        final ListScreen s = new ListScreen(this, screenTitle);
        s.status = "Loading...";
        shell.push(s);
        bg(new Runnable() {
            public void run() {
                try {
                    Item it = topItem(spottyTitle);
                    String[] cmd = it == null ? null : it.command(Json.obj(appTop, "base"), "go", 100);
                    if (cmd == null) throw new Exception("not found in this music source");
                    fill(s, lms.call(player, cmd));
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    private void openBrowse() {
        final ListScreen s = new ListScreen(this, "Spotify");
        s.status = "Loading...";
        shell.push(s);
        bg(new Runnable() {
            public void run() {
                try {
                    fill(s, spottyTop());
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    private void runSearch(final String q) {
        lastQuery = q;
        if (source == HomeScreen.LOCAL && searchItem == null) {
            runLocalSearch(q);
            return;
        }
        final ListScreen s = new ListScreen(this, "Search: " + q);
        s.status = "Searching...";
        shell.push(s);
        final Item given = searchItem;
        final Hashtable givenBase = searchBase;
        searchItem = null;
        bg(new Runnable() {
            public void run() {
                try {
                    Item si = given;
                    Hashtable sb = givenBase;
                    Item top = si == null ? topItem("Search") : null;
                    if (top != null && top.isSearch()) {   // a search field right in the top menu
                        si = top;
                        sb = Json.obj(appTop, "base");
                    }
                    if (si == null) { // the plugin's search entry: Search > (type=search)
                        String[] c = top == null ? null : top.command(Json.obj(appTop, "base"), "go", 20);
                        if (c == null) throw new Exception("search not found in this music source");
                        Hashtable r = lms.call(player, c);
                        Vector v = Lms.items(r);
                        for (int k = 0; k < v.size() && si == null; k++) {
                            if (((Item) v.elementAt(k)).isSearch()) si = (Item) v.elementAt(k);
                        }
                        sb = Json.obj(r, "base");
                    }
                    String[] cmd = si == null ? null : Item.withInput(si.command(sb, "go", 60), q);
                    if (cmd == null) throw new Exception("search not found in this music source");
                    fill(s, lms.call(player, cmd));
                } catch (Exception e) {
                    s.status = "Error: " + e.getMessage();
                }
                shell.repaint();
            }
        });
    }

    // ------------------------------------------------------------------ now playing strip

    void setNowPlaying(String title, String artist) {
        npTitle = title;
        npArtist = artist;
    }

    private void startMiniPoller() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(8000); } catch (InterruptedException e) {}
                    if (lms.host.length() == 0 || player.length() == 0 || shell.top() instanceof NowPlayingScreen) continue;
                    try {
                        Hashtable r = lms.call(player, new String[]{"status", "-", "1", "tags:a"});
                        Vector loop = Json.arr(r, "playlist_loop");
                        if (loop != null && loop.size() > 0) {
                            setNowPlaying(Json.str(loop.elementAt(0), "title"), Json.str(loop.elementAt(0), "artist"));
                        } else {
                            npTitle = null;
                        }
                        shell.repaint();
                    } catch (Exception ignored) {}
                }
            }
        });
        t.start();
    }

    // ------------------------------------------------------------------ this phone as a player (SlimProto)

    /** "Sony Ericsson - U10i", from microedition.platform ("SonyEricssonU10i/R1BA..."). */
    static String phoneName() {
        String p = System.getProperty("microedition.platform");
        if (p == null || p.length() == 0) return "Java phone";
        int slash = p.indexOf('/');
        if (slash > 0) p = p.substring(0, slash);
        if (p.startsWith("SonyEricsson")) return "Sony Ericsson - " + p.substring(12);
        if (p.startsWith("Nokia")) return "Nokia - " + p.substring(5);
        return "Java - " + p;
    }

    void startPhone() {
        stopPhone();
        player = phoneMac;
        save();
        slim = new Slim(this, lms.host, phoneMac, phoneName(), phoneMode);
        slim.start();
    }

    void stopPhone() {
        Slim s = slim;
        slim = null;
        if (s != null) s.stop();
    }

    // ------------------------------------------------------------------ diagnostics

    void log(String line) {
        synchronized (logBuf) {
            long t = (System.currentTimeMillis() / 1000) % 86400;
            String ts = (t / 3600) + ":" + two((t / 60) % 60) + ":" + two(t % 60) + " ";
            logBuf.insert(0, ts + line + "\n");      // newest first
            if (logBuf.length() > 6000) logBuf.setLength(6000);
        }
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /** Full-text error on a native alert (readable, unlike a one-line toast), and in the log. */
    void error(String what, Throwable t) {
        String msg = what + ": " + (t.getMessage() != null ? t.getMessage() : t.toString());
        log(msg);
        try {
            Alert a = new Alert("LegacyLyrion", msg + "\n\n(Settings > Diagnostics has the full log.)", null, AlertType.ERROR);
            a.setTimeout(Alert.FOREVER);
            display.setCurrent(a, shell);
        } catch (Throwable ignored) {}
    }

    void showLog() {
        logForm = new Form("Diagnostics");
        String text;
        synchronized (logBuf) {
            text = logBuf.length() == 0 ? "(empty)" : logBuf.toString();
        }
        logForm.append(new StringItem(credit() + "\n", null));
        logForm.append(new StringItem("Player: " + phoneName() + " / " + phoneMac + "\n", text));
        logForm.addCommand(cBack);
        logForm.setCommandListener(this);
        display.setCurrent(logForm);
    }

    // ------------------------------------------------------------------ util

    static void bg(final Runnable r) {
        new Thread(new Runnable() {
            public void run() {
                try { r.run(); } catch (Throwable t) { if (instance != null) instance.fault("background task", t); }
            }
        }).start();
    }

    private static LegacyLyrion instance;
    private String lastFault;

    /**
     * Last-resort guard for code the phone calls into (painting, keys, touch, player events,
     * threads): an uncaught exception there can take down the whole Java VM on these phones.
     * Logs where it happened and shows it, once per distinct fault.
     */
    void fault(String where, Throwable t) {
        String msg = "Internal error (" + where + "): " + t;
        if (msg.equals(lastFault)) return;
        lastFault = msg;
        log(msg);
        try { shell.toast(msg + " - please report this"); } catch (Throwable ignored) {}
    }
}
