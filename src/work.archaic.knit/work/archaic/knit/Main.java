package work.archaic.knit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ServiceLoader;
import work.archaic.service.logging.v02.Diagnostics;
import work.archaic.service.logging.v02.FailureReport;
import work.archaic.service.logging.v02.Goal;
import work.archaic.service.logging.v02.Log;

/** Command-line entry point; all paths are relative to the invoking project. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args));
    }

    private static int run(String[] args) {
        var error = new PrintWriter(System.err, true);
        if (args.length == 1 && args[0].equals("--version")) {
            System.out.println("knit 0.1.0 / JDK " + Runtime.version() + " / " + System.getProperty("java.home"));
            return 0;
        }
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
            System.out.println("Usage: knit fetch | compile | package <module-name> | --version\nRun from the project root; knit.xml is optional. Compilation is offline.");
            return 0;
        }
        boolean operation = args.length == 1 && (args[0].equals("fetch") || args[0].equals("compile"));
        boolean packaging = args.length == 2 && args[0].equals("package");
        if (!operation && !packaging) {
            error.println("Usage: knit fetch | compile | package <module-name> | --version");
            return 2;
        }
        var log = new CommandLog(error);
        try {
            var providers = ServiceLoader.load(Diagnostics.class).stream().toList();
            if (providers.size() != 1) throw new InputFailure("Expected exactly one Diagnostics provider; install Peep on Knit's module path");
            Diagnostics diagnostics = providers.getFirst().get();
            Goal fetch = diagnostics.goal("knit.fetch", log);
            Goal compile = diagnostics.goal("knit.compile", log);
            Goal packageModule = diagnostics.goal("knit.package", log);
            Goal selected = switch (args[0]) {
                case "fetch" -> fetch;
                case "compile" -> compile;
                default -> packageModule;
            };
            selected.run(() -> {
                var project = Project.read(Path.of("."));
                switch (args[0]) {
                    case "fetch" -> Artifacts.userCache().fetch(project, error);
                    case "compile" -> Compilation.run(project, Artifacts.userCache(), error);
                    case "package" -> Packaging.run(project, args[1], error);
                    default -> throw new AssertionError("Validated command was lost");
                }
                error.flush();
            });
            return 0;
        } catch (CompilationFailure failure) {
            log.reportIfNeeded(failure);
            return 1;
        } catch (InputFailure | IOException | java.nio.file.InvalidPathException failure) {
            log.reportIfNeeded(failure);
            return 2;
        } catch (InterruptedException failure) {
            log.reportIfNeeded(failure);
            Thread.currentThread().interrupt();
            return 2;
        } catch (Exception | java.util.ServiceConfigurationError failure) {
            log.reportIfNeeded(failure);
            return 3;
        }
    }

    private static final class CommandLog implements Log {
        private final PrintWriter destination;
        private boolean reported;
        CommandLog(PrintWriter destination) { this.destination = destination; }
        @Override public void write(String message) { destination.println(message); }
        @Override public void write(FailureReport report) { reportIfNeeded(report.failure()); }
        void reportIfNeeded(Throwable failure) {
            if (reported) return;
            reported = true;
            destination.println("knit: " + (failure.getMessage() == null ? failure : failure.getMessage()));
            if (!(failure instanceof InputFailure || failure instanceof IOException || failure instanceof CompilationFailure
                    || failure instanceof InterruptedException || failure instanceof java.nio.file.InvalidPathException))
                failure.printStackTrace(destination);
            destination.flush();
        }
    }
}
