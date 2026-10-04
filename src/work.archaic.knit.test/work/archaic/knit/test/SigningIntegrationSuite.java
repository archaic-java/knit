package work.archaic.knit.test;

import java.nio.file.Files;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import work.archaic.knit.signing.Signing;
import work.archaic.service.test.v02.*;

public record SigningIntegrationSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new SignedPackage());
        cases.add(new StandaloneSigning());
        cases.add(new BinarySigning());
        cases.add(new DescriptorMismatch());
        for (String kind : List.of("missing-key", "bad-key", "publisher", "duplicate-config", "unknown-config")) cases.add(new SigningFailure(kind));
    }
}

record SignedPackage() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var author = Fixture.create(); var consumer = Fixture.create()) {
            author.write("src/work.archaic.example/module-info.java", "module work.archaic.example { exports api; }");
            author.write("src/work.archaic.example/api/Api.java", "package api; public class Api { public static int value() { return 7; } }");
            author.write("knit.xml", "<knit version=\"1\"><signing publisher=\"archaic.work\" key-id=\"test\" private-key-env=\"KNIT_TEST_KEY\"/></knit>");
            var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var key = author.root().resolve("private.pk8"); Files.write(key, keys.getPrivate().getEncoded());
            var packaged = author.knitEnvironment(List.of("package", "work.archaic.example"), Map.of("KNIT_TEST_KEY", key.toString())); trail.note(packaged.output());
            assert packaged.stdout().isEmpty() && packaged.stderr().contains("sha256:") : "Signed packaging progress and hashes must use stderr";
            assert packaged.exit() == 0 : "Configured packaging must sign without DNS";
            var archive = author.packaged(packaged);
            assert Signing.check(archive, "work.archaic.example", keys.getPublic()) : "Published package must contain a valid signature";
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            assert packaged.output().contains("sha256: " + hash) : "Packaging must hash the signed artifact, not its unsigned predecessor";
            byte[] original = Files.readAllBytes(archive);
            var repeated = author.knitEnvironment(List.of("package", "work.archaic.example"), Map.of("KNIT_TEST_KEY", key.toString()));
            assert repeated.exit() == 0 && Arrays.equals(original, Files.readAllBytes(author.packaged(repeated))) : "Signed packaging must remain deterministic for unchanged inputs and key";
            var failed = author.packageModule("work.archaic.example");
            assert failed.exit() == 2 && Arrays.equals(original, Files.readAllBytes(archive)) : "Missing signing credentials must preserve the existing package";
            consumer.app("requires work.archaic.example;", "System.out.print(api.Api.value());");
            consumer.config("", consumer.dependency(archive, "work.archaic.example", "https://invalid.example/signed.jar", true));
            var compiled = consumer.knit("compile"); trail.note(compiled.output());
            assert compiled.exit() == 0 : "Signed source archives must compile offline under existing SHA-256 pinning";
            var launched = consumer.launch("example.app/example.Main");
            assert launched.exit() == 0 && launched.output().equals("7") : "Signed source distributions must remain executable by consumers";
        }
    }
}

record StandaloneSigning() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("src/work.archaic.example/module-info.java", "module work.archaic.example {}");
            var packaged = f.packageModule("work.archaic.example");
            assert packaged.exit() == 0 : "Unsigned packaging must remain available without signing configuration";
            var jar = f.packaged(packaged);
            // verify must ignore unrelated or invalid project configuration.
            f.write("knit.xml", "invalid XML");
            var unsigned = f.knitCommand(List.of("verify", jar.toString()));
            assert unsigned.stdout().isEmpty() && unsigned.stderr().contains("Verification: unsigned") : "Verification outcomes must use stderr";
            assert unsigned.exit() == 2 && unsigned.output().contains("Verification: unsigned") : "Unsigned verification must fail without reading project configuration or querying DNS";
            f.write("knit.xml", "<knit version=\"1\"><signing publisher=\"archaic.work\" key-id=\"test\" private-key-env=\"KNIT_TEST_KEY\"/></knit>");
            var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var key = f.root().resolve("private.pk8"); Files.write(key, keys.getPrivate().getEncoded());
            var signed = f.knitEnvironment(List.of("sign", jar.toString()), Map.of("KNIT_TEST_KEY", key.toString())); trail.note(signed.output());
            assert signed.stdout().isEmpty() && signed.stderr().contains("Signed ") : "Signing progress must use stderr";
            assert signed.exit() == 0 && Signing.check(jar, "work.archaic.example", keys.getPublic()) : "Standalone signing must establish identity from the source descriptor";
            byte[] original = Files.readAllBytes(jar);
            var resign = f.knitEnvironment(List.of("sign", jar.toString()), Map.of("KNIT_TEST_KEY", key.toString()));
            assert resign.exit() == 2 && Arrays.equals(original, Files.readAllBytes(jar)) : "Already signed artifacts must be preserved when resigning is rejected";
        }
    }
}

record SigningFailure(String kind) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            String element = "<signing publisher=\"archaic.work\" key-id=\"test\" private-key-env=\"KNIT_TEST_KEY\"/>";
            if (kind.equals("duplicate-config")) element += element;
            if (kind.equals("unknown-config")) element = element.replace("/>", " extra=\"bad\"/>");
            if (!kind.equals("publisher")) f.write("src/work.archaic.example/module-info.java", "module work.archaic.example {}");
            f.write("knit.xml", "<knit version=\"1\">" + element + "</knit>");
            var key = f.write("invalid.pk8", "invalid");
            var result = kind.equals("missing-key") ? f.packageModule("work.archaic.example")
                    : f.knitEnvironment(List.of("package", kind.equals("publisher") ? "example.app" : "work.archaic.example"), Map.of("KNIT_TEST_KEY", key.toString()));
            trail.note(result.output());
            assert result.exit() == 2 : "Invalid signing inputs must be ordinary input failures: " + kind;
            if (Files.exists(f.root().resolve("dist"))) try (var files = Files.list(f.root().resolve("dist"))) {
                assert files.findAny().isEmpty() : "Failed signing must not publish an unsigned package or leave temporary files";
            }
        }
    }
}

record BinarySigning() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("src/work.archaic.example/module-info.java", "module work.archaic.example {}");
            var compiled = f.knit("compile");
            assert compiled.exit() == 0 : "A binary signing fixture must compile";
            var jar = f.root().resolve("binary.jar");
            int packaged = java.util.spi.ToolProvider.findFirst("jar").orElseThrow().run(System.out, System.err,
                    "--create", "--file", jar.toString(), "-C", f.root().resolve("out/work.archaic.example").toString(), ".");
            assert packaged == 0 : "The JDK must create the explicit binary module fixture";
            var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var key = f.root().resolve("private.pk8"); Files.write(key, keys.getPrivate().getEncoded());
            f.write("knit.xml", "<knit version=\"1\"><signing publisher=\"archaic.work\" key-id=\"test\" private-key-env=\"KNIT_TEST_KEY\"/></knit>");
            var result = f.knitEnvironment(List.of("sign", jar.toString()), Map.of("KNIT_TEST_KEY", key.toString())); trail.note(result.output());
            assert result.exit() == 0 && Signing.check(jar, "work.archaic.example", keys.getPublic()) : "Standalone signing must read compiled JPMS descriptors";
        }
    }
}

record DescriptorMismatch() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("src/work.archaic.example/module-info.java", "module work.archaic.example {}");
            var packaged = f.packageModule("work.archaic.example");
            assert packaged.exit() == 0 : "An unsigned identity fixture must package";
            var jar = f.packaged(packaged);
            var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            // Low-level API callers establish identity; deliberately violate that precondition.
            Signing.sign(jar, new work.archaic.knit.signing.Metadata("work.archaic.other", "archaic.work", "test"), keys.getPrivate());
            var result = f.knitCommand(List.of("verify", jar.toString())); trail.note(result.output());
            assert result.exit() == 2 && result.output().contains("Signing namespace disagrees with module descriptor") : "CLI verification must establish descriptor identity before DNS authorization";
        }
    }
}
