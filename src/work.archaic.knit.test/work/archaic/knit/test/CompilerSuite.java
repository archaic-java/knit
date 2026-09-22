package work.archaic.knit.test;

import java.nio.file.Files;
import java.util.Collection;
import java.util.Map;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;
import work.archaic.service.test.v02.TestTrail;

public record CompilerSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new CompileArchive());
        cases.add(new PreserveOutput());
        cases.add(new WarningsFail());
        cases.add(new ServiceProvider());
        for (String kind : new String[]{"missing", "tampered", "collision", "name", "indirect", "format", "minimum", "binary", "path", "diagnostic", "unreferenced"})
            cases.add(new RejectArchive(kind));
        for (String xml : new String[]{
                "<knit version=\"2\"/>", "<knit version=\"1\"><compiler processor=\"anything\"/></knit>",
                "<knit version=\"1\"><compiler werror=\"yes\"/></knit>",
                "<knit version=\"1\"><compiler release=\"999\"/></knit>",
                "<!DOCTYPE knit [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><knit version=\"1\">&x;</knit>",
                "<knit version=\"1\"><compiler><option value=\"-Xplugin:evil\"/></compiler></knit>",
                "<knit version=\"1\"><compiler/><compiler/></knit>"}) cases.add(new RejectConfig(xml));
        for (String output : new String[]{".", "src", "src/example.app/nested", ".git", "cache", "../outside"})
            cases.add(new UnsafeOutput(output));
        cases.add(new UnownedOutput());
        cases.add(new SelectModule());
        cases.add(new AutomaticBinary());
        cases.add(new ExplicitBinary());
        cases.add(new SymlinkOutput());
        cases.add(new DuplicateSourceRoot());
        cases.add(new DuplicateDependency());
        cases.add(new InvalidDescriptor());
    }
}

record CompileArchive() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var fixture = Fixture.create()) {
            fixture.app("requires example.dep;", "System.out.print(dep.Api.value());");
            var jar = fixture.jar(Map.of("module-info.java", "module example.dep { exports dep; }",
                    "dep/Api.java", "package dep; public class Api { public static String value() { return \"archive\"; } }"), "1", "25");
            fixture.config("release=\"25\"", fixture.dependency(jar, "example.dep", "https://invalid.example/unused", true));
            var result = fixture.knit("compile");
            trail.note(result.output());
            assert result.exit() == 0 : "A local application must compile with archived module sources, including paths with spaces";
            var run = fixture.launch("example.app/example.Main");
            assert run.exit() == 0 && run.output().equals("archive") : "The compiled application must call the archived dependency";
            assert Files.exists(fixture.root().resolve("out/example.dep/module-info.class")) : "Archived sources must produce an explicit module";
            try (var files = Files.walk(fixture.cache())) {
                assert files.noneMatch(path -> path.toString().endsWith(".java")) : "Compilation must not extract source JARs into the cache";
            }
        }
    }
}

record PreserveOutput() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "System.out.print(\"good\");"); f.config("", "");
            f.write("src/example.app/example/Obsolete.java", "package example; class Obsolete {}");
            var initial = f.knit("compile"); trail.note(initial.output());
            assert initial.exit() == 0 : "Initial compilation must succeed";
            f.write("src/example.app/example/Main.java", "package example; public class Main { invalid }");
            var failed = f.knit("compile");
            assert failed.exit() == 1 : "Invalid Java must return the compilation failure status";
            var old = f.launch("example.app/example.Main");
            assert old.output().equals("good") : "A failed compilation must preserve the previous complete output";
            f.app("", "System.out.print(\"new\");");
            Files.delete(f.root().resolve("src/example.app/example/Obsolete.java"));
            assert f.knit("compile").exit() == 0 : "A corrected application must compile";
            assert !Files.exists(f.root().resolve("out/example.app/example/Obsolete.class")) : "Deleted sources must not leave stale classes";
        }
    }
}

record WarningsFail() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "System.out.print(Old.value());");
            f.write("src/example.app/example/Old.java", "package example; @Deprecated class Old { static int value() { return 1; } }");
            f.config("lint=\"all\" werror=\"true\"", "");
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 1 : "-Werror must make the compiler task fail";
            assert result.output().contains("warning [compiler.warn.") : "Warnings must retain their compiler codes";
            assert !Files.exists(f.root().resolve("out")) : "Warning-as-error failure must not publish output";
        }
    }
}

record RejectArchive(String kind) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("requires example.dep;", "");
            var entries = new java.util.LinkedHashMap<String,String>();
            entries.put("module-info.java", switch (kind) {
                case "name" -> "module wrong.name {}";
                case "indirect" -> "module example.dep { requires absent.module; }";
                default -> "module example.dep {}";
            });
            if (kind.equals("binary")) entries.put("hidden.class", "binary");
            if (kind.equals("path")) entries.put("../escape.java", "class Escape {}");
            if (kind.equals("diagnostic") || kind.equals("unreferenced")) entries.put("dep/Broken.java", "package dep; class Broken {\n\tString ü = missing;\n}");
            var jar = f.jar(entries, kind.equals("format") ? "2" : "1", kind.equals("minimum") ? "999" : "25");
            String declaration = f.dependency(jar, "example.dep", "https://invalid.example/unused", !kind.equals("missing"));
            if (kind.equals("tampered")) {
                try (var paths = Files.walk(f.cache())) {
                    var cached = paths.filter(p -> p.getFileName().toString().equals("artifact.jar")).findFirst().orElseThrow();
                    Files.writeString(cached, "changed");
                }
            }
            if (kind.equals("collision")) f.write("src/example.dep/module-info.java", "module example.dep {}");
            if (kind.equals("unreferenced")) f.app("", "");
            f.config("", declaration);
            var result = f.knit("compile"); trail.note(result.output());
            int expected = java.util.Set.of("indirect", "diagnostic", "unreferenced").contains(kind) ? 1 : 2;
            assert result.exit() == expected : "Invalid dependency must fail in the expected input or compiler phase: " + kind;
            assert !Files.exists(f.root().resolve("out")) : "Invalid dependencies must not publish output";
            if (kind.equals("diagnostic")) {
                assert result.output().contains("example.dep!/dep/Broken.java:2:") : "Archive diagnostics must identify the module and entry";
                assert result.output().contains("String ü = missing;") : "Archive diagnostics must include source text";
            }
            if (kind.equals("missing")) assert result.output().contains("knit fetch") : "Missing cached dependencies must explain recovery without downloading";
        }
    }
}

record RejectConfig(String xml) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", ""); f.write("knit.xml", xml);
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 2 : "Unsupported or unsafe XML must be an input failure";
            assert !Files.exists(f.root().resolve("out")) : "Invalid settings must fail before producing output";
        }
    }
}

record UnsafeOutput(String output) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", ""); f.config("output=\"" + output + "\"", "");
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 2 : "Unsafe output must be rejected: " + output;
            assert Files.exists(f.root().resolve("src/example.app/module-info.java")) : "Output validation must preserve source inputs";
        }
    }
}

record UnownedOutput() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", ""); f.config("", ""); f.write("out/important", "keep");
            var result = f.knit("compile");
            assert result.exit() == 2 : "Nonempty output without Knit ownership must be rejected";
            assert Files.readString(f.root().resolve("out/important")).equals("keep") : "Unrelated output contents must survive";
        }
    }
}

record SelectModule() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            f.write("other sources/example.unselected/module-info.java", "module example.unselected {}");
            f.write("other sources/example.unselected/broken/Bad.java", "package broken; class Bad { invalid }");
            f.write("knit.xml", "<knit version=\"1\"><compiler release=\"25\" output=\"build classes\"><source-root path=\"src\"/><source-root path=\"other sources\"/><module name=\"example.app\"/></compiler></knit>");
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 0 : "Explicit local roots must exclude unrelated modules from compilation";
            assert Files.exists(f.root().resolve("build classes/example.app/module-info.class")) : "Configured output with spaces must work";
        }
    }
}

record AutomaticBinary() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            var jar = f.jar(Map.of("x.txt", "resource"), "1", "25");
            f.write("knit.xml", "<knit version=\"1\"><compiler><module-path path=\"" + jar.getFileName() + "\"/></compiler></knit>");
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 2 && result.output().contains("Automatic modules") : "Binary inputs must be explicit JPMS modules";
        }
    }
}

record ServiceProvider() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("src/example.catalog/module-info.java", "module example.catalog { exports catalog; }");
            f.write("src/example.catalog/catalog/Service.java", "package catalog; public interface Service { String value(); }");
            f.app("requires example.catalog; uses catalog.Service;", "System.out.print(java.util.ServiceLoader.load(catalog.Service.class).findFirst().orElseThrow().value());");
            var jar = f.jar(Map.of("module-info.java", "module example.provider { requires example.catalog; provides catalog.Service with provider.Implementation; }",
                    "provider/Implementation.java", "package provider; public class Implementation implements catalog.Service { public String value() { return \"provider\"; } }"), "1", "25");
            f.config("", f.dependency(jar, "example.provider", "https://invalid.example/unused", true));
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 0 : "An explicitly selected provider must compile even without a requires edge to it";
            var run = f.launch("example.app/example.Main");
            assert run.exit() == 0 && run.output().equals("provider") : "ServiceLoader must discover the selected compiled provider";
        }
    }
}

record ExplicitBinary() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            var descriptor = f.write("binary-src/module-info.java", "module example.binary { exports binary; }");
            var api = f.write("binary-src/binary/Api.java", "package binary; public class Api { public static int value() { return 42; } }");
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            int status = compiler.run(null, null, null, "-d", f.root().resolve("binary-classes").toString(), descriptor.toString(), api.toString());
            assert status == 0 : "The binary fixture must be a compilable explicit module";
            var jar = f.root().resolve("binary.jar");
            try (var sink = new java.util.jar.JarOutputStream(Files.newOutputStream(jar));
                    var files = Files.walk(f.root().resolve("binary-classes"))) {
                for (var path : files.filter(Files::isRegularFile).toList()) {
                    sink.putNextEntry(new java.util.jar.JarEntry(f.root().resolve("binary-classes").relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, sink);
                    sink.closeEntry();
                }
            }
            f.app("requires example.binary;", "System.out.print(binary.Api.value());");
            String dependency = f.dependency(jar, "example.binary", "https://invalid.example/unused", true).replace("kind=\"source\"", "kind=\"binary\"");
            f.config("", dependency);
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 0 : "Pinned explicit binary modules must participate in compilation";
            f.config("", dependency.replace("example.binary", "wrong.name"));
            var mismatch = f.knit("compile");
            assert mismatch.exit() == 2 && mismatch.output().contains("artifact declares example.binary") : "Binary identity must match the declaration";
        }
    }
}

record SymlinkOutput() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", ""); f.config("output=\"linked/classes\"", "");
            Files.createSymbolicLink(f.root().resolve("linked"), f.root().resolve("src"));
            var result = f.knit("compile");
            assert result.exit() == 2 : "Output must reject symlink ancestors before modifying inputs";
            assert !Files.exists(f.root().resolve("src/classes")) : "Rejected output must not create directories through a symlink";
        }
    }
}

record DuplicateSourceRoot() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            Files.createSymbolicLink(f.root().resolve("linked"), f.root().resolve("src"));
            f.write("knit.xml", "<knit version=\"1\"><compiler><source-root path=\"src\"/><source-root path=\"linked\"/></compiler></knit>");
            var result = f.knit("compile");
            assert result.exit() == 2 && result.output().contains("Duplicate source root") : "Aliases of the same source root must be rejected";
        }
    }
}

record DuplicateDependency() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            var jar = f.jar(Map.of("module-info.java", "module example.dep {}"), "1", "25");
            String dependency = f.dependency(jar, "example.dep", "https://invalid.example/unused", false);
            f.config("", dependency + dependency);
            var result = f.knit("fetch");
            assert result.exit() == 2 && result.output().contains("Duplicate dependency") : "Duplicate module selections must fail before acquisition";
            assert !Files.exists(f.cache()) : "Invalid selections must not trigger downloads";
        }
    }
}

record InvalidDescriptor() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", ""); f.config("", "");
            f.write("src/example.app/module-info.java", "module example.app { requires ; }");
            var result = f.knit("compile"); trail.note(result.output());
            assert result.exit() == 1 && result.output().contains("compiler.err.") : "Malformed descriptors must retain Java compiler diagnostics and failure status";
        }
    }
}
