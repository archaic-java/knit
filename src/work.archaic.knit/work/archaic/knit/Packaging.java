package work.archaic.knit;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;

/** Creates one source distribution without compiling or resolving its dependencies. */
final class Packaging {
    // Stay away from the ZIP epoch boundary, which triggers timezone-dependent extended timestamps.
    private static final LocalDateTime ARCHIVE_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private Packaging() {}

    static void run(Project project, String module, PrintWriter output) throws Exception {
        Project.moduleName(module);
        Path source = source(project, module);
        validateDescriptor(project, module, source, output);
        var payload = payload(project.root(), source);
        Path dist = project.root().resolve("dist");
        if (Files.isSymbolicLink(dist) || (Files.exists(dist) && !Files.isDirectory(dist)))
            throw new InputFailure("dist must be a regular directory, not a file or symbolic link");
        Path realDist = Output.canonical(dist);
        if (source.startsWith(realDist) || realDist.startsWith(source))
            throw new InputFailure("Package output overlaps module sources");
        Files.createDirectories(dist);
        Path target = dist.resolve(module + ".knit.jar");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new InputFailure("Package output must be a regular file: " + target);
        Path temporary = Files.createTempFile(dist, ".knit-package-", ".tmp");
        try {
            var manifest = new Manifest();
            var attributes = manifest.getMainAttributes();
            attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
            attributes.putValue("Archaic-Source-Format", "1");
            // The existing archive format requires a minimum even when the project omits one.
            int minimum = project.minimumJdk() == 0 ? Runtime.version().feature() : project.minimumJdk();
            attributes.putValue("Archaic-Minimum-JDK", Integer.toString(minimum));
            var digest = MessageDigest.getInstance("SHA-256");
            try (var bytes = new DigestOutputStream(Files.newOutputStream(temporary), digest);
                    var jar = new JarOutputStream(bytes)) {
                jar.putNextEntry(entry("META-INF/MANIFEST.MF"));
                manifest.write(jar);
                jar.closeEntry();
                for (var file : payload.entrySet()) {
                    jar.putNextEntry(entry(file.getKey()));
                    Files.copy(file.getValue(), jar);
                    jar.closeEntry();
                }
            }
            SourceArchive.validate(temporary);
            String hash = HexFormat.of().formatHex(digest.digest());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            output.println("Packaged " + project.root().relativize(target));
            output.println("sha256: " + hash);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Path source(Project project, String module) throws IOException, InputFailure {
        Path selected = null;
        for (Path root : project.sourceRoots()) {
            Path candidate = root.resolve(module);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isDirectory(candidate) || !Files.isRegularFile(candidate.resolve("module-info.java")))
                throw new InputFailure("Not a source module: " + candidate);
            if (selected != null) throw new InputFailure("Duplicate module: " + module);
            selected = candidate.toRealPath();
        }
        if (selected == null) throw new InputFailure("No local source module named " + module + " in src or lib/src");
        return selected;
    }

    private static void validateDescriptor(Project project, String module, Path source, PrintWriter output) throws Exception {
        if (Files.isSymbolicLink(source.resolve("module-info.java")))
            throw new InputFailure("Package inputs cannot contain symbolic links: module-info.java");
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new InputFailure("Knit requires a full JDK with its Java compiler");
        var messages = new Messages(output, project.root());
        try (var files = compiler.getStandardFileManager(messages, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var units = files.getJavaFileObjectsFromPaths(List.of(source.resolve("module-info.java")));
            var task = (JavacTask) compiler.getTask(output, files, messages, List.of("-proc:none"), null, units);
            String declared = null;
            for (var unit : task.parse()) {
                if (messages.hasErrors()) throw new InputFailure("Cannot package a malformed module descriptor");
                if (unit.getModule() != null) declared = unit.getModule().getName().toString();
            }
            if (!module.equals(declared)) throw new InputFailure("Module descriptor does not match expected module " + module);
        }
    }

    private static TreeMap<String, Path> payload(Path project, Path source) throws IOException, InputFailure {
        var payload = new TreeMap<String, Path>();
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new InputFailure("Package inputs cannot contain symbolic links: " + path);
                if (Files.isDirectory(path)) continue;
                String name = source.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                if (!Files.isRegularFile(path) || (!(name.endsWith(".java") && !name.startsWith("META-INF/"))
                        && !SourceArchive.legalNotice(name)))
                    throw new InputFailure("Unsupported source package input: " + name + "; only Java sources and legal notices are supported");
                payload.put(name, path);
            }
        }
        // Include project legal notices, preserving a module's more specific notice of the same name.
        try (var paths = Files.list(project)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!SourceArchive.legalNotice(name) || payload.containsKey(name)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    throw new InputFailure("Project legal notice must be a regular file: " + name);
                payload.put(name, path);
            }
        }
        return payload;
    }

    private static JarEntry entry(String name) {
        var entry = new JarEntry(name);
        entry.setTimeLocal(ARCHIVE_TIME);
        return entry;
    }
}
