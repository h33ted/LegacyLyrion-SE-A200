package ll;

/**
 * Docked mode (Aino): when the phone lies sideways and still for a while (the wait is set in
 * Settings, 2 s by default), as it does in its desk stand, the app asks "Docked?" or, if the
 * answer was "Always", docks straight away. Phones that report the charger to Java must also be
 * charging. Docked, it shows the landscape DockScreen and keeps the display lit through the Nokia
 * UI API (NokiaLight), which the Aino carries. MIDP's own Display.flashBacklight is not used: on
 * Sony Ericsson phones it blinks the screen. Standing the phone upright undocks after the same wait; unplugging (where the charger
 * is reported) undocks at once.
 *
 * Also usable by hand from More > Docked mode; a manual dock lasts until Undock.
 */
final class Docking implements Runnable {
    /** Settings values, in the order of the Settings choices. */
    static final int ASK = 0, ALWAYS = 1, OFF = 2;
    static final int NIGHT_LIGHT = 10;                       // percent backlight in night mode
    static final int NIGHT_FROM = 23, NIGHT_TO = 7;          // hours

    /** Backlight control (NokiaLight), loaded by name. */
    interface Light {
        /** Light num (0 display, 1 keypad on most phones) at 0..100 percent. */
        void set(int num, int percent);
    }

    private final LegacyLyrion app;
    volatile boolean docked;
    private volatile boolean charging, chargerKnown;
    private boolean manual;          // docked from the menu: stays until Undock
    private boolean accepted;        // "Yes": dock again without asking, until unplugged / picked up
    private boolean declined;        // "Not now" or Undock: not again until unplugged / picked up
    private boolean asking;
    private long upSince = -1;
    private DockScreen screen;
    private Light light;             // NokiaLight, if the phone has DeviceControl
    private long litAt;
    private int litLevel = -1, keypadLevel = -1;
    volatile long nextAlarm;         // the controlled player's next Lyrion alarm, ms since 1970; 0 if none
    private volatile long alarmsAt;  // when the alarms were last read
    private boolean woke;            // the alarm went off: no night mode until 07:00
    volatile boolean night;          // red-on-black, dimmed (DockScreen reads it)
    private boolean nightByHand;     // switched from the menu: stays until switched back or undocked
    private int nightAnswer;         // tonight's answer to "Night mode?": 0 not asked, 1 yes, 2 no
    private boolean litLogged;

    Docking(LegacyLyrion app) {
        this.app = app;
    }

    /** The Aino (U10i / U10a), or any phone whose .jad has "LL-Dock: on" (for the emulator). */
    static boolean supported(LegacyLyrion app) {
        String p = System.getProperty("microedition.platform");
        if (p != null && p.startsWith("SonyEricssonU10")) return true;
        String jad = app.getAppProperty("LL-Dock");
        return jad != null && jad.trim().equalsIgnoreCase("on");
    }

    void start() {
        probe();
        try {                                    // Nokia UI API: the Aino carries it; steady light, no blinking
            Class.forName("com.nokia.mid.ui.DeviceControl");
            light = (Light) Class.forName("ll.NokiaLight").newInstance();
            app.log("Dock: Nokia DeviceControl present" + nokiaExtras());
        } catch (Throwable t) {
            app.log("Dock: no Nokia DeviceControl; the light follows the phone's setting");
        }
        try {
            Runnable r = (Runnable) Class.forName("ll.Charger").newInstance();
            new Thread(r).start();
        } catch (Throwable t) {
            app.log("Dock: this phone has no sensor API (" + t + ")");
        }
        new Thread(this).start();
        alarmReader();
    }

    /** Logs any battery or charger property the firmware happens to expose, for later use. */
    private void probe() {
        String[] names = {"com.sonyericsson.batterylevel", "com.sonyericsson.battery.level", "com.sonyericsson.battery",
                "com.sonyericsson.charger", "com.sonyericsson.charging", "com.sonyericsson.chargerstatus",
                "com.sonyericsson.slider", "com.sonyericsson.sliderstate", "com.sonyericsson.flipopen"};
        boolean any = false;
        for (int i = 0; i < names.length; i++) {
            try {
                String v = System.getProperty(names[i]);
                if (v != null) {
                    app.log("Dock: " + names[i] + " = " + v);
                    any = true;
                }
            } catch (Throwable ignored) {}
        }
        if (!any) app.log("Dock: no battery or charger properties");
    }

    /** From Charger, about once a second, on phones that report the charger. */
    void charger(boolean on) {
        chargerKnown = true;
        if (!on && charging) {           // unplugged: a new charge starts fresh
            accepted = false;
            declined = false;
        }
        charging = on;
    }

    /** More > Docked mode. */
    void dockNow() {
        if (docked) return;
        declined = false;
        enter(true);
    }

    public void run() {
        while (true) {
            try { Thread.sleep(250); } catch (InterruptedException e) {}
            try {
                tick();
                keepLit();
            } catch (Throwable t) {
                app.log("Dock: " + t);
            }
        }
    }

    /** Which other Nokia UI classes the phone has (for later use). */
    private static String nokiaExtras() {
        String[] n = {"com.nokia.mid.ui.DirectUtils", "com.nokia.mid.ui.DirectGraphics", "com.nokia.mid.sound.Sound"};
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < n.length; i++) {
            try {
                Class.forName(n[i]);
                b.append(", ").append(n[i].substring(n[i].lastIndexOf('.') + 1));
            } catch (Throwable ignored) {}
        }
        return b.length() == 0 ? "" : " (also" + b.toString().substring(1) + ")";
    }

    /**
     * Docked and in front: renews the backlight every few seconds, fading between levels instead of
     * jumping: full, 10% at night, rising from 1% before an alarm (sunrise). At night the keypad
     * light is switched off. Undocked, the light lapses by itself.
     */
    private void keepLit() {
        if (light == null || !docked || !app.shell.isShown()) return;
        long now = System.currentTimeMillis();
        int rise = sunrise(now);
        if (!app.keepLit && rise < 0) return;
        int target = night ? NIGHT_LIGHT : 100;
        if (rise >= 0) target = night ? Math.max(1, rise) : Math.max(target, rise);
        int level = litLevel < 0 ? target : step(litLevel, target);
        if (now - litAt < 4000 && level == litLevel) return;
        litAt = now;
        litLevel = level;
        try {
            light.set(0, level);
            int keys = night ? 0 : 100;
            if (keys != keypadLevel && (keypadLevel != -1 || keys == 0)) {
                try { light.set(1, keys); } catch (Throwable ignored) {}
                keypadLevel = keys;
            }
            if (!litLogged) {
                app.log("Dock: keeping the screen on");
                litLogged = true;
            }
        } catch (Throwable t) {
            app.log("Dock: DeviceControl failed (" + t + ")");
            light = null;
        }
    }

    /** One fade step (4 a second): at most 15 percentage points. */
    private static int step(int from, int to) {
        int d = to - from;
        if (d > 15) d = 15;
        if (d < -15) d = -15;
        return from + d;
    }

    /**
     * Backlight percent during the sunrise before the next alarm: from 1% up to full, on a curve,
     * over the minutes set in Settings; -1 outside it.
     */
    private int sunrise(long now) {
        long at = nextAlarm;
        int mins = app.sunriseMinutes;
        if (at <= 0 || mins <= 0) return -1;
        long left = at - now, span = mins * 60000L;
        if (left > span || left <= 0) return -1;
        int p = (int) ((span - left) * 100 / span);
        return Math.max(1, p * p / 100);
    }

    /** Reads the controlled player's alarms from Lyrion every 5 minutes while docked. */
    private void alarmReader() {
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) {}
                    long now = System.currentTimeMillis();
                    if (!docked || app.player.length() == 0 || now - alarmsAt < 300000) continue;
                    alarmsAt = now;
                    try {
                        long at = nextAlarmFromLyrion();
                        if (at != nextAlarm) app.log("Dock: next alarm " + (at == 0 ? "none" : clock(at)));
                        nextAlarm = at;
                        app.shell.repaint();
                    } catch (Throwable t) {
                        app.log("Dock: alarms unavailable (" + t + ")");
                    }
                }
            }
        }).start();
    }

    private long nextAlarmFromLyrion() throws Exception {
        try {
            java.util.Hashtable on = app.lms.call(app.player, new String[]{"playerpref", "alarmsEnabled", "?"});
            if ("0".equals(Json.str(on, "_p2"))) return 0;            // all alarms off for this player
        } catch (Exception ignored) {}
        java.util.Hashtable r = app.lms.call(app.player, new String[]{"alarms", "0", "20", "filter:enabled"});
        java.util.Vector v = Json.arr(r, "alarms_loop");
        if (v == null) return 0;
        java.util.Calendar c = java.util.Calendar.getInstance();
        long now = System.currentTimeMillis();
        int today = c.get(java.util.Calendar.DAY_OF_WEEK) - 1;      // Lyrion: 0 = Sunday
        int secNow = c.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + c.get(java.util.Calendar.MINUTE) * 60
                + c.get(java.util.Calendar.SECOND);
        long midnight = now - secNow * 1000L - c.get(java.util.Calendar.MILLISECOND);
        long best = 0;
        for (int i = 0; i < v.size(); i++) {
            if (!(v.elementAt(i) instanceof java.util.Hashtable)) continue;
            java.util.Hashtable a = (java.util.Hashtable) v.elementAt(i);
            if ("0".equals(Json.str(a, "enabled"))) continue;
            int t = Json.toInt(Json.str(a, "time"), -1);
            if (t < 0) continue;
            String dow = Json.str(a, "dow");
            for (int d = 0; d < 8; d++) {
                int day = (today + d) % 7;
                if (dow != null && dow.length() > 0 && dow.indexOf((char) ('0' + day)) < 0) continue;
                if (d == 0 && t <= secNow) continue;
                long at = midnight + d * 86400000L + t * 1000L;
                if (best == 0 || at < best) best = at;
                break;
            }
        }
        return best;
    }

    static String clock(long at) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTime(new java.util.Date(at));
        int m = c.get(java.util.Calendar.MINUTE);
        return c.get(java.util.Calendar.HOUR_OF_DAY) + ":" + (m < 10 ? "0" : "") + m;
    }

    private void tick() {
        Shell s = app.shell;
        long now = System.currentTimeMillis();
        long wait = app.dockDelay * 1000L;
        boolean wide = s.sideways();

        if (docked) {
            nightTick();
            if (screen != null && !s.holds(screen)) {     // left with Undock / Back
                declined = true;
                leave(false);
                return;
            }
            if (manual) return;
            if ((chargerKnown && !charging) || app.dockMode == OFF) {
                leave(true);
                return;
            }
            if (wide) upSince = -1;
            else if (upSince < 0) upSince = now;
            else if (now - upSince >= wait) leave(true);
            return;
        }

        if (!chargerKnown && !wide) {                     // picked up: the next time on the stand starts fresh
            accepted = false;
            declined = false;
        }
        boolean want = app.dockMode != OFF && (!chargerKnown || charging) && wide
                && s.stillFor() >= wait && !declined && s.isShown();
        if (asking) {
            // turned upright or unplugged while asking (moving it to answer by touch is fine)
            if (!wide || !s.isShown() || (chargerKnown && !charging)) {
                asking = false;
                app.display.callSerially(new Runnable() {
                    public void run() { app.shell.closePrompt(); }
                });
            }
            return;
        }
        if (!want) return;
        if (app.dockMode == ALWAYS || accepted) enter(false);
        else ask();
    }

    /** Between 23:00 and 07:00, docked: ask "Night mode?" once a night (or switch it on, if "Always"). */
    private void nightTick() {
        int hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        boolean nightTime = hour >= NIGHT_FROM || hour < NIGHT_TO;
        long at = nextAlarm;
        if (at > 0 && System.currentTimeMillis() >= at) {      // the alarm: day mode, full light, read the next one
            nextAlarm = 0;
            alarmsAt = 0;
            woke = true;
            app.log("Dock: alarm time");
            if (night) setNight(false, false);
        }
        if (!nightTime) {
            nightAnswer = 0;
            woke = false;
            if (night && !nightByHand) setNight(false, false);
            return;
        }
        if (night || asking || woke || app.nightMode == OFF) return;
        if (app.nightMode == ALWAYS || nightAnswer == 1) {
            setNight(true, false);
            return;
        }
        if (nightAnswer != 0 || !app.shell.isShown()) return;
        asking = true;
        app.display.callSerially(new Runnable() {
            public void run() {
                app.shell.prompt("Night mode?", new String[]{"Yes", "Not tonight", "Always at night"},
                        new Shell.Chooser() {
                            public void chose(int k) {
                                asking = false;
                                if (k == 0) {
                                    nightAnswer = 1;
                                    setNight(true, false);
                                } else if (k == 2) {
                                    nightAnswer = 1;
                                    app.nightMode = ALWAYS;
                                    app.save();
                                    setNight(true, false);
                                } else {
                                    nightAnswer = 2;        // "Not tonight" or Back
                                }
                            }
                        });
            }
        });
    }

    /** More > Night mode / Day mode on the docked screen. */
    void toggleNight() {
        setNight(!night, true);
        if (!night) nightAnswer = 2;                      // switched off by hand: don't ask again tonight
    }

    private void setNight(boolean on, boolean byHand) {
        night = on;
        nightByHand = on && byHand;
        litAt = 0;                                        // new brightness right away
        app.log("Dock: night mode " + (on ? "on" : "off"));
        app.shell.repaint();
    }

    private void ask() {
        asking = true;
        app.display.callSerially(new Runnable() {
            public void run() {
                app.shell.prompt("Docked?", new String[]{"Yes", "Not now", "Always when docked"},
                        new Shell.Chooser() {
                            public void chose(int k) {
                                asking = false;
                                if (k == 0) {
                                    accepted = true;
                                    enter(false);
                                } else if (k == 2) {
                                    app.dockMode = ALWAYS;
                                    app.save();
                                    enter(false);
                                } else {
                                    declined = true;        // "Not now" or Back
                                }
                            }
                        });
            }
        });
    }

    private void enter(final boolean byHand) {
        docked = true;
        manual = byHand;
        litAt = 0;
        alarmsAt = 0;                                    // read the alarms soon after docking
        upSince = -1;
        app.log("Dock: docked" + (byHand ? " (by hand)" : ""));
        app.display.callSerially(new Runnable() {
            public void run() {
                DockScreen d = new DockScreen(app);
                app.shell.dockRotation(true);
                app.shell.push(d);
                screen = d;                          // only once it is on the stack (tick() checks that)
            }
        });
    }

    private void leave(final boolean closeScreen) {
        docked = false;
        manual = false;
        night = false;
        nightByHand = false;
        if (light != null && litLevel != -1 && litLevel != 100) {   // don't leave the screen dimmed
            try { light.set(0, 100); } catch (Throwable ignored) {}
        }
        if (light != null && keypadLevel == 0) {                     // nor the keypad dark
            try { light.set(1, 100); } catch (Throwable ignored) {}
        }
        litLevel = -1;
        keypadLevel = -1;
        final DockScreen sc = screen;
        screen = null;
        app.log("Dock: undocked");
        app.display.callSerially(new Runnable() {
            public void run() {
                if (closeScreen && sc != null) app.shell.remove(sc);
                app.shell.dockRotation(false);
            }
        });
    }
}
