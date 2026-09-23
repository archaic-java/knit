package work.archaic.knit.test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

record Fixture(Path root, Path cache) implements AutoCloseable {
    static final Path MODULES = Path.of("out").toAbsolutePath();
    static Fixture create() throws IOException {
        Path root = Files.createTempDirectory("knit test ü ");
        return new Fixture(root, root.resolve("cache"));
    }
    Path write(String path, String value) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        return Files.writeString(target, value);
    }
    void app(String requires, String body) throws IOException {
        write("src/example.app/module-info.java", "module example.app { " + requires + " }");
        write("src/example.app/example/Main.java", "package example; public class Main { public static void main(String[] args) { " + body + " } }");
    }
    void config(String settings, String dependencies) throws IOException {
        write("knit.xml", "<knit version=\"1\"><compiler " + settings + "/>" + dependencies + "</knit>");
    }
    Path jar(Map<String, String> entries, String format, String minimum) throws IOException {
        Path jar = Files.createTempFile(root, "sources-", ".jar");
        var manifest = new Manifest();
        var values = manifest.getMainAttributes();
        values.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        values.putValue("Archaic-Source-Format", format);
        values.putValue("Archaic-Minimum-JDK", minimum);
        try (var stream = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            for (var entry : entries.entrySet()) {
                stream.putNextEntry(new JarEntry(entry.getKey()));
                stream.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stream.closeEntry();
            }
        }
        return jar;
    }
    String dependency(Path jar, String module, String url, boolean cached) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        if (cached) {
            Path entry = cache.resolve("sha256").resolve(hash).resolve("artifact.jar");
            Files.createDirectories(entry.getParent());
            Files.copy(jar, entry);
        }
        return "<dependency module=\"" + module + "\" kind=\"source\" url=\"" + url + "\" sha256=\"" + hash + "\"/>";
    }
    Result knit(String command, String... properties) throws Exception {
        return knitCommand(java.util.List.of(command), properties);
    }
    Result packageModule(String module) throws Exception {
        return knitCommand(java.util.List.of("package", module));
    }
    Result knitCommand(java.util.List<String> command, String... properties) throws Exception {
        var args = new java.util.ArrayList<String>();
        args.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        args.add("-Xshare:off");
        args.addAll(java.util.List.of(properties));
        args.addAll(java.util.List.of("--module-path", MODULES.toString(), "--module", "work.archaic.knit/work.archaic.knit.Main"));
        args.addAll(command);
        var builder = new ProcessBuilder(args).directory(root.toFile());
        builder.environment().put("KNIT_CACHE", cache.toString());
        return execute(builder);
    }
    Result launch(String entry) throws Exception {
        return execute(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xshare:off", "--module-path", root.resolve("out").toString(), "--module", entry).directory(root.toFile()));
    }
    static Result execute(ProcessBuilder builder) throws Exception {
        Path capture = Files.createTempFile("knit-capture-", ".txt");
        try {
            var process = builder.redirectErrorStream(true).redirectOutput(capture.toFile()).start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor();
                throw new IOException("Subprocess timed out");
            }
            return new Result(process.exitValue(), Files.readString(capture));
        } finally { Files.deleteIfExists(capture); }
    }
    @Override public void close() throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    record Result(int exit, String output) {}
}
