package work.archaic.knit.dns;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import work.archaic.knit.signing.AuthorizationResolver;
import work.archaic.knit.signing.Metadata;

/** Uses trusted busctl and systemd-resolved on the system bus; never trusts a remote DNS AD bit. */
public final class SystemdResolver implements AuthorizationResolver {
    private final String executable;
    /** Use trusted busctl on PATH and local systemd-resolved on the system bus.
     * The resolver must support the requested flags and be configured for DNSSEC validation.
     */
    public SystemdResolver() { executable = "busctl"; }
    /** Select an explicitly trusted busctl executable, including an isolated test fixture.
     * @param executable trusted executable path, made absolute at construction
     */
    public SystemdResolver(Path executable) { this.executable = executable.toAbsolutePath().toString(); }

    /** Resolve through the local system bus with a 25-second process deadline.
     * Responses are limited to 1 MiB and 4096 records. Authentication requires validated,
     * unicast DNS data excluding synthetic/local-zone origins; no aliases, search suffixes
     * or stale answers are requested. Inherited remote-bus selection is removed.
     * @throws IOException for invalid domains, unavailable services, failed lookup or malformed replies
     * @throws InterruptedException if the calling thread is interrupted
     */
    @Override public Authorization resolve(String publisher) throws IOException, InterruptedException {
        // Reuse canonical publisher checks before constructing a resolver query.
        try {
            var labels = publisher.split("\\."); java.util.Collections.reverse(java.util.Arrays.asList(labels));
            new Metadata(String.join(".", labels), publisher, "validation");
        } catch (IllegalArgumentException error) { throw new IOException("Invalid publisher domain", error); }
        String owner = "_knit." + publisher;
        // DNS only; no aliases, search suffixes, synthesized data, local zones, or stale answers.
        long flags = 1L | (1L << 5) | (1L << 8) | (1L << 11) | (1L << 13) | (1L << 24);
        var builder = new ProcessBuilder(executable, "--system", "--timeout=20s", "--no-pager", "call",
                "org.freedesktop.resolve1", "/org/freedesktop/resolve1", "org.freedesktop.resolve1.Manager",
                "ResolveRecord", "isqqt", "0", owner, "1", "16", Long.toString(flags)).redirectErrorStream(true);
        // Always use the local system bus; inherited remote-bus selection cannot supply trust evidence.
        builder.environment().remove("DBUS_SYSTEM_BUS_ADDRESS");
        builder.environment().remove("SYSTEMD_BUS_ADDRESS");
        var process = builder.start();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = executor.submit(() -> {
                try (var input = process.getInputStream()) {
                    byte[] bytes = input.readNBytes(1024 * 1024 + 1);
                    if (bytes.length > 1024 * 1024) throw new IOException("DNS resolver response exceeds 1 MiB");
                    return new String(bytes, StandardCharsets.US_ASCII);
                }
            });
            try {
                if (!process.waitFor(25, TimeUnit.SECONDS)) throw new IOException("DNS authorization lookup timed out");
                String reply = output.get(1, TimeUnit.SECONDS);
                if (process.exitValue() != 0) throw new IOException("systemd-resolved lookup failed; ensure busctl and a DNSSEC-validating systemd-resolved are available");
                return reply(reply, owner);
            } catch (ExecutionException | TimeoutException error) { throw new IOException("Cannot read DNS authorization response", error); }
            finally { process.destroyForcibly(); process.getInputStream().close(); }
        } finally { process.destroyForcibly(); }
    }

    private static Authorization reply(String reply, String owner) throws IOException {
        var tokens = new Tokens(reply);
        if (!tokens.next().equals("a(iqqay)t")) throw new IOException("Unexpected busctl reply signature");
        int count = tokens.number(4096);
        var records = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            tokens.number(Integer.MAX_VALUE); // interface index
            int clazz = tokens.number(65535), type = tokens.number(65535);
            byte[] record = new byte[tokens.number(65535)];
            for (int j = 0; j < record.length; j++) record[j] = (byte) tokens.number(255);
            if (clazz != 1 || type != 16) throw new IOException("DNS response contains unexpected record type");
            records.add(txt(record, owner));
        }
        long flags;
        try { flags = Long.parseUnsignedLong(tokens.next()); }
        catch (NumberFormatException error) { throw new IOException("Invalid DNS authorization flags", error); }
        if (tokens.more()) throw new IOException("Trailing DNS resolver response data");
        boolean authenticated = (flags & 512) != 0 && (flags & 31) == 1
                && (flags & ((1L << 19) | (1L << 21) | (1L << 27))) == 0;
        return new Authorization(authenticated, records);
    }

    private static String txt(byte[] raw, String expected) throws IOException {
        var bytes = java.nio.ByteBuffer.wrap(raw);
        var labels = new ArrayList<String>();
        try {
            for (int n; (n = Byte.toUnsignedInt(bytes.get())) != 0;) {
                if (n > 63 || n > bytes.remaining()) throw new IOException("Invalid DNS owner name");
                byte[] label = new byte[n]; bytes.get(label);
                for (byte value : label) if (value < 33 || value > 126) throw new IOException("Non-ASCII DNS owner name");
                labels.add(new String(label, StandardCharsets.US_ASCII));
            }
            if (!String.join(".", labels).equalsIgnoreCase(expected)) throw new IOException("DNS record owner disagrees with publisher");
            if (Short.toUnsignedInt(bytes.getShort()) != 16 || Short.toUnsignedInt(bytes.getShort()) != 1)
                throw new IOException("Invalid TXT resource record");
            bytes.getInt(); // TTL is handled by systemd-resolved's cache.
            int size = Short.toUnsignedInt(bytes.getShort());
            if (size != bytes.remaining() || size == 0) throw new IOException("Invalid TXT RDATA length");
            var text = new StringBuilder();
            while (bytes.hasRemaining()) {
                int n = Byte.toUnsignedInt(bytes.get());
                if (n > bytes.remaining()) throw new IOException("Truncated TXT character-string");
                for (int i = 0; i < n; i++) {
                    int value = Byte.toUnsignedInt(bytes.get());
                    if (value < 32 || value > 126) throw new IOException("Non-ASCII Knit TXT record");
                    text.append((char) value);
                }
            }
            return text.toString();
        } catch (java.nio.BufferUnderflowException error) { throw new IOException("Truncated DNS resource record", error); }
    }

    private static final class Tokens {
        private final String[] values;
        private int at;
        Tokens(String text) { values = text.strip().split("\\s+"); }
        String next() throws IOException {
            if (!more()) throw new IOException("Truncated busctl reply");
            return values[at++];
        }
        boolean more() { return at < values.length; }
        int number(int maximum) throws IOException {
            try {
                int value = Integer.parseInt(next());
                if (value < 0 || value > maximum) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException error) { throw new IOException("Invalid busctl integer", error); }
        }
    }
}
