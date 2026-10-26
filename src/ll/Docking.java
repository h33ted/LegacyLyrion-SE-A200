package ll;

/**
 * Docked mode (Aino): when the phone lies sideways and still for a while (the wait is set in
 * Settings, 2 s by default), as it does in its desk stand, the app asks "Docked?" or, if the
 * answer was "Always", docks straight away. Phones that report the charger to Java must also be
 * charging. Docked, it shows the landscape DockScreen and keeps the screen on, the way chosen in
 * Settings. Standing the phone upright undocks after the same wait; unplugging (where the charger
 * is reported) undocks at once.
 *
 * Also usable by hand from More > Docked mode; a manual dock lasts until Undock.
 */
final class Docking implements Runnable {
    /** Settings values, in the order of the Settings choices. */
    static final int ASK = 0, ALWAYS = 1, OFF = 2;
    static final int AWAKE_STEADY = 0, AWAKE_OFTEN = 1, AWAKE_OFF = 2;

    private static final int STEADY_MS = 600000, STEADY_EVERY = 540000;   // one 10-minute request, renewed every 9
    private static final int OFTEN_MS = 2000, OFTEN_EVERY = 700;          // short requests, renewed before they lapse

    private final LegacyLyrion app;
    volatile boolean docked;
    private volatile boolean charging, chargerKnown;
    private boolean manual;          // docked from the menu: stays until Undock
    private boolean accepted;        // "Yes": dock again without asking, until unplugged / picked up
    private boolean declined;        // "Not now" or Undock: not again until unplugged / picked up
    private boolean asking;
    private long upSince = -1;
    private DockScreen screen;
    private boolean lit, litLogged;

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
        long lastLight = 0;
        while (true) {
            try { Thread.sleep(250); } catch (InterruptedException e) {}
            try {
                tick();
                if (light(System.currentTimeMillis() - lastLight)) lastLight = System.currentTimeMillis();
            } catch (Throwable t) {
                app.log("Dock: " + t);
            }
        }
    }

    /** Keeps the backlight on while docked and in front, the way chosen in Settings; true if it asked now. */
    private boolean light(long sinceLast) {
        int mode = app.keepAwake;
        boolean want = docked && mode != AWAKE_OFF && app.shell.isShown();
        if (!want) {
            if (lit) {
                lit = false;
                try { app.display.flashBacklight(0); } catch (Throwable ignored) {}   // back to the phone's own timing
            }
            return false;
        }
        long every = mode == AWAKE_OFTEN ? OFTEN_EVERY : STEADY_EVERY;
        if (lit && sinceLast < every) return false;
        boolean ok = app.display.flashBacklight(mode == AWAKE_OFTEN ? OFTEN_MS : STEADY_MS);
        lit = true;
        if (!litLogged) {
            app.log("Dock: keep screen on (" + (mode == AWAKE_OFTEN ? "refresh" : "steady") + ") "
                    + (ok ? "accepted" : "not supported by this phone"));
            litLogged = true;
        }
        return true;
    }

    private void tick() {
        Shell s = app.shell;
        long now = System.currentTimeMillis();
        long wait = app.dockDelay * 1000L;
        boolean wide = s.sideways();

        if (docked) {
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

    private void ask() {
        asking = true;
        app.display.callSerially(new Runnable() {
            public void run() {
                app.shell.prompt("Docked?", new String[]{"Yes, keep the screen on", "Not now", "Always when docked"},
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
