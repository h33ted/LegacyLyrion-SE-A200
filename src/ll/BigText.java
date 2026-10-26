package ll;

import javax.microedition.lcdui.Graphics;

/** Text larger than MIDP's fonts allow, in the phone's own font (SvgText, through JSR-226). */
interface BigText {
    /** Draws white text with its right edge at x and its top at y, about px tall. */
    void draw(Graphics g, String text, int x, int y, int px) throws Exception;
}
