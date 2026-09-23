package work.archaic.knit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;

final class Output implements AutoCloseable {
    private static final String MARKER = ".knit-output";
    private final Path destination;
    private final String owner;
    private final Path staging;

    Output(Project project, List<Path> inputs, Path cache) throws IOException, InputFailure {
        destination = project.output().toAbsolutePath().normalize();
        owner = project.root().toString();
        if (!destination.startsWith(project.root()) || destination.equals(project.root()))
            throw new InputFailure("Output must be a dedicated directory inside the project");
        for (Path part : project.root().relativize(destination))
            if (part.toString().startsWith(".")) throw new InputFailure("Output cannot use hidden project directories");
        for (Path ancestor = destination; !ancestor.equals(project.root()); ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor)) throw new InputFailure("Output cannot traverse symbolic links");
        for (Path input : inputs) {
            Path real = canonical(input);
            if (destination.startsWith(real) || real.startsWith(destination))
                throw new InputFailure("Output overlaps input: " + input);
        }
        Path cachePath = canonical(cache);
        if (destination.startsWith(cachePath) || cachePath.startsWith(destination))
            throw new InputFailure("Output overlaps artifact cache");
        if (Files.exists(destination)) {
            if (!Files.isDirectory(destination)) throw new InputFailure("Output is not a directory");
            Path marker = destination.resolve(MARKER);
            try (var entries = Files.list(destination)) {
                if (entries.findAny().isPresent() && (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                        || !Files.readString(marker).equals(owner)))
                    throw new InputFailure("Refusing to replace output not owned by Knit: " + destination);
            }
        }
        Files.createDirectories(destination.getParent());
        staging = Files.createTempDirectory(destination.getParent(), ".knit-stage-");
    }

    Path staging() { return staging; }

    void publish() throws IOException {
        Files.writeString(staging.resolve(MARKER), owner);
        Path backup = null;
        if (Files.exists(destination)) {
            backup = Files.createTempDirectory(destination.getParent(), ".knit-backup-");
            Files.delete(backup);
            Files.move(destination, backup, StandardCopyOption.ATOMIC_MOVE);
        }
        try { Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); }
        catch (IOException error) {
            if (backup != null) {
                try { Files.move(backup, destination, StandardCopyOption.ATOMIC_MOVE); }
                catch (IOException restore) { error.addSuppressed(restore); }
            }
            throw error;
        }
        if (backup != null) delete(backup);
    }

    static Path canonical(Path path) throws IOException {
        path = path.toAbsolutePath().normalize();
        if (Files.exists(path)) return path.toRealPath();
        Path parent = path.getParent();
        return parent == null ? path : canonical(parent).resolve(path.getFileName());
    }

    static void delete(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Override public void close() throws IOException { delete(staging); }
}
