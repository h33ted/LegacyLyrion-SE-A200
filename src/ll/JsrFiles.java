package ll;

import java.io.InputStream;
import java.io.OutputStream;
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

    public String save(String name, byte[] data) throws Exception {
        String dir = folder();
        FileConnection fc = (FileConnection) Connector.open(dir + name, Connector.READ_WRITE);
        try {
            if (!fc.exists()) fc.create();
            OutputStream o = fc.openOutputStream();
            try {
                o.write(data);
            } finally {
                o.close();
            }
        } finally {
            fc.close();
        }
        return dir + name;
    }

    /** The memory card if there is one (any root but c:/), else the first root; its Other folder if present. */
    private String folder() throws Exception {
        Vector r = roots();
        if (r.isEmpty()) throw new Exception("no memory found");
        String root = (String) r.elementAt(0);
        for (int i = 0; i < r.size(); i++) {
            String u = (String) r.elementAt(i);
            if (!u.toLowerCase().startsWith("file:///c:")) {
                root = u;
                break;
            }
        }
        try {
            FileConnection d = (FileConnection) Connector.open(root + "Other/", Connector.READ);
            try {
                if (d.exists() && d.isDirectory()) return root + "Other/";
            } finally {
                d.close();
            }
        } catch (Exception ignored) {}
        return root;
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
