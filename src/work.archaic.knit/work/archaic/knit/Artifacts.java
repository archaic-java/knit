package work.archaic.knit;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class Artifacts {
    private final Path cache;
    Artifacts(Path cache) { this.cache = cache.toAbsolutePath().normalize(); }
    Path root() { return cache; }

    static Artifacts userCache() {
        String override = System.getenv("KNIT_CACHE");
        if (override != null && !override.isBlank()) return new Artifacts(Path.of(override));
        String xdg = System.getenv("XDG_CACHE_HOME");
        Path base = xdg != null && !xdg.isBlank() && Path.of(xdg).isAbsolute()
                ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".cache");
        return new Artifacts(base.resolve("knit"));
    }

    Path path(Project.Dependency dependency) { return cache.resolve("sha256").resolve(dependency.sha256()).resolve("artifact.jar"); }

    Path require(Project.Dependency dependency) throws IOException, InputFailure {
        Path file = path(dependency);
        if (!Files.isRegularFile(file)) throw new InputFailure("Missing artifact for " + dependency.label() + "; run knit fetch");
        verify(file, dependency);
        return file;
    }

    void fetch(Project project, PrintWriter output) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            for (var dependency : project.dependencies()) {
                Path target = path(dependency);
                if (Files.exists(target)) {
                    verify(target, dependency);
                    output.println("Verified cached " + dependency.label());
                    continue;
                }
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), ".download-", ".tmp");
                try {
                    download(client, dependency, temporary);
                    // Same digest means concurrent publishers must publish identical bytes.
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    output.println("Fetched " + dependency.label());
                } finally { Files.deleteIfExists(temporary); }
            }
        }
        output.println("Dependencies ready: " + project.dependencies().size());
    }

    @SuppressWarnings("try") // Closing a stalled HTTP body is the timeout mechanism.
    private static void download(HttpClient client, Project.Dependency dependency, Path target) throws Exception {
        URI url = dependency.url();
        for (int redirects = 0; redirects <= 5; redirects++) {
            Project.validateUrl(url);
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                int status = response.statusCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects == 5) throw new InputFailure("Too many redirects for " + dependency.label());
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new InputFailure("Redirect without Location for " + dependency.label()));
                    try { url = url.resolve(location); }
                    catch (IllegalArgumentException error) { throw new InputFailure("Invalid redirect for " + dependency.label()); }
                    continue;
                }
                if (status != 200) throw new InputFailure("HTTP " + status + " fetching " + dependency.label());
                var digest = sha256();
                // Header timeout alone does not bound a streaming response body.
                try (var timer = Executors.newSingleThreadScheduledExecutor()) {
                    var deadline = timer.schedule(() -> {
                        try { body.close(); } catch (IOException ignored) { /* Reader reports the failure. */ }
                    }, 120, TimeUnit.SECONDS);
                    try (var sink = new DigestOutputStream(Files.newOutputStream(target), digest)) {
                        byte[] buffer = new byte[65536];
                        long size = 0;
                        for (int count; (count = body.read(buffer)) != -1;) {
                            size += count;
                            if (size > 512L * 1024 * 1024) throw new InputFailure("Artifact exceeds 512 MiB: " + dependency.label());
                            sink.write(buffer, 0, count);
                        }
                    } finally { deadline.cancel(false); }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(dependency.sha256()))
                    throw new InputFailure("SHA-256 mismatch for " + dependency.label() + "; cache unchanged");
                return;
            }
        }
        throw new InputFailure("Download did not complete: " + dependency.label());
    }

    static void verify(Path file, Project.Dependency dependency) throws IOException, InputFailure {
        var digest = sha256();
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(dependency.sha256()))
            throw new InputFailure("SHA-256 mismatch for " + dependency.label() + "; remove the corrupt cache entry and run knit fetch");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
