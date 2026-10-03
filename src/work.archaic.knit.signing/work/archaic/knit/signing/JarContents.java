package work.archaic.knit.signing;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** One physical entry enumeration, independent of multi-release logical lookup. */
final class JarContents {
    static final String METADATA = "META-INF/KNIT/metadata";
    static final String SIGNATURE = "META-INF/KNIT/signature";
    // SunEC's plain Ed25519 implementation may buffer the message. Bound uncompressed work.
    static final long LIMIT = 128L * 1024 * 1024;
    private JarContents() {}

    static List<JarEntry> entries(JarFile jar) throws IOException {
        var result = new ArrayList<JarEntry>();
        var names = new HashSet<String>();
        var files = new HashSet<String>();
        long total = 0;
        var entries = jar.entries();
        while (entries.hasMoreElements()) {
            var entry = entries.nextElement();
            String name = entry.getName();
            if (!names.add(name)) throw new SigningException("Duplicate JAR entry: " + name);
            String clean = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
            if (clean.isEmpty() || clean.startsWith("/") || clean.contains("\\") || clean.contains(":"))
                throw new SigningException("Ambiguous JAR entry: " + name);
            for (String part : clean.split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new SigningException("Ambiguous JAR entry: " + name);
            encodedName(name);
            if (entry.getSize() < 0) throw new SigningException("Unknown JAR entry length: " + name);
            if (entry.isDirectory()) {
                try (var input = jar.getInputStream(entry)) {
                    if (entry.getSize() != 0 || input.read() != -1) throw new SigningException("Directory entry contains payload: " + name);
                }
            } else files.add(name);
            total += entry.getSize() + encodedName(name).length + 12L;
            if (entry.getSize() > LIMIT || total > LIMIT || result.size() >= 100_000)
                throw new SigningException("JAR exceeds signing limit (128 MiB uncompressed, 100000 entries)");
            result.add(entry);
        }
        for (var entry : result) {
            String name = entry.getName();
            if (entry.isDirectory() && files.contains(name.substring(0, name.length() - 1)))
                throw new SigningException("JAR file/directory collision: " + name);
            for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
                if (files.contains(name.substring(0, slash))) throw new SigningException("JAR file/directory collision: " + name);
        }
        return result;
    }

    static byte[] encodedName(String name) throws SigningException {
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(name));
            var bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (java.nio.charset.CharacterCodingException error) { throw new SigningException("Invalid UTF-8 entry name"); }
    }

    static byte[] read(JarFile jar, String name, int limit) throws IOException {
        var entry = jar.getJarEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > limit) throw new SigningException("Missing or oversized " + name);
        try (var input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit || bytes.length != entry.getSize()) throw new SigningException("Invalid entry length: " + name);
            return bytes;
        }
    }

    static void update(JarFile jar, Signature signature) throws IOException, SignatureException {
        var entries = entries(jar).stream().filter(e -> !e.isDirectory() && !e.getName().equals(SIGNATURE))
                .sorted((a, b) -> Arrays.compareUnsigned(uncheckedName(a), uncheckedName(b))).toList();
        signature.update("knit1-semantic-jar\0".getBytes(StandardCharsets.US_ASCII));
        signature.update(ByteBuffer.allocate(4).putInt(entries.size()).array());
        byte[] buffer = new byte[16384];
        for (var entry : entries) {
            byte[] name = encodedName(entry.getName());
            signature.update(ByteBuffer.allocate(4).putInt(name.length).array());
            signature.update(name);
            signature.update(ByteBuffer.allocate(8).putLong(entry.getSize()).array());
            long read = 0;
            try (var input = jar.getInputStream(entry)) {
                for (int n; (n = input.read(buffer)) != -1;) {
                    read += n;
                    if (read > entry.getSize()) throw new SigningException("JAR entry exceeds declared size: " + entry.getName());
                    signature.update(buffer, 0, n);
                }
            }
            if (read != entry.getSize()) throw new SigningException("JAR entry length mismatch: " + entry.getName());
        }
    }

    private static byte[] uncheckedName(JarEntry entry) {
        // entries() already required successful strict UTF-8 encoding.
        return entry.getName().getBytes(StandardCharsets.UTF_8);
    }
}
