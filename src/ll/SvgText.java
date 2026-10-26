package ll;

import java.io.ByteArrayInputStream;

import javax.microedition.lcdui.Graphics;
import javax.microedition.m2g.ScalableGraphics;
import javax.microedition.m2g.ScalableImage;
import javax.microedition.m2g.SVGImage;

/**
 * Large text through SVG (JSR-226): no font-family is given, so the phone draws it in its own
 * font, smooth at any size, like the standby clock. DockScreen loads this class by name, so phones
 * without SVG fall back to MIDP's largest font.
 */
public final class SvgText implements BigText {
    private final ScalableGraphics sg = ScalableGraphics.createInstance();
    private String key;
    private ScalableImage img;
    private int iw, ih;

    public void draw(Graphics g, String text, int x, int y, int px, int alpha) throws Exception {
        String k = text + "/" + px;
        if (!k.equals(key)) {                       // rebuilt only when the minute or the size changes
            iw = px * 3;                            // room for "23:59"; text is anchored at the right edge
            ih = px * 6 / 5;
            String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.1\" baseProfile=\"tiny\""
                    + " width=\"" + iw + "\" height=\"" + ih + "\" viewBox=\"0 0 " + iw + " " + ih + "\">"
                    + "<text x=\"" + iw + "\" y=\"" + (px * 19 / 20) + "\" font-size=\"" + px + "\""
                    + " text-anchor=\"end\" fill=\"#ffffff\">" + text + "</text></svg>";
            img = SVGImage.createImage(new ByteArrayInputStream(svg.getBytes("UTF-8")), null);
            img.setViewportWidth(iw);
            img.setViewportHeight(ih);
            key = k;
        }
        sg.bindTarget(g);
        try {
            sg.setRenderingQuality(ScalableGraphics.RENDERING_QUALITY_HIGH);
            sg.setTransparency(alpha / 100f);
            sg.render(x - iw, y, img);
        } finally {
            sg.releaseTarget();
        }
    }
}
