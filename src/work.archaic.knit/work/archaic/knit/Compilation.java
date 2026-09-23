package work.archaic.knit;

import com.sun.source.util.JavacTask;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.module.FindException;
import java.lang.module.ModuleFinder;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

final class Compilation {
    private Compilation() {}

    static void run(Project project, Artifacts artifacts, PrintWriter output) throws Exception {
        try (var channel = FileChannel.open(project.root().resolve(".knit-compile.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new InputFailure("Another Knit compilation owns this project");
            compile(project, artifacts, output);
        }
    }

    private static void compile(Project project, Artifacts artifacts, PrintWriter output) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new InputFailure("Knit requires a full JDK with its Java compiler");
        var messages = new Messages(output, project.root());
        var sourceModules = new LinkedHashMap<String, Path>();
        var binaryNames = new HashSet<String>();
        var binaryPaths = new ArrayList<Path>();
        var protectedPaths = new ArrayList<Path>();
        protectedPaths.add(project.root().resolve("knit.xml"));
        var archives = new ArrayList<FileSystem>();
        try (var files = compiler.getStandardFileManager(messages, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var sourcePaths = new HashSet<Path>();
            for (Path root : project.sourceRoots()) {
                if (!Files.isDirectory(root)) throw new InputFailure("Missing source root: " + root);
                root = root.toRealPath();
                if (!sourcePaths.add(root)) throw new InputFailure("Duplicate source root: " + root);
                protectedPaths.add(root);
                try (var children = Files.list(root)) {
                    for (Path module : children.sorted().toList()) {
                        if (module.getFileName().toString().startsWith(".")) continue;
                        if (!Files.isDirectory(module) || !Files.isRegularFile(module.resolve("module-info.java")))
                            throw new InputFailure("Source roots must contain named module directories: " + module);
                        String name = module.getFileName().toString();
                        Project.moduleName(name);
                        Path real = module.toRealPath();
                        protectedPaths.add(real);
                        if (sourceModules.putIfAbsent(name, real) != null) throw new InputFailure("Duplicate module: " + name);
                    }
                }
            }
            var binaryLocations = new HashSet<Path>();
            for (Path path : project.modulePaths()) {
                if (!Files.exists(path)) throw new InputFailure("Missing module path: " + path);
                path = path.toRealPath();
                protectedPaths.add(path);
                if (Files.isDirectory(path) && !Files.isRegularFile(path.resolve("module-info.class"))) {
                    try (var children = Files.list(path)) {
                        for (Path entry : children.sorted().toList()) {
                            if (entry.getFileName().toString().startsWith(".")) continue;
                            addBinary(entry, null, binaryNames, binaryPaths, binaryLocations);
                        }
                    }
                } else addBinary(path, null, binaryNames, binaryPaths, binaryLocations);
            }
            for (var dependency : project.dependencies()) {
                Path jar = artifacts.require(dependency);
                protectedPaths.add(jar);
                String kind = SourceArchive.kind(jar);
                if (!dependency.kind().isEmpty() && !dependency.kind().equals(kind))
                    throw new InputFailure("Expected " + dependency.kind() + " artifact for " + dependency.label() + " but found " + kind);
                if (kind.equals("binary")) {
                    addBinary(jar, dependency.module().isEmpty() ? null : dependency.module(), binaryNames, binaryPaths, binaryLocations);
                } else {
                    SourceArchive.validate(jar);
                    var archive = FileSystems.newFileSystem(jar);
                    archives.add(archive);
                    Path root = archive.getPath("/");
                    messages.archive(root, dependency.label());
                    String name = sourceName(compiler, files, messages, output, root);
                    if (!dependency.module().isEmpty() && !dependency.module().equals(name))
                        throw new InputFailure("Module descriptor does not match expected module " + dependency.module());
                    if (sourceModules.putIfAbsent(name, root) != null)
                        throw new InputFailure("Duplicate module: " + name);
                    messages.archive(root, name);
                }
            }
            for (String name : sourceModules.keySet())
                if (binaryNames.contains(name)) throw new InputFailure("Source/binary module collision: " + name);
            if (sourceModules.isEmpty()) throw new InputFailure("No source modules selected");
            // Parse descriptors with the public tree API; Java remains the compiler's language.
            for (var entry : sourceModules.entrySet()) {
                if (!sourceName(compiler, files, messages, output, entry.getValue()).equals(entry.getKey()))
                    throw new InputFailure("Module descriptor does not match expected module " + entry.getKey());
            }
            if (messages.hasErrors()) throw new CompilationFailure();
            for (var entry : sourceModules.entrySet())
                files.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, entry.getKey(), List.of(entry.getValue()));
            files.setLocationFromPaths(StandardLocation.MODULE_PATH, binaryPaths);
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            try (var destination = new Output(project, protectedPaths, artifacts.root())) {
                var options = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8", "-d", destination.staging().toString(),
                        "--module", String.join(",", sourceModules.keySet())));
                if (!project.lint().isEmpty()) options.add("-Xlint:" + project.lint());
                if (project.werror()) options.add("-Werror");
                boolean success = compiler.getTask(output, files, messages, options, null, null).call();
                output.println(messages.summary());
                if (!success) throw new CompilationFailure();
                destination.publish();
                output.println("Compiled to " + project.root().relativize(project.output()));
            }
        } finally {
            IOException failure = null;
            for (var archive : archives) {
                try { archive.close(); } catch (IOException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }

    private static String sourceName(javax.tools.JavaCompiler compiler, javax.tools.StandardJavaFileManager files,
            Messages messages, PrintWriter output, Path root) throws Exception {
        var units = files.getJavaFileObjectsFromPaths(List.of(root.resolve("module-info.java")));
        var task = (JavacTask) compiler.getTask(output, files, messages, List.of("-proc:none"), null, units);
        String name = null;
        for (var unit : task.parse()) {
            if (messages.hasErrors()) throw new CompilationFailure();
            if (unit.getModule() != null) name = unit.getModule().getName().toString();
        }
        if (name == null) throw new InputFailure("Missing module declaration: " + root);
        Project.moduleName(name);
        return name;
    }

    private static void addBinary(Path path, String expected, java.util.Set<String> names, List<Path> paths,
            java.util.Set<Path> locations) throws IOException, InputFailure {
        path = path.toRealPath();
        if (!locations.add(path)) throw new InputFailure("Duplicate module path entry: " + path);
        try {
            var found = ModuleFinder.of(path).findAll();
            if (found.size() != 1) throw new InputFailure("Expected one explicit binary module: " + path);
            var descriptor = found.iterator().next().descriptor();
            if (descriptor.isAutomatic()) throw new InputFailure("Automatic modules are unsupported: " + path);
            String name = descriptor.name();
            Project.moduleName(name);
            if (expected != null && !expected.equals(name)) throw new InputFailure("Expected " + expected + " but artifact declares " + name);
            if (!names.add(name)) throw new InputFailure("Duplicate binary module: " + name);
            paths.add(path);
        } catch (FindException error) { throw new InputFailure("Invalid binary module " + path + ": " + error.getMessage()); }
    }
}
