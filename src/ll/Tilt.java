package ll;

import javax.microedition.io.Connector;
import javax.microedition.sensor.ChannelInfo;
import javax.microedition.sensor.Data;
import javax.microedition.sensor.SensorConnection;
import javax.microedition.sensor.SensorInfo;
import javax.microedition.sensor.SensorManager;

/**
 * Reads the accelerometer through JSR-256 and tells the Shell which way the phone is held.
 * Shell loads this class by name, so phones without the sensor API still run the app.
 */
public final class Tilt implements Runnable {

    public void run() {
        Shell shell = Shell.instance;
        SensorConnection c = null;
        try {
            SensorInfo[] s = SensorManager.findSensors("acceleration", null);
            if (s == null || s.length == 0) {
                shell.app.log("Rotation: no accelerometer found");
                return;
            }
            c = (SensorConnection) Connector.open(s[0].getUrl());
            shell.app.log("Rotation: using " + s[0].getUrl());
            boolean logged = false;
            while (shell.tiltWanted()) {
                Data[] d = c.getData(1);
                double x = 0, y = 0;
                for (int i = 0; d != null && i < d.length; i++) {
                    String n = d[i].getChannelInfo().getName();
                    double v = first(d[i]);
                    if ("axis_x".equals(n)) x = v;
                    else if ("axis_y".equals(n)) y = v;
                }
                if (!logged) {
                    shell.app.log("Rotation: x=" + x + " y=" + y);
                    logged = true;
                }
                shell.tilt(x, y);
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        } catch (Throwable t) {
            shell.app.log("Rotation: sensor unavailable (" + t + ")");
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
    }

    private static double first(Data d) {
        if (d.getChannelInfo().getDataType() == ChannelInfo.TYPE_INT) {
            int[] v = d.getIntValues();
            return v != null && v.length > 0 ? v[v.length - 1] : 0;
        }
        double[] v = d.getDoubleValues();
        return v != null && v.length > 0 ? v[v.length - 1] : 0;
    }
}
