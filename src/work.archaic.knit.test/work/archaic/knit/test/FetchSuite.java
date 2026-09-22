package work.archaic.knit.test;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Collection;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import work.archaic.service.test.v02.TestCase;
import work.archaic.service.test.v02.TestSuite;
import work.archaic.service.test.v02.TestTrail;

public record FetchSuite() implements TestSuite {
    @Override public void cases(Collection<TestCase> cases) { cases.add(new AcquireArtifacts()); }
}

record AcquireArtifacts() implements TestCase {
    @Override public void run(TestTrail trail) throws Exception {
        try (var fixture = Fixture.create()) {
            Path keys = fixture.root().resolve("test.p12");
            var generated = Fixture.execute(new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "keytool").toString(), "-genkeypair",
                    "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                    "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1", "-storetype", "PKCS12",
                    "-keystore", keys.toString(), "-storepass", "test-password", "-keypass", "test-password", "-noprompt"));
            assert generated.exit() == 0 : "The HTTPS fixture must have a test certificate";
            var store = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keys)) { store.load(input, "test-password".toCharArray()); }
            var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(store, "test-password".toCharArray());
            var tls = SSLContext.getInstance("TLS");
            tls.init(managers.getKeyManagers(), null, null);
            var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls));
            var requests = new java.util.concurrent.atomic.AtomicInteger();
            Path jar = fixture.jar(Map.of("module-info.java", "module example.dep {}"), "1", "25");
            byte[] bytes = Files.readAllBytes(jar);
            server.createContext("/artifact", exchange -> {
                requests.incrementAndGet();
                try (exchange) {
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            });
            server.createContext("/redirect", exchange -> {
                try (exchange) { exchange.getResponseHeaders().add("Location", "/artifact"); exchange.sendResponseHeaders(302, -1); }
            });
            server.createContext("/downgrade", exchange -> {
                try (exchange) { exchange.getResponseHeaders().add("Location", "http://127.0.0.1/artifact"); exchange.sendResponseHeaders(302, -1); }
            });
            server.createContext("/loop", exchange -> {
                try (exchange) { exchange.getResponseHeaders().add("Location", "/loop"); exchange.sendResponseHeaders(302, -1); }
            });
            server.createContext("/truncated", exchange -> {
                try (exchange) { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes, 0, bytes.length / 2); }
            });
            server.start();
            try {
                String base = "https://127.0.0.1:" + server.getAddress().getPort();
                String[] trust = {"-Djavax.net.ssl.trustStore=" + keys, "-Djavax.net.ssl.trustStorePassword=test-password"};
                for (String endpoint : new String[]{"downgrade", "loop", "truncated", "missing"}) {
                    fixture.config("", fixture.dependency(jar, "example.dep", base + "/" + endpoint, false));
                    var result = fixture.knit("fetch", trust); trail.note(endpoint + ": " + result.output());
                    assert result.exit() == 2 : "Failed or unsafe HTTP responses must be input/acquisition failures: " + endpoint;
                    try (var paths = Files.walk(fixture.cache())) {
                        assert paths.noneMatch(Files::isRegularFile) : "A failed download must leave no cache entry or temporary download";
                    }
                }
                String declaration = fixture.dependency(jar, "example.dep", base + "/artifact", false);
                fixture.config("", declaration.replaceAll("sha256=\"[0-9a-f]+\"", "sha256=\"" + "0".repeat(64) + "\""));
                var mismatch = fixture.knit("fetch", trust);
                assert mismatch.exit() == 2 && mismatch.output().contains("SHA-256 mismatch") : "A downloaded hash mismatch must fail";
                fixture.config("", fixture.dependency(jar, "example.dep", base + "/redirect", false));
                var downloaded = fixture.knit("fetch", trust); trail.note(downloaded.output());
                assert downloaded.exit() == 0 : "An HTTPS redirect to a matching artifact must be supported";
                int count = requests.get();
                assert fixture.knit("fetch", trust).exit() == 0 : "Repeated fetch must accept a valid cache entry";
                assert count == requests.get() : "Repeated fetch must reuse the verified cache without a network request";
                fixture.app("requires example.dep;", "");
                server.stop(0);
                var compiled = fixture.knit("compile"); trail.note(compiled.output());
                assert compiled.exit() == 0 : "Downloaded dependencies must compile offline after the server stops";
            } finally { server.stop(0); }
        }
    }
}
