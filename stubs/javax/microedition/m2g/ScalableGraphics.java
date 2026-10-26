package javax.microedition.m2g;
/** Compile-time stub (JSR-226). */
public class ScalableGraphics {
    public static final int RENDERING_QUALITY_LOW = 1;
    public static final int RENDERING_QUALITY_HIGH = 2;
    protected ScalableGraphics() {}
    public static ScalableGraphics createInstance() { return null; }
    public void bindTarget(Object target) {}
    public void releaseTarget() {}
    public void render(int x, int y, ScalableImage image) {}
    public void setRenderingQuality(int mode) {}
    public void setTransparency(float alpha) {}
}
