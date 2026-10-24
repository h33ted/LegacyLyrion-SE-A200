package ll;

import javax.microedition.lcdui.Graphics;

/** One page inside the Shell canvas. Coordinates are relative to the content area. */
abstract class Screen {
    final LegacyLyrion app;
    String title;
    String leftLabel = "More", centreLabel = "Select", rightLabel = "Back";

    Screen(LegacyLyrion app, String title) {
        this.app = app;
        this.title = title;
    }

    abstract void paint(Graphics g, int w, int h);

    void shown() {}

    void hidden() {}

    /** Touch tap inside the content area. */
    void tap(int x, int y, int w, int h) {}

    /** Touch drag (finger moved by dy since last call). */
    void drag(int dy) {}

    /** Joystick / keypad. game = Canvas game action or 0. */
    void key(int game, int keyCode) {}

    /** Centre soft key / select. */
    void select() {}

    /** Left soft key: options for the current item; null if none. */
    String[] options() {
        return null;
    }

    void option(int index) {}

    /** Raw keypad key (before rotation mapping); return true to consume it, e.g. for typing. */
    boolean typed(int keyCode, boolean repeat) {
        return false;
    }

    /** Clear key (C); return true if the screen used it (deleting a typed letter) instead of going back. */
    boolean backspace() {
        return false;
    }

    /** True if the left soft key does something itself instead of opening the options menu. */
    boolean leftDirect() {
        return false;
    }

    void left() {}
}
