package work.archaic.knit.signing.test;

import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import work.archaic.knit.signing.*;
import work.archaic.service.test.v02.*;

public record SigningSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new GoldenSignature(false));
        cases.add(new GoldenSignature(true));
        cases.add(new SemanticRepacking());
        cases.add(new InvalidPublicKey());
        cases.add(new RefuseUnsignedDirectoryPayload());
        for (String mode : List.of("active", "retired", "revoked", "missing", "duplicate", "unauthenticated", "wrong-key", "malformed", "unavailable", "unsigned", "identity", "interrupted", "bad-base64", "unknown-state", "unsupported-algorithm", "dns-fields"))
            cases.add(new AuthorizationCase(mode));
        for (String mode : List.of("payload", "name", "metadata", "signature", "partial", "directory", "duplicate", "resign"))
            cases.add(new InvalidArtifact(mode));
        for (String name : List.of("work.archaicx.example", "com.example", "work.archaic.example\n")) cases.add(new InvalidNamespace(name));
    }
}

record GoldenSignature(boolean unicode) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var jar = Archive.create()) {
            if (unicode) {
                var entries = jar.contents();
                entries.put("\ue000.txt", new byte[] {0, 1, 2}); entries.put("\ud800\udc00.txt", new byte[] {3, 4, 5});
                jar.write(entries, 9);
            }
            byte[] seed = HexFormat.of().parseHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
            var privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
            var publicKey = Keys.decode(HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"));
            Signing.sign(jar.path(), new Metadata("work.archaic.example", "archaic.work", "test"), privateKey);
            String actual = HexFormat.of().formatHex(Base64.getDecoder().decode(jar.contents().get("META-INF/KNIT/signature")));
            String expected = unicode ? "123a3dfb10e1792cd8fe84fff42ca8d2408b1390fa55fa1f809cbc0d19e448e582088309aec0344d32452f91077495b27b33f728e324204d1cc9b0c3450ed608" : "91228d30e54c1f1e62ce46cc8d7ad9dec947e6703be8f030f4367b371a24f5dff7c422d141e9f4ecf431976dabca555e4ba5dc47654d349dfc845f4d0eb18e02";
            assert actual.equals(expected) : "Canonical framing must match the independent Ed25519 vector";
            assert Signing.check(jar.path(), "work.archaic.example", publicKey) : "A fixed RFC 8032 key must verify the canonical signature";
        }
    }
}

record SemanticRepacking() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (var jar = Archive.create()) {
            var entries = jar.contents(); entries.put("work/", new byte[0]);
            entries.put("\ue000.txt", new byte[] {0, 1, 2}); entries.put("\ud800\udc00.txt", new byte[] {3, 4, 5});
            jar.write(entries, 9);
            Signing.sign(jar.path(), new Metadata("work.archaic.example", "archaic.work", "test"), keys.getPrivate());
            byte[] original = Files.readAllBytes(jar.path());
            var reversed = new LinkedHashMap<String, byte[]>();
            for (var entry : jar.contents().entrySet().stream().toList().reversed()) if (!entry.getKey().endsWith("/")) reversed.put(entry.getKey(), entry.getValue());
            reversed.put("unrelated/", new byte[0]); jar.write(reversed, 0);
            assert !java.util.Arrays.equals(original, Files.readAllBytes(jar.path())) : "The fixture must change ZIP container bytes";
            assert Signing.check(jar.path(), "work.archaic.example", keys.getPublic()) : "Compression, ordering, timestamps and empty directories must not affect trust";
            assert java.util.Arrays.equals(Keys.encode(keys.getPublic()), Keys.encode(Keys.decode(Keys.encode(keys.getPublic())))) : "Raw DNS keys must round trip";
        }
    }
}

record AuthorizationCase(String mode) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (var jar = Archive.create()) {
            if (!mode.equals("unsigned")) Signing.sign(jar.path(), new Metadata("work.archaic.example", "archaic.work", "test"), keys.getPrivate());
            var published = mode.equals("wrong-key") ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic() : keys.getPublic();
            String record = "v=knit1 alg=ed25519 id=test state=" + (mode.equals("retired") ? "retired" : mode.equals("revoked") ? "revoked" : "active")
                    + " key=" + Base64.getEncoder().encodeToString(Keys.encode(published));
            var records = switch (mode) {
                case "missing" -> List.<String>of();
                case "duplicate" -> List.of(record, record);
                case "malformed" -> List.of(record + " id=second");
                case "bad-base64" -> List.of(record.substring(0, record.length() - 1));
                case "unknown-state" -> List.of(record.replace("state=active", "state=unknown"));
                case "unsupported-algorithm" -> List.of(record.replace("alg=ed25519", "alg=ed448"));
                case "dns-fields" -> List.of(record + " extra=field");
                default -> List.of("unrelated TXT protocol", record);
            };
            AuthorizationResolver resolver = publisher -> {
                assert publisher.equals("archaic.work") : "Authorization must resolve the signed publisher";
                if (mode.equals("unsigned") || mode.equals("identity")) throw new AssertionError("No DNS lookup is allowed before identity checks");
                if (mode.equals("unavailable")) throw new java.io.IOException("offline");
                if (mode.equals("interrupted")) throw new InterruptedException("cancelled");
                return new AuthorizationResolver.Authorization(!mode.equals("unauthenticated"), records);
            };
            if (mode.equals("interrupted")) {
                try { Signing.verify(jar.path(), "work.archaic.example", resolver); throw new AssertionError("Interruption must escape verification"); }
                catch (InterruptedException expected) { return; }
            }
            var result = Signing.verify(jar.path(), mode.equals("identity") ? "work.archaic.other" : "work.archaic.example", resolver);
            var expected = switch (mode) {
                case "active", "retired" -> Verification.Status.VERIFIED;
                case "unsigned" -> Verification.Status.UNSIGNED;
                case "unavailable" -> Verification.Status.UNAVAILABLE;
                default -> Verification.Status.REJECTED;
            };
            assert result.status() == expected : "Verification must distinguish trust, rejection and availability for " + mode;
            if (mode.equals("retired")) assert result.keyState().equals("retired") : "Retired keys must remain verifiable and identifiable";
        }
    }
}

record InvalidArtifact(String mode) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        try (var jar = Archive.create()) {
            var metadata = new Metadata("work.archaic.example", "archaic.work", "test");
            Signing.sign(jar.path(), metadata, keys.getPrivate());
            byte[] original = Files.readAllBytes(jar.path());
            if (mode.equals("resign")) {
                try { Signing.sign(jar.path(), metadata, keys.getPrivate()); throw new AssertionError("Resigning must require an explicit future policy"); }
                catch (SigningException expected) { }
                assert java.util.Arrays.equals(original, Files.readAllBytes(jar.path())) : "Rejected signing must preserve existing bytes";
                return;
            }
            var entries = jar.contents();
            switch (mode) {
                case "payload" -> entries.put("module-info.java", "changed".getBytes());
                case "name" -> entries.put("renamed.java", entries.remove("module-info.java"));
                case "metadata" -> entries.put("META-INF/KNIT/metadata", new String(entries.get("META-INF/KNIT/metadata"), java.nio.charset.StandardCharsets.UTF_8).replace("\n", "\r\n").getBytes());
                case "signature" -> entries.put("META-INF/KNIT/signature", new byte[] {0});
                case "partial" -> entries.remove("META-INF/KNIT/signature");
                case "directory" -> entries.put("hidden/", new byte[] {1});
                case "duplicate" -> { entries.put("aa", new byte[] {1}); entries.put("bb", new byte[] {2}); }
                default -> throw new IllegalArgumentException(mode);
            }
            jar.write(entries, 9);
            if (mode.equals("duplicate")) {
                byte[] bytes = Files.readAllBytes(jar.path());
                for (int i = 0; i < bytes.length - 1; i++) if (bytes[i] == 'b' && bytes[i + 1] == 'b') { bytes[i] = 'a'; bytes[i + 1] = 'a'; }
                Files.write(jar.path(), bytes);
            }
            try {
                assert !Signing.check(jar.path(), "work.archaic.example", keys.getPublic()) : "Changed semantic content must fail signature checking";
                assert mode.equals("payload") || mode.equals("name") : "Malformed structure must be rejected before cryptography";
            } catch (SigningException expected) {
                assert !mode.equals("payload") && !mode.equals("name") : "Content tampering must be a signature failure";
            }
        }
    }
}

record InvalidNamespace(String namespace) implements TestCase {
    @Override public void run(TestTrail trail) {
        try { new Metadata(namespace, "archaic.work", "test"); throw new AssertionError("Invalid namespace authority must fail"); }
        catch (IllegalArgumentException expected) { }
    }
}

record InvalidPublicKey() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        for (byte[] bytes : List.of(new byte[31], new byte[33], HexFormat.of().parseHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"))) {
            try { Keys.decode(bytes); throw new AssertionError("Wrong length or noncanonical field coordinates must be rejected"); }
            catch (SigningException expected) { }
        }
    }
}

record RefuseUnsignedDirectoryPayload() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var jar = Archive.create()) {
            var entries = jar.contents(); entries.put("hidden/", new byte[] {1}); jar.write(entries, 9);
            byte[] original = Files.readAllBytes(jar.path());
            var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            try { Signing.sign(jar.path(), new Metadata("work.archaic.example", "archaic.work", "test"), keys.getPrivate()); throw new AssertionError("Directory payload must never be signed"); }
            catch (SigningException expected) { }
            assert java.util.Arrays.equals(original, Files.readAllBytes(jar.path())) : "Rejected unsigned input must be preserved exactly";
        }
    }
}
