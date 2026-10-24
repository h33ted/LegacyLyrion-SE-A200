package javax.microedition.sensor;
/** Compile-time stub (JSR-256). */
public interface SensorConnection extends javax.microedition.io.Connection {
    Data[] getData(int bufferSize) throws java.io.IOException;
    SensorInfo getSensorInfo();
}
