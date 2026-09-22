package work.archaic.knit;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

final class Messages implements DiagnosticListener<JavaFileObject> {
    private final PrintWriter output;
    private final Path project;
    private final Map<String, String> archives = new LinkedHashMap<>();
    private int errors;
    private int warnings;
    Messages(PrintWriter output, Path project) { this.output = output; this.project = project; }
    void archive(Path root, String module) { archives.put(root.toUri().toString(), module + "!/"); }
    boolean hasErrors() { return errors > 0; }
    String summary() { return errors + " error(s), " + warnings + " warning(s)"; }

    @Override public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
        switch (diagnostic.getKind()) {
            case ERROR -> errors++;
            case WARNING, MANDATORY_WARNING -> warnings++;
            default -> { }
        }
        var source = diagnostic.getSource();
        String location = source == null ? "javac" : location(source);
        if (diagnostic.getLineNumber() != Diagnostic.NOPOS) location += ":" + diagnostic.getLineNumber();
        if (diagnostic.getColumnNumber() != Diagnostic.NOPOS) location += ":" + diagnostic.getColumnNumber();
        output.println(location + ": " + diagnostic.getKind().name().toLowerCase(Locale.ROOT)
                + " [" + diagnostic.getCode() + "]");
        output.println(diagnostic.getMessage(Locale.ROOT));
        if (source != null && diagnostic.getPosition() != Diagnostic.NOPOS) {
            try { excerpt(source.getCharContent(true).toString(), diagnostic); }
            catch (IOException error) { output.println("  (source excerpt unavailable: " + error.getMessage() + ")"); }
        }
        output.flush();
    }

    private String location(JavaFileObject source) {
        String uri = source.toUri().toString();
        for (var entry : archives.entrySet())
            if (uri.startsWith(entry.getKey())) return entry.getValue() + uri.substring(entry.getKey().length());
        if ("file".equals(source.toUri().getScheme())) {
            Path path = Path.of(source.toUri());
            return path.startsWith(project) ? project.relativize(path).toString() : path.toString();
        }
        return uri;
    }

    private void excerpt(String text, Diagnostic<?> diagnostic) {
        int position = (int) Math.min(text.length(), diagnostic.getPosition());
        if (position < 0) return;
        int start = text.lastIndexOf('\n', position - 1) + 1;
        int end = text.indexOf('\n', position);
        if (end < 0) end = text.length();
        String before = expand(text.substring(start, position));
        String line = expand(text.substring(start, end).replace("\r", ""));
        int stop = diagnostic.getEndPosition() < position ? position : (int) Math.min(end, diagnostic.getEndPosition());
        int width = Math.max(1, expand(text.substring(start, stop)).codePointCount(0,
                expand(text.substring(start, stop)).length()) - before.codePointCount(0, before.length()));
        output.println("  " + line);
        output.println("  " + " ".repeat(before.codePointCount(0, before.length())) + "^".repeat(Math.min(width, 160)));
        if (diagnostic.getEndPosition() > end) output.println("  (range continues on following lines)");
    }

    private static String expand(String text) {
        var result = new StringBuilder();
        int column = 0;
        for (int cp : text.codePoints().toArray()) {
            if (cp == '\t') { int count = 8 - column % 8; result.append(" ".repeat(count)); column += count; }
            else { result.appendCodePoint(Character.isISOControl(cp) ? '?' : cp); column++; }
        }
        return result.toString();
    }
}
