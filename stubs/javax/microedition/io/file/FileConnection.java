package javax.microedition.io.file;
/** Compile-time stub (JSR-75 FileConnection). */
public interface FileConnection extends javax.microedition.io.StreamConnection {
    boolean exists();
    boolean isDirectory();
    java.util.Enumeration list() throws java.io.IOException;
    String getName();
    String getURL();
}
