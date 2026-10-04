package work.archaic.knit.signing;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Signed identity; the caller establishes namespace from the actual module descriptor.
 * @param namespace Java identifier components without ignorable characters, at most
 *     3000 UTF-8 bytes; equal to reversed publisher labels or a dotted descendant
 * @param publisher lowercase ASCII DNS domain, at least two labels, no trailing dot,
 *     at most 247 characters; domain hyphens are not translated into Java identifiers
 * @param keyId case-sensitive identifier matching {@code [A-Za-z0-9._-]{1,64}}
 */
public record Metadata(String namespace, String publisher, String keyId) {
    /** Validate identity syntax and publisher ownership.
     * @throws IllegalArgumentException if any component is null or violates the identity rules
     */
    public Metadata {
        if (namespace == null || !namespace.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*"))
            throw new IllegalArgumentException("Invalid signing namespace");
        if (publisher == null || publisher.length() > 247
                || !publisher.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+"))
            throw new IllegalArgumentException("Publisher must be a lowercase ASCII DNS domain without a trailing dot");
        if (keyId == null || !keyId.matches("[A-Za-z0-9._-]{1,64}"))
            throw new IllegalArgumentException("Invalid signing key-id");
        var labels = publisher.split("\\.");
        java.util.Collections.reverse(Arrays.asList(labels));
        String authority = String.join(".", labels);
        if (!namespace.equals(authority) && !namespace.startsWith(authority + "."))
            throw new IllegalArgumentException("Signing namespace does not belong to publisher " + publisher);
        if (namespace.getBytes(StandardCharsets.UTF_8).length > 3000)
            throw new IllegalArgumentException("Signing namespace is too long");
        if (namespace.codePoints().anyMatch(Character::isIdentifierIgnorable))
            throw new IllegalArgumentException("Signing namespace contains ignorable characters");
    }

    byte[] bytes() {
        return ("version=knit1\nnamespace=" + namespace + "\npublisher=" + publisher
                + "\nalgorithm=ed25519\nkey-id=" + keyId + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static Metadata parse(byte[] bytes) throws SigningException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length != 6 || !lines[0].equals("version=knit1")
                || !lines[1].startsWith("namespace=") || !lines[2].startsWith("publisher=")
                || !lines[3].equals("algorithm=ed25519") || !lines[4].startsWith("key-id=") || !lines[5].isEmpty())
            throw new SigningException("Malformed Knit signing metadata");
        try {
            var value = new Metadata(lines[1].substring(10), lines[2].substring(10), lines[4].substring(7));
            if (!Arrays.equals(bytes, value.bytes())) throw new SigningException("Noncanonical Knit signing metadata");
            return value;
        } catch (IllegalArgumentException error) { throw new SigningException(error.getMessage()); }
    }
}
