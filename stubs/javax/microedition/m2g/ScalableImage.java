package javax.microedition.m2g;
/** Compile-time stub (JSR-226). */
public abstract class ScalableImage {
    protected ScalableImage() {}
    public static ScalableImage createImage(java.io.InputStream stream, ExternalResourceHandler handler) throws java.io.IOException { return null; }
    public abstract int getViewportWidth();
    public abstract int getViewportHeight();
    public abstract void setViewportWidth(int width);
    public abstract void setViewportHeight(int height);
}
