import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Writes a MIDlet .jar the way 2000s phone installers expect: META-INF/MANIFEST.MF first,
 * no directory entries, no extra fields, entries STORED with sizes and CRC in the local header
 * (no data descriptors). JDK's jar tool does none of these, and some Sony Ericsson firmware
 * then refuses to install the file without saying why.
 *
 * Usage: java tools/Pack.java <out.jar> <MANIFEST.MF> <classes-dir>
 */
public class Pack {
    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]), manifest = Paths.get(a[1]), root = Paths.get(a[2]);
        List<Path> files = new ArrayList<>();
        try (var s = Files.walk(root)) { s.filter(Files::isRegularFile).sorted().forEach(files::add); }
        try (ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(out)))) {
            z.setMethod(ZipOutputStream.STORED);
            add(z, "META-INF/MANIFEST.MF", crlf(Files.readAllBytes(manifest)));
            for (Path f : files) add(z, root.relativize(f).toString().replace('\\', '/'), Files.readAllBytes(f));
        }
    }

    static void add(ZipOutputStream z, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        CRC32 c = new CRC32();
        c.update(data);
        e.setCrc(c.getValue());
        e.setTime(946684800000L); // fixed date: reproducible jars
        z.putNextEntry(e);
        z.write(data);
        z.closeEntry();
    }

    /** Manifest with CRLF line ends and a final blank line, as the JAR spec wants. */
    static byte[] crlf(byte[] b) {
        String s = new String(b, java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
        return (s.replace("\n", "\r\n") + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
