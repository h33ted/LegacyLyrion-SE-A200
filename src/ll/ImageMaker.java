package ll;

import javax.microedition.lcdui.Image;

/** Mutable images that can be transparent (NokiaImages, through the Nokia UI API's DirectUtils). */
interface ImageMaker {
    Image transparent(int w, int h);
}
