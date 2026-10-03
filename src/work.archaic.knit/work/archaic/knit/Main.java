package work.archaic.knit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ServiceLoader;
import work.archaic.service.logging.v03.Configuration;
import work.archaic.service.logging.v03.Context;
import work.archaic.service.logging.v03.Log;
import work.archaic.service.logging.v03.Logging;

/** Command-line entry point; all paths are relative to the invoking project. */
public final class Main implements Logging {
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
        var output = new CommandOutput(error);
        Context context;
        try {
            var providers = ServiceLoader.load(Log.class).stream().toList();
            if (providers.size() != 1) throw new InputFailure("Expected exactly one Log provider; install Culpa on Knit's module path");
            var configuration = new Configuration(Boolean.getBoolean("knit.debug"), output::entry, output::failure);
            context = providers.getFirst().get().context(configuration);
        } catch (Exception | java.util.ServiceConfigurationError failure) {
            // No context exists yet to render a composition failure.
            output.problem(failure);
            return status(failure);
        }
        try {
            context.run(() -> new Main().execute(args, error));
            return 0;
        } catch (Exception | Error failure) {
            // Context completion has already published this failure and its evidence.
            return status(failure);
        }
    }

    private void execute(String[] args, PrintWriter error) throws Exception {
        logOnFailure("Command: " + String.join(" ", args));
        Path root = Path.of(".").toAbsolutePath().normalize();
        logOnFailure("Project: " + root);
        var project = Project.read(root);
        switch (args[0]) {
            case "fetch" -> Artifacts.userCache().fetch(project, error);
            case "compile" -> new Compilation(project, Artifacts.userCache(), error).run();
            case "package" -> new Packaging(project, args[1], error).run();
            default -> throw new AssertionError("Validated command was lost");
        }
        error.flush();
    }

    private static int status(Throwable failure) {
        if (failure instanceof CompilationFailure) return 1;
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return 2;
        }
        if (failure instanceof InputFailure || failure instanceof IOException
                || failure instanceof java.nio.file.InvalidPathException) return 2;
        return 3;
    }
}
