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
        void level(int percent);
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
    private int litLevel = -1;
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
            app.log("Dock: Nokia DeviceControl present");
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

    /** Docked and in front: renews the backlight every few seconds. Undocked, it lapses by itself. */
    private void keepLit() {
        if (light == null || !docked || !app.keepLit || !app.shell.isShown()) return;
        long now = System.currentTimeMillis();
        int level = night ? NIGHT_LIGHT : 100;
        if (now - litAt < 4000 && level == litLevel) return;
        litAt = now;
        litLevel = level;
        try {
            light.level(level);
            if (!litLogged) {
                app.log("Dock: keeping the screen on");
                litLogged = true;
            }
        } catch (Throwable t) {
            app.log("Dock: DeviceControl failed (" + t + ")");
            light = null;
        }
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
        if (!nightTime) {
            nightAnswer = 0;
            if (night && !nightByHand) setNight(false, false);
            return;
        }
        if (night || asking || app.nightMode == OFF) return;
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
            try { light.level(100); } catch (Throwable ignored) {}
        }
        litLevel = -1;
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
