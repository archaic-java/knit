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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        var roots = new LinkedHashSet<String>();
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
            if (project.modules().isEmpty()) roots.addAll(sourceModules.keySet());
            else {
                for (String name : project.modules()) {
                    if (!sourceModules.containsKey(name)) throw new InputFailure("Unknown local compile root: " + name);
                    roots.add(name);
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
                if (dependency.kind().equals("binary")) {
                    addBinary(jar, dependency.module(), binaryNames, binaryPaths, binaryLocations);
                } else {
                    SourceArchive.validate(jar);
                    var archive = FileSystems.newFileSystem(jar);
                    archives.add(archive);
                    Path root = archive.getPath("/");
                    if (sourceModules.putIfAbsent(dependency.module(), root) != null)
                        throw new InputFailure("Duplicate module: " + dependency.module());
                    messages.archive(root, dependency.module());
                    roots.add(dependency.module());
                }
            }
            for (String name : sourceModules.keySet())
                if (binaryNames.contains(name)) throw new InputFailure("Source/binary module collision: " + name);
            if (roots.isEmpty()) throw new InputFailure("No source modules selected");
            // Parse descriptors with the public tree API; Java remains the compiler's language.
            for (var entry : sourceModules.entrySet()) {
                var units = files.getJavaFileObjectsFromPaths(List.of(entry.getValue().resolve("module-info.java")));
                var task = (JavacTask) compiler.getTask(output, files, messages, List.of("-proc:none"), null, units);
                for (var unit : task.parse()) {
                    if (messages.hasErrors()) throw new CompilationFailure();
                    if (unit.getModule() == null || !unit.getModule().getName().toString().equals(entry.getKey()))
                        throw new InputFailure("Module descriptor does not match expected module " + entry.getKey());
                }
            }
            if (messages.hasErrors()) throw new CompilationFailure();
            for (var entry : sourceModules.entrySet())
                files.setLocationForModule(StandardLocation.MODULE_SOURCE_PATH, entry.getKey(), List.of(entry.getValue()));
            files.setLocationFromPaths(StandardLocation.MODULE_PATH, binaryPaths);
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            try (var destination = new Output(project, protectedPaths, artifacts.root())) {
                var options = new ArrayList<>(List.of("-proc:none", "-encoding", "UTF-8", "-d", destination.staging().toString(),
                        "--module", String.join(",", roots)));
                if (!project.release().isEmpty()) { options.add("--release"); options.add(project.release()); }
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
