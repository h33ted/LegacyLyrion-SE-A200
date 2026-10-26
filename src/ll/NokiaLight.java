package ll;

import com.nokia.mid.ui.DeviceControl;

/**
 * Lights the display through the Nokia UI API, which the Aino also carries. Unlike MIDP's
 * flashBacklight it holds the light steady. Docking loads this class by name and renews it every
 * few seconds while docked, so phones without the API still run the app.
 */
public final class NokiaLight implements Runnable {
    public void run() {
        DeviceControl.setLights(0, 100);
    }
}
