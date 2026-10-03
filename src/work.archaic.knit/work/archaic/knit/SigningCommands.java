package work.archaic.knit;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.HexFormat;
import work.archaic.knit.dns.SystemdResolver;
import work.archaic.knit.signing.Metadata;
import work.archaic.knit.signing.Signing;
import work.archaic.knit.signing.Verification;

final class SigningCommands {
    private SigningCommands() {}
    static void sign(Path artifact, String module, Project project) throws Exception {
        var settings = project.signing();
        if (settings == null) throw new InputFailure("Signing requires <signing> in knit.xml");
        Metadata metadata;
        try { metadata = new Metadata(module, settings.publisher(), settings.keyId()); }
        catch (IllegalArgumentException error) { throw new InputFailure(error.getMessage()); }
        String location = System.getenv(settings.privateKeyEnv());
        if (location == null || location.isBlank()) throw new InputFailure("Missing private-key path in " + settings.privateKeyEnv());
        Path keyPath = Path.of(location);
        if (!keyPath.isAbsolute()) keyPath = project.root().resolve(keyPath);
        byte[] bytes;
        try (var input = Files.newInputStream(keyPath)) {
            bytes = input.readNBytes(16385);
            if (bytes.length > 16384) throw new InputFailure("PKCS#8 key file exceeds 16 KiB");
        }
        try {
            var key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            Signing.sign(artifact, metadata, key);
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }

    static void verify(Path artifact, PrintWriter output) throws Exception {
        String module = ArtifactIdentity.read(artifact);
        var result = Signing.verify(artifact, module, new SystemdResolver());
        output.println("Verification: " + result.status().name().toLowerCase(java.util.Locale.ROOT));
        if (result.metadata() != null) {
            output.println("Module: " + result.metadata().namespace());
            output.println("Publisher: " + result.metadata().publisher());
            output.println("Key: " + result.metadata().keyId());
            if (!result.keyState().isEmpty()) output.println("Key state: " + result.keyState());
        }
        output.println(result.detail());
        if (result.status() != Verification.Status.VERIFIED) throw new InputFailure("Package trust was not established");
    }

    static String hash(Path artifact) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] buffer = new byte[16384];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
