package work.archaic.knit.test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;
import work.archaic.service.test.v02.TestTrail;

/** Exercise context output through the actual CLI and service-loaded provider. */
public record LoggingSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) {
        cases.add(new SuccessfulLogging(false));
        cases.add(new SuccessfulLogging(true));
        cases.add(new FailedCompilationLogging());
        cases.add(new MissingArtifactLogging());
        cases.add(new FailedPackagingLogging());
        cases.add(new InvalidProjectLogging());
        cases.add(new MissingLoggingProvider());
        cases.add(new BrokenLoggingProvider());
    }
}

record SuccessfulLogging(boolean debug) implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "System.out.print(\"ok\");");
            var result = f.knit("compile", "-Dknit.debug=" + debug);
            trail.note(result.output());
            assert result.exit() == 0 : "Logging configuration must preserve successful compilation";
            assert result.output().contains("Compiled to out") : "Normal progress must keep its CLI format";
            assert !result.output().contains("Command: compile") && !result.output().contains("Acquiring compilation lock")
                    : "Successful commands must discard failure evidence even in debug mode";
            assert result.output().contains("Source modules: example.app") == debug
                    : "Only enabled debug contexts must render lazy module details";
        }
    }
}

record FailedCompilationLogging() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "missing();");
            var result = f.knit("compile");
            trail.note(result.output());
            assert result.exit() == 1 : "Compiler failures must preserve exit status 1";
            assert result.output().contains("Main.java:1:") && result.output().contains("compiler.err.cant.resolve")
                    : "Compiler locations and diagnostic codes must remain visible";
            assert result.output().contains("work.archaic.knit.Main: Command: compile")
                    && result.output().contains("work.archaic.knit.Compilation: Compiling 1 source modules")
                    : "The command and operational object must contribute to the same failure context";
            assert result.output().lines().filter(line -> line.startsWith("knit: ")).count() == 1
                    : "Context completion must render the compiler failure exactly once";
            assert !result.output().contains("at work.archaic.knit/") : "Expected compiler failures must stay concise";
            assert result.output().indexOf("Command: compile") < result.output().indexOf("Compiling 1 source modules")
                    : "Failure evidence must preserve submission order";
        }
    }
}

record MissingArtifactLogging() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("requires example.dep;", "");
            var jar = f.jar(Map.of("module-info.java", "module example.dep {}"), "1", "25");
            f.config("", f.dependency(jar, "example.dep", "https://invalid.example/unused", false));
            var result = f.knit("compile");
            trail.note(result.output());
            assert result.exit() == 2 : "Missing artifacts must preserve the input failure status";
            assert result.output().contains("work.archaic.knit.Compilation: Resolving dependency example.dep")
                    && result.output().contains("work.archaic.knit.Artifacts: Checking cached artifact example.dep")
                    : "Compilation and artifact evidence must share one command context";
            assert result.output().lines().filter(line -> line.startsWith("knit: Missing artifact")).count() == 1
                    : "Missing artifact failures must be reported once";
            assert !result.output().contains("at work.archaic.knit/") : "Expected input failures must omit stack traces";
        }
    }
}

record FailedPackagingLogging() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.app("", "");
            var result = f.packageModule("example.absent");
            trail.note(result.output());
            assert result.exit() == 2 : "Missing package sources must preserve input failure status";
            assert result.output().contains("work.archaic.knit.Packaging: Packaging source module example.absent")
                    : "Packaging must retain its own operational evidence";
            assert result.output().lines().filter(line -> line.startsWith("knit: No local source module")).count() == 1
                    : "Packaging failures must be rendered exactly once";
        }
    }
}

record InvalidProjectLogging() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            f.write("knit.xml", "<knit version=\"invalid\"/>");
            var result = f.knit("fetch");
            trail.note(result.output());
            assert result.exit() == 2 : "Project parsing failures must retain exit status 2";
            assert result.output().contains("work.archaic.knit.Main: Command: fetch")
                    && result.output().contains("work.archaic.knit.Main: Project: ")
                    : "The context must encompass project parsing before operational objects exist";
            assert result.output().lines().filter(line -> line.startsWith("knit: ")).count() == 1
                    : "Early project failures must be reported once";
        }
    }
}

record MissingLoggingProvider() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            var result = f.knit("compile", "--limit-modules=work.archaic.knit");
            trail.note(result.output());
            assert result.exit() == 2 : "Missing logging provider must remain a clear installation error";
            assert result.output().lines().filter(line -> line.startsWith("knit: Expected exactly one Log provider")).count() == 1
                    && result.output().contains("install Culpa") : "Composition failures must render once without a context";
            var help = f.knitCommand(List.of("--help"), "--limit-modules=work.archaic.knit");
            assert help.exit() == 0 && help.output().startsWith("Usage: knit")
                    : "Help must not require a logging provider";
        }
    }
}

record BrokenLoggingProvider() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var f = Fixture.create()) {
            var descriptor = f.write("provider/module-info.java", "module example.logging { requires work.archaic.service.catalog; provides work.archaic.service.logging.v03.Log with example.Broken; }");
            var source = f.write("provider/example/Broken.java", """
                    package example;
                    import work.archaic.service.logging.v03.*;
                    public class Broken implements Log {
                        public Context context() { throw new IllegalStateException("broken logging output"); }
                        public Context context(Configuration configuration) { return context(); }
                    }
                    """);
            var classes = f.root().resolve("provider-out");
            int compiled = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--module-path", Fixture.MODULES.toString(), "-d", classes.toString(), descriptor.toString(), source.toString());
            assert compiled == 0 : "The fixture must supply an explicit failing logging provider";
            var result = Fixture.execute(new ProcessBuilder(
                    java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "--module-path", Fixture.MODULES + java.io.File.pathSeparator + classes,
                    "--limit-modules=work.archaic.knit,example.logging", "--add-modules=example.logging",
                    "--module", "work.archaic.knit/work.archaic.knit.Main", "compile").directory(f.root().toFile()));
            trail.note(result.output());
            assert result.exit() == 3 : "Unexpected provider failures must preserve exit status 3";
            assert result.output().lines().filter(line -> line.startsWith("knit: broken logging output")).count() == 1
                    : "Unexpected composition failures must be reported once";
            assert result.output().contains("java.lang.IllegalStateException: broken logging output")
                    && result.output().contains("at example.logging/example.Broken.context")
                    : "Unexpected failures must retain their original stack trace";
        }
    }
}
