package ll;

import java.util.Vector;

import javax.microedition.lcdui.Image;

/** The phone's files (JsrFiles, through JSR-75): the standby wallpaper's picture, saved diagnostics. */
interface FileAccess {
    /** "file:///c:/" and the like. */
    Vector roots() throws Exception;

    /** Folders (ending in "/") and pictures in a folder, as names. */
    Vector list(String url) throws Exception;

    Image load(String url) throws Exception;

    /** Writes a file to the memory card (or phone memory), in its Other folder if there is one; returns its URL. */
    String save(String name, byte[] data) throws Exception;
}
