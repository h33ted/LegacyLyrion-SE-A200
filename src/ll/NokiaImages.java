package ll;

import javax.microedition.lcdui.Image;

import com.nokia.mid.ui.DirectUtils;

/**
 * Transparent mutable images through DirectUtils (MIDP's own mutable images are always opaque).
 * Loaded by name, so phones without the Nokia UI API still run the app.
 */
public final class NokiaImages implements ImageMaker {
    public Image transparent(int w, int h) {
        return DirectUtils.createImage(w, h, 0x00000000);
    }
}
