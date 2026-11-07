package ll;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;

import javax.microedition.rms.RecordStore;

/**
 * The home menu's artwork strips (Recently added, Albums, Made for you...), kept in RMS so the
 * start screen doesn't ask the server for them on every launch. A strip older than MAX_AGE is
 * still shown straight away and fetched again in the background, so it refreshes about twice a
 * week. One small record store per strip ("llart" + a hash of its key, a record per picture), so a
 * lookup reads only that strip; Settings > Refresh artwork clears them all.
 */
final class ArtCache {
    static final long MAX_AGE = 84L * 3600 * 1000;      // 3.5 days
    private static final String PREFIX = "llart";

    private static String store(String key) {
        return PREFIX + Integer.toHexString(key.hashCode());
    }

    /** {Long saved-at, Vector of byte[] (null where an item had no art)}, or null if not cached. */
    static synchronized Object[] get(String key) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(store(key), false);
            if (rs.getNumRecords() == 0) return null;
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
            if (!in.readUTF().equals(key)) return null;             // a hash clash: treat as missing
            long t = in.readLong();
            int n = in.readInt();
            if (rs.getNumRecords() < n + 1) return null;            // half written
            Vector v = new Vector();
            for (int k = 0; k < n; k++) {
                byte[] b = rs.getRecord(k + 2);
                v.addElement(b == null || b.length <= 1 ? null : b);
            }
            return new Object[]{new Long(t), v};
        } catch (Exception e) {
            return null;                                            // not there yet, or unreadable
        } finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception ignored) {}
        }
    }

    /**
     * Saves a strip: a small header record, then one record per picture (records 2...), since
     * some phones refuse one large record. Returns null, or why it couldn't be saved.
     */
    static synchronized String put(String key, Vector images) {
        RecordStore rs = null;
        int bytes = 0;
        try {
            try { RecordStore.deleteRecordStore(store(key)); } catch (Exception ignored) {}   // ids start at 1 again
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bo);
            out.writeUTF(key);
            out.writeLong(System.currentTimeMillis());
            out.writeInt(images.size());
            out.flush();
            byte[] head = bo.toByteArray();
            rs = RecordStore.openRecordStore(store(key), true);
            rs.addRecord(head, 0, head.length);
            byte[] none = {0};
            for (int k = 0; k < images.size(); k++) {
                byte[] b = (byte[]) images.elementAt(k);
                if (b == null || b.length == 0) b = none;
                bytes += b.length;
                rs.addRecord(b, 0, b.length);
            }
            return null;
        } catch (Exception e) {
            String free = "";
            try { if (rs != null) free = ", " + rs.getSizeAvailable() / 1024 + " KB free"; } catch (Exception ignored) {}
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception ignored) {}
            rs = null;
            try { RecordStore.deleteRecordStore(store(key)); } catch (Exception ignored) {}
            return e + " at " + bytes / 1024 + " KB" + free;
        } finally {
            try { if (rs != null) rs.closeRecordStore(); } catch (Exception ignored) {}
        }
    }

    /** Forgets every cached strip. */
    static synchronized void clear() {
        String[] names = RecordStore.listRecordStores();
        if (names == null) return;
        for (int k = 0; k < names.length; k++) {
            if (names[k].startsWith(PREFIX)) {
                try { RecordStore.deleteRecordStore(names[k]); } catch (Exception ignored) {}
            }
        }
    }
}
