package ll;

import javax.microedition.io.Connector;
import javax.microedition.sensor.ChannelInfo;
import javax.microedition.sensor.Data;
import javax.microedition.sensor.SensorConnection;
import javax.microedition.sensor.SensorInfo;
import javax.microedition.sensor.SensorManager;

/**
 * Reports whether the charger is connected, through the JSR-256 "charger_state" sensor, about
 * once a second. Docking loads this class by name, so phones without the sensor API still run.
 * Also writes the list of sensors the phone offers to Diagnostics.
 */
public final class Charger implements Runnable {

    public void run() {
        LegacyLyrion app = Shell.instance.app;
        Docking dock = app.docking;
        SensorConnection c = null;
        try {
            SensorInfo[] all = SensorManager.findSensors(null, null);
            StringBuffer b = new StringBuffer("Sensors:");
            for (int i = 0; all != null && i < all.length; i++) b.append(' ').append(all[i].getQuantity());
            app.log(all == null || all.length == 0 ? "Sensors: none reported" : b.toString());

            SensorInfo[] s = SensorManager.findSensors("charger_state", null);
            if (s == null || s.length == 0) {
                app.log("Dock: no charger sensor; docking on sideways and still only");
                return;
            }
            c = (SensorConnection) Connector.open(s[0].getUrl());
            app.log("Dock: charger sensor " + s[0].getUrl());
            int last = -1;
            while (true) {
                Data[] d = c.getData(1);
                boolean on = d != null && d.length > 0 && value(d[0]);
                dock.charger(on);
                if ((on ? 1 : 0) != last) {
                    app.log("Dock: charger " + (on ? "connected" : "disconnected"));
                    last = on ? 1 : 0;
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        } catch (Throwable t) {
            app.log("Dock: charger sensor unavailable (" + t + ")");
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
    }

    /** Latest reading as connected / not connected, whatever type the firmware reports it in. */
    private static boolean value(Data d) {
        int type = d.getChannelInfo().getDataType();
        if (type == ChannelInfo.TYPE_INT) {
            int[] v = d.getIntValues();
            return v != null && v.length > 0 && v[v.length - 1] > 0;
        }
        if (type == ChannelInfo.TYPE_DOUBLE) {
            double[] v = d.getDoubleValues();
            return v != null && v.length > 0 && v[v.length - 1] > 0;
        }
        Object[] v = d.getObjectValues();
        if (v == null || v.length == 0 || v[v.length - 1] == null) return false;
        String s = v[v.length - 1].toString().toLowerCase();
        return s.equals("true") || s.equals("1") || s.startsWith("connect") || s.startsWith("charg");
    }
}
