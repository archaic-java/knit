package work.archaic.knit;

import com.sun.source.util.JavacTask;
import java.lang.module.ModuleFinder;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Establishes the actual module identity independently of signing metadata. */
final class ArtifactIdentity {
    private ArtifactIdentity() {}
    static String read(Path path) throws Exception {
        work.archaic.knit.signing.Signing.inspect(path);
        if (!SourceArchive.kind(path).equals("source")) {
            var modules = ModuleFinder.of(path).findAll();
            if (modules.size() != 1 || modules.iterator().next().descriptor().isAutomatic())
                throw new InputFailure("Signing requires an explicit module descriptor");
            String name = modules.iterator().next().descriptor().name(); Project.moduleName(name); return name;
        }
        SourceArchive.validate(path);
        String text;
        try (var jar = new JarFile(path.toFile(), false); var input = jar.getInputStream(jar.getJarEntry("module-info.java"))) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new InputFailure("Module descriptor exceeds 64 KiB");
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new InputFailure("A full JDK is required to read source module identity");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var source = new SimpleJavaFileObject(URI.create("string:///module-info.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return text; }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(source));
            String name = null;
            for (var unit : task.parse()) if (unit.getModule() != null) name = unit.getModule().getName().toString();
            if (name == null || diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR))
                throw new InputFailure("Malformed source module descriptor");
            Project.moduleName(name); return name;
        }
    }
}
