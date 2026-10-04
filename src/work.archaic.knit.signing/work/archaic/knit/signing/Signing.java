package work.archaic.knit.signing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import static work.archaic.knit.signing.Verification.Status.*;

/** Signing and verification of Knit v1 semantic JAR contents. */
public final class Signing {
    private Signing() {}

    /** Atomically replace an unsigned regular JAR with its signed form.
     * The caller establishes metadata.namespace from the descriptor and must not concurrently
     * edit or replace the artifact. Failure preserves the original and removes temporary files.
     * @param artifact unsigned regular JAR, not a symbolic link
     * @param metadata descriptor-derived identity and publisher/key selection
     * @param key Ed25519 private key; signing is offline and does not check DNS key state
     * @throws IOException for invalid/already signed artifacts, exceeded limits or publication failure
     * @throws GeneralSecurityException if signing cannot be performed with the supplied key
     */
    public static void sign(Path artifact, Metadata metadata, PrivateKey key) throws IOException, GeneralSecurityException {
        artifact = artifact.toAbsolutePath();
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) throw new SigningException("Signing requires a regular JAR, not a symbolic link");
        Path staged = Files.createTempFile(artifact.getParent(), ".knit-sign-", ".tmp");
        Path completed = null;
        try {
            completed = Files.createTempFile(artifact.getParent(), ".knit-signed-", ".tmp");
            try (var jar = new JarFile(artifact.toFile(), false)) {
                JarContents.entries(jar);
                if (jar.getJarEntry(JarContents.METADATA) != null || jar.getJarEntry(JarContents.SIGNATURE) != null)
                    throw new SigningException("Artifact already contains Knit signing entries");
                rewrite(jar, staged, metadata.bytes(), null);
            }
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            try (var jar = new JarFile(staged.toFile(), false)) { JarContents.update(jar, signer); }
            byte[] signature = Base64.getEncoder().encode(signer.sign());
            try (var jar = new JarFile(staged.toFile(), false)) { rewrite(jar, completed, null, signature); }
            try (var jar = new JarFile(completed.toFile(), false)) { JarContents.entries(jar); inspect(jar); }
            Files.move(completed, artifact, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); if (completed != null) Files.deleteIfExists(completed); }
    }

    /** Validate structure and metadata without DNS or cryptographic trust.
     * @param artifact JAR to inspect
     * @return metadata, or null only when both signing entries are absent
     * @throws IOException for unreadable or structurally invalid archives/signing entries
     */
    public static Metadata inspect(Path artifact) throws IOException {
        try (var jar = new JarFile(artifact.toFile(), false)) { JarContents.entries(jar); return inspect(jar); }
    }

    /** Check cryptographic integrity without establishing publisher authorization.
     * @param artifact signed JAR
     * @param expectedModule name derived independently from the actual module descriptor
     * @param key public key to check; no DNS authorization is inferred
     * @return whether the signature validates against this key
     * @throws IOException for unreadable, unsigned, malformed or identity-mismatched artifacts
     * @throws GeneralSecurityException if cryptographic checking cannot be performed
     */
    public static boolean check(Path artifact, String expectedModule, PublicKey key) throws IOException, GeneralSecurityException {
        try (var jar = new JarFile(artifact.toFile(), false)) {
            JarContents.entries(jar);
            Metadata metadata = inspect(jar);
            if (metadata == null) throw new SigningException("Artifact is unsigned");
            if (!metadata.namespace().equals(expectedModule)) throw new SigningException("Signing namespace disagrees with module descriptor");
            return check(jar, key);
        }
    }

    /** Establish current publisher trust using the artifact's semantic signature.
     * The caller derives expectedModule independently from the actual descriptor.
     * No historical authorization, artifact safety or dependency graph trust is established.
     * @param artifact JAR to inspect, bounded to 128 MiB uncompressed contents and 100000 entries
     * @param expectedModule actual JPMS module name
     * @param resolver trusted DNSSEC validator supplying the complete authorization RRset
     * @return unsigned, verified, rejected or unavailable outcome; only VERIFIED establishes trust
     * @throws InterruptedException if authorization lookup is interrupted
     */
    public static Verification verify(Path artifact, String expectedModule, AuthorizationResolver resolver) throws InterruptedException {
        Metadata metadata = null;
        try (var jar = new JarFile(artifact.toFile(), false)) {
            JarContents.entries(jar);
            metadata = inspect(jar);
            if (metadata == null) return new Verification(UNSIGNED, null, "", "Artifact is unsigned");
            if (!metadata.namespace().equals(expectedModule)) throw new SigningException("Signing namespace disagrees with module descriptor");
            var authorization = resolver.resolve(metadata.publisher());
            if (!authorization.authenticated()) throw new SigningException("DNS authorization is not authenticated through DNSSEC");
            var key = AuthorizedKey.select(authorization.records(), metadata);
            if (!check(jar, key.key())) throw new SigningException("Signature is invalid");
            return new Verification(VERIFIED, metadata, key.state(), "DNSSEC authenticated; signature valid");
        } catch (SigningException | java.util.zip.ZipException | GeneralSecurityException error) {
            return new Verification(REJECTED, metadata, "", error.getMessage());
        } catch (IOException error) {
            return new Verification(UNAVAILABLE, metadata, "", error.getMessage());
        }
    }

    private static Metadata inspect(JarFile jar) throws IOException {
        boolean metadata = jar.getJarEntry(JarContents.METADATA) != null;
        boolean signature = jar.getJarEntry(JarContents.SIGNATURE) != null;
        if (!metadata && !signature) return null;
        if (!metadata || !signature) throw new SigningException("Incomplete Knit signing entries");
        AuthorizedKey.base64(new String(JarContents.read(jar, JarContents.SIGNATURE, 88), java.nio.charset.StandardCharsets.US_ASCII), 64);
        return Metadata.parse(JarContents.read(jar, JarContents.METADATA, 4096));
    }

    private static boolean check(JarFile jar, PublicKey key) throws IOException, GeneralSecurityException {
        var verifier = Signature.getInstance("Ed25519"); verifier.initVerify(key);
        JarContents.update(jar, verifier);
        return verifier.verify(AuthorizedKey.base64(new String(JarContents.read(jar, JarContents.SIGNATURE, 88), java.nio.charset.StandardCharsets.US_ASCII), 64));
    }

    private static void rewrite(JarFile source, Path target, byte[] metadata, byte[] signature) throws IOException {
        try (var out = new JarOutputStream(Files.newOutputStream(target))) {
            for (var original : JarContents.entries(source)) {
                var entry = entry(original.getName()); out.putNextEntry(entry);
                long read = 0;
                try (var in = source.getInputStream(original)) {
                    byte[] buffer = new byte[16384];
                    for (int count; (count = in.read(buffer)) != -1;) {
                        read += count;
                        if (read > original.getSize()) throw new SigningException("JAR entry exceeds declared size: " + original.getName());
                        out.write(buffer, 0, count);
                    }
                }
                if (read != original.getSize()) throw new SigningException("JAR entry length mismatch: " + original.getName());
                out.closeEntry();
            }
            if (metadata != null) { out.putNextEntry(entry(JarContents.METADATA)); out.write(metadata); out.closeEntry(); }
            if (signature != null) { out.putNextEntry(entry(JarContents.SIGNATURE)); out.write(signature); out.closeEntry(); }
        }
    }

    private static JarEntry entry(String name) {
        var entry = new JarEntry(name); entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0)); return entry;
    }
}
