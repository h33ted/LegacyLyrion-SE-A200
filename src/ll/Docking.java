package ll;

/**
 * Docked mode (Aino): when the phone is charging and lying sideways for a while (the wait is set
 * in Settings, 2 s by default), the app asks "Docked?" or, if the answer was "Always", docks
 * straight away. Docked, it shows the landscape DockScreen and keeps the backlight on. Unplugging
 * undocks at once; turning the phone upright undocks after the same wait.
 *
 * Also usable by hand from More > Docked mode, e.g. in the emulator or where the phone doesn't
 * report the charger. A manual dock lasts until Back.
 */
final class Docking implements Runnable {
    /** Settings values, in the order of the Settings choice. */
    static final int ASK = 0, ALWAYS = 1, OFF = 2;

    private final LegacyLyrion app;
    volatile boolean docked;
    private volatile boolean charging;
    private boolean manual;          // docked from the menu: stays until Back
    private boolean accepted;        // "Yes" this charge: dock again without asking
    private boolean declined;        // "Not now" or Back: leave it until the charger is unplugged
    private boolean asking;
    private long since = -1, upSince = -1;
    private DockScreen screen;
    private boolean flashLogged;

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
        try {
            Runnable r = (Runnable) Class.forName("ll.Charger").newInstance();
            new Thread(r).start();
        } catch (Throwable t) {
            app.log("Dock: this phone has no sensor API (" + t + ")");
        }
        new Thread(this).start();
    }

    /** From Charger, about once a second. */
    void charger(boolean on) {
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
        long lastFlash = 0;
        while (true) {
            try { Thread.sleep(250); } catch (InterruptedException e) {}
            try {
                tick();
                if (docked && app.shell.isShown()) {
                    long now = System.currentTimeMillis();
                    if (now - lastFlash >= 4000) {           // keep the backlight on while docked
                        boolean ok = app.display.flashBacklight(8000);
                        if (!flashLogged) {
                            app.log("Dock: keep-awake " + (ok ? "on" : "not supported by this phone"));
                            flashLogged = true;
                        }
                        lastFlash = now;
                    }
                }
            } catch (Throwable t) {
                app.log("Dock: " + t);
            }
        }
    }

    private void tick() {
        Shell s = app.shell;
        long now = System.currentTimeMillis();
        long wait = app.dockDelay * 1000L;
        boolean wide = s.sideways();

        if (docked) {
            if (screen != null && !s.holds(screen)) {     // left with Back
                declined = charging;
                leave(false);
                return;
            }
            if (manual) return;
            if (!charging || app.dockMode == OFF) {
                leave(true);
                return;
            }
            if (wide) upSince = -1;
            else if (upSince < 0) upSince = now;
            else if (now - upSince >= wait) leave(true);
            return;
        }

        boolean want = app.dockMode != OFF && charging && wide && !declined && s.isShown();
        if (asking) {
            if (!want) {                                  // unplugged or turned while asking
                asking = false;
                app.display.callSerially(new Runnable() {
                    public void run() { app.shell.closePrompt(); }
                });
            }
            return;
        }
        if (!want) {
            since = -1;
            return;
        }
        if (since < 0) {
            since = now;
            return;
        }
        if (now - since < wait) return;
        since = -1;
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
