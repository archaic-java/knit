package work.archaic.knit;

import java.io.IOException;
import java.io.PrintWriter;
import work.archaic.service.logging.v03.Entry;
import work.archaic.service.logging.v03.FailureReport;

/** CLI rendering shared by context completion and provider-selection failures. */
final class CommandOutput {
    private final PrintWriter destination;
    CommandOutput(PrintWriter destination) { this.destination = destination; }

    void entry(Entry entry) {
        destination.println(entry.timestamp() + " " + escape(entry.source()) + ": " + escape(entry.message()));
    }

    void failure(FailureReport report) {
        if (report.dropped() != 0) destination.println("knit: " + report.dropped() + " earlier log entries dropped");
        report.evidence().forEach(this::entry);
        if (report.explicitFailure() != null) entry(report.explicitFailure());
        if (report.cause() != null) problem(report.cause());
        destination.flush();
    }

    void problem(Throwable failure) {
        destination.println("knit: " + escape(failure.getMessage() == null ? failure.toString() : failure.getMessage()));
        if (!(failure instanceof InputFailure || failure instanceof IOException || failure instanceof CompilationFailure
                || failure instanceof InterruptedException || failure instanceof java.nio.file.InvalidPathException
                || failure instanceof java.security.GeneralSecurityException || failure instanceof java.lang.module.FindException))
            failure.printStackTrace(destination);
        destination.flush();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
