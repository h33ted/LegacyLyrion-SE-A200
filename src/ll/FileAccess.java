package ll;

import java.util.Vector;

import javax.microedition.lcdui.Image;

/** The phone's files (JsrFiles, through JSR-75), for the standby wallpaper's idle picture. */
interface FileAccess {
    /** "file:///c:/" and the like. */
    Vector roots() throws Exception;

    /** Folders (ending in "/") and pictures in a folder, as names. */
    Vector list(String url) throws Exception;

    Image load(String url) throws Exception;
}
