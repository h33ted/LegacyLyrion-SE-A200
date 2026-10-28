package ll;

import java.io.InputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;
import javax.microedition.lcdui.Image;

/** JSR-75 file access. Loaded by name, so phones without the API still run the standby app. */
public final class JsrFiles implements FileAccess {
    public Vector roots() throws Exception {
        Vector v = new Vector();
        Enumeration e = FileSystemRegistry.listRoots();
        while (e != null && e.hasMoreElements()) v.addElement("file:///" + e.nextElement());
        return v;
    }

    public Vector list(String url) throws Exception {
        Vector dirs = new Vector(), pics = new Vector();
        FileConnection fc = (FileConnection) Connector.open(url, Connector.READ);
        try {
            Enumeration e = fc.list();
            while (e.hasMoreElements()) {
                String n = (String) e.nextElement();
                String l = n.toLowerCase();
                if (n.endsWith("/")) dirs.addElement(n);
                else if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".gif")) pics.addElement(n);
            }
        } finally {
            fc.close();
        }
        for (int i = 0; i < pics.size(); i++) dirs.addElement(pics.elementAt(i));
        return dirs;
    }

    public Image load(String url) throws Exception {
        FileConnection fc = (FileConnection) Connector.open(url, Connector.READ);
        InputStream in = null;
        try {
            in = fc.openInputStream();
            return Image.createImage(in);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            fc.close();
        }
    }
}
