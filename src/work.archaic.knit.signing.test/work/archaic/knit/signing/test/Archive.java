package work.archaic.knit.signing.test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

record Archive(Path path) implements AutoCloseable {
    static Archive create() throws Exception {
        var result = new Archive(Files.createTempFile("knit-signing-test-", ".jar"));
        result.write(Map.of("module-info.java", "module work.archaic.example {}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 9);
        return result;
    }
    Map<String, byte[]> contents() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        try (var jar = new JarFile(path.toFile(), false)) {
            for (var entry : jar.stream().toList()) try (var in = jar.getInputStream(entry)) { entries.put(entry.getName(), in.readAllBytes()); }
        }
        return entries;
    }
    void write(Map<String, byte[]> entries, int level) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(path))) {
            out.setLevel(level);
            for (var entry : entries.entrySet()) {
                var item = new JarEntry(entry.getKey()); item.setTime(1_500_000_000_000L);
                out.putNextEntry(item); out.write(entry.getValue()); out.closeEntry();
            }
        }
    }
    @Override public void close() throws java.io.IOException { Files.deleteIfExists(path); }
}
