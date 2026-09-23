package work.archaic.knit;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.jar.JarFile;

final class SourceArchive {
    private SourceArchive() {}

    static String kind(Path path) throws IOException, InputFailure {
        try (var jar = new JarFile(path.toFile(), false)) {
            boolean source = jar.getEntry("module-info.java") != null;
            boolean binary = jar.stream().anyMatch(entry -> entry.getName().endsWith(".class"));
            if (source && binary) throw new InputFailure("Ambiguous source/binary archive: " + path);
            return source ? "source" : "binary";
        }
    }

    static void validate(Path path) throws IOException, InputFailure {
        try (var jar = new JarFile(path.toFile(), false)) {
            var manifest = jar.getManifest();
            if (manifest == null) throw new InputFailure("Source JAR has no manifest: " + path);
            var attributes = manifest.getMainAttributes();
            if (!"1.0".equals(attributes.getValue("Manifest-Version"))
                    || !"1".equals(attributes.getValue("Archaic-Source-Format")))
                throw new InputFailure("Unsupported source JAR format: " + path);
            String minimum = attributes.getValue("Archaic-Minimum-JDK");
            try {
                if (minimum == null || !minimum.matches("[1-9][0-9]*")) throw new NumberFormatException();
                if (Integer.parseInt(minimum) > Runtime.version().feature())
                    throw new InputFailure("Source JAR requires JDK " + minimum + ": " + path);
            } catch (NumberFormatException error) { throw new InputFailure("Invalid Archaic-Minimum-JDK: " + path); }
            var names = new HashSet<String>();
            var files = new HashSet<String>();
            var entries = jar.entries();
            boolean descriptor = false;
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String name = entry.getName();
                String clean = entry.isDirectory() ? name.substring(0, name.length() - 1) : name;
                if (clean.isEmpty() || clean.startsWith("/") || clean.contains("\\") || clean.contains(":")
                        || java.util.Arrays.stream(clean.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..")))
                    throw new InputFailure("Ambiguous archive entry: " + name);
                if (!names.add(clean)) throw new InputFailure("Duplicate archive entry: " + name);
                if (entry.isDirectory()) continue;
                files.add(clean);
                descriptor |= name.equals("module-info.java");
                if (!(name.endsWith(".java") && !name.startsWith("META-INF/"))
                        && !name.equals("META-INF/MANIFEST.MF") && !legalNotice(name))
                    throw new InputFailure("Unsupported source JAR payload: " + name);
            }
            for (String name : names) {
                for (int slash = name.indexOf('/'); slash >= 0; slash = name.indexOf('/', slash + 1))
                    if (files.contains(name.substring(0, slash))) throw new InputFailure("Archive file/directory collision: " + name);
            }
            if (!descriptor) throw new InputFailure("Source JAR requires root module-info.java: " + path);
        }
    }

    private static boolean legalNotice(String name) {
        if (name.startsWith("META-INF/")) name = name.substring("META-INF/".length());
        return name.matches("(?i)(LICENSE|LICENCE|NOTICE|COPYING)([.-][A-Za-z0-9_-]+)*");
    }
}
