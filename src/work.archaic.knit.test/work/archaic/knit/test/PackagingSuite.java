package work.archaic.knit.test;

import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;
import work.archaic.service.test.v02.TestTrail;

public record PackagingSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new PackageRoundTrip());
        cases.add(new RepeatablePackage());
        cases.add(new PackageLinkedModule());
        for (String kind : List.of("missing", "name", "syntax", "duplicate", "resource", "binary", "symlink", "output-link", "target-link"))
            cases.add(new RejectPackage(kind));
        for (var command : List.of(List.of("package"), List.of("package", "example.app", "extra"), List.of("package", "../outside")))
            cases.add(new PackageArguments(command));
    }
}

record PackageRoundTrip() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var author = Fixture.create(); var consumer = Fixture.create()) {
            author.write("src/example.dep/module-info.java", "module example.dep { exports dep; }");
            author.write("src/example.dep/dep/Api.java", "package dep; public class Api { public static int value() { return 42; } }");
            author.write("LICENSE", "project license");
            author.write("NOTICE", "project notice");
            author.write("src/example.dep/NOTICE", "module notice");
            author.write("src/example.other/module-info.java", "module example.other {}");
            // Packaging needs neither cached dependencies nor previously compiled output.
            author.config("minimum-jdk=\"9\"", "<dependency url=\"https://invalid.example/missing.knit.jar\" sha256=\"" + "0".repeat(64) + "\"/>");
            Instant before = Instant.now();
            var result = author.packageModule("example.dep"); trail.note(result.output());
            Instant after = Instant.now();
            assert result.exit() == 0 : "An explicit source module must package without compiling or acquiring dependencies";
            var archive = author.packaged(result);
            var utc = DateTimeFormatter.ofPattern("yyMMdd-HHmm", Locale.ROOT).withZone(ZoneOffset.UTC);
            assert archive.getFileName().toString().matches("example\\.dep-[0-9]{6}-[0-9]{4}\\.knit\\.jar")
                    : "Package filenames must show a UTC minute with a two-digit year";
            assert archive.getFileName().toString().equals("example.dep-" + utc.format(before) + ".knit.jar")
                    || archive.getFileName().toString().equals("example.dep-" + utc.format(after) + ".knit.jar")
                    : "The filename must use the UTC minute in which packaging began";
            assert Files.isRegularFile(archive) : "The published source archive must have the .knit.jar extension";
            assert !Files.exists(author.root().resolve("out")) && !Files.exists(author.cache()) : "Packaging must not compile or fetch";
            assert !Files.exists(author.root().resolve("src/example.dep/META-INF")) : "Packaging must not introduce metadata files into source modules";
            try (var jar = new JarFile(archive.toFile())) {
                var names = jar.stream().map(entry -> entry.getName()).toList();
                assert names.equals(List.of("META-INF/MANIFEST.MF", "LICENSE", "NOTICE", "dep/Api.java", "module-info.java"))
                        : "The package must contain exactly one module, generated metadata, and its legal notices";
                assert jar.getManifest().getMainAttributes().getValue("Archaic-Minimum-JDK").equals("9")
                        : "An explicit project minimum must be preserved in the distribution";
                try (var notice = jar.getInputStream(jar.getJarEntry("NOTICE"))) {
                    assert new String(notice.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).equals("module notice")
                            : "A module-specific legal notice must take precedence over the same project notice";
                }
            }
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive)));
            assert result.output().contains("sha256: " + digest) : "Packaging must report the digest of the finished archive bytes";
            consumer.app("requires example.dep;", "System.out.print(dep.Api.value());");
            String dependency = consumer.dependency(archive, "example.dep", "https://invalid.example/example.dep.knit.jar", true)
                    .replace("module=\"example.dep\" ", "").replace("kind=\"source\" ", "");
            consumer.config("", dependency);
            var compiled = consumer.knit("compile"); trail.note(compiled.output());
            assert compiled.exit() == 0 : "The generated .knit.jar must be consumable with only URL and digest metadata";
            var run = consumer.launch("example.app/example.Main");
            assert run.exit() == 0 && run.output().equals("42") : "A consumer must execute code compiled directly from the packaged sources";
        }
    }
}

record RepeatablePackage() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            var first = f.packageModule("example.app"); trail.note(first.output());
            assert first.exit() == 0 : "A conventional module must package without knit.xml";
            var artifact = f.packaged(first);
            byte[] original = Files.readAllBytes(artifact);
            try (var jar = new JarFile(artifact.toFile())) {
                assert jar.getManifest().getMainAttributes().getValue("Archaic-Minimum-JDK").equals(Integer.toString(Runtime.version().feature()))
                        : "Without a declared minimum, packaging must record the current JDK rather than guess an older compatible release";
            }
            Files.setLastModifiedTime(f.root().resolve("src/example.app/example/Main.java"), FileTime.fromMillis(1_000_000));
            var repeated = f.knitCommand(List.of("package", "example.app"), "-Duser.timezone=Pacific/Auckland");
            assert repeated.exit() == 0 && Arrays.equals(original, Files.readAllBytes(f.packaged(repeated)))
                    : "Unchanged inputs must produce identical archives despite source timestamps and timezone";
            f.write("src/example.app/example/Main.java", "package example; public class Main { public static void main(String[] args) { System.out.print(1); } }");
            var changedResult = f.packageModule("example.app");
            assert changedResult.exit() == 0 : "Repackaging changed sources must publish a new package";
            var changedArtifact = f.packaged(changedResult);
            byte[] changed = Files.readAllBytes(changedArtifact);
            assert !Arrays.equals(original, changed) : "Changed source content must change the artifact";
            long packagesBeforeFailure;
            try (var files = Files.list(f.root().resolve("dist"))) { packagesBeforeFailure = files.count(); }
            f.write("src/example.app/config.properties", "runtime.resource=true");
            assert f.packageModule("example.app").exit() == 2 : "Unsupported runtime resources must not be silently dropped";
            assert Arrays.equals(changed, Files.readAllBytes(changedArtifact)) : "Failed packaging must preserve the previous complete artifact";
            try (var files = Files.list(f.root().resolve("dist"))) {
                assert files.count() == packagesBeforeFailure : "Packaging must leave no temporary artifacts behind";
            }
        }
    }
}

record PackageLinkedModule() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("sibling/example.linked/module-info.java", "module example.linked { requires missing.dependency; }");
            Files.createDirectories(f.root().resolve("lib/src"));
            Files.createSymbolicLink(f.root().resolve("lib/src/example.linked"), f.root().resolve("sibling/example.linked"));
            var result = f.packageModule("example.linked"); trail.note(result.output());
            assert result.exit() == 0 : "Explicitly selected linked modules must package without dependency resolution";
            assert Files.isRegularFile(f.packaged(result)) : "Linked modules use the same output naming convention";
        }
    }
}

record RejectPackage(String kind) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            switch (kind) {
                case "missing" -> { }
                case "name" -> f.write("src/example.app/module-info.java", "module wrong.name {}");
                case "syntax" -> f.write("src/example.app/module-info.java", "module example.app { requires ; }");
                case "duplicate" -> f.write("lib/src/example.app/module-info.java", "module example.app {}");
                case "resource" -> f.write("src/example.app/data.json", "{}");
                case "binary" -> f.write("src/example.app/Unexpected.class", "not source");
                case "symlink" -> {
                    var external = f.write("outside.java", "class Outside {}");
                    Files.createSymbolicLink(f.root().resolve("src/example.app/Linked.java"), external);
                }
                case "output-link" -> Files.createSymbolicLink(f.root().resolve("dist"), f.root().resolve("src/example.app"));
                case "target-link" -> {
                    Files.createDirectories(f.root().resolve("dist"));
                    var utc = DateTimeFormatter.ofPattern("yyMMdd-HHmm", Locale.ROOT).withZone(ZoneOffset.UTC);
                    for (int offset = -1; offset <= 1; offset++)
                        Files.createSymbolicLink(f.root().resolve("dist/example.app-" + utc.format(Instant.now().plusSeconds(60L * offset)) + ".knit.jar"),
                                f.root().resolve("src/example.app/module-info.java"));
                }
                default -> throw new IllegalArgumentException(kind);
            }
            byte[] descriptor = Files.readAllBytes(f.root().resolve("src/example.app/module-info.java"));
            var result = f.packageModule(kind.equals("missing") ? "absent.module" : "example.app"); trail.note(result.output());
            assert result.exit() == 2 : "Invalid packaging inputs must be rejected: " + kind;
            assert Arrays.equals(descriptor, Files.readAllBytes(f.root().resolve("src/example.app/module-info.java")))
                    : "Rejected packaging must not change sources";
        }
    }
}

record PackageArguments(List<String> command) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            var result = f.knitCommand(command);
            assert result.exit() == 2 : "Packaging requires exactly one valid module name";
            assert !Files.exists(f.root().resolve("dist")) : "Invalid arguments must not create package output";
        }
    }
}
