package work.archaic.knit;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

record Project(Path root, Path output, String release, String lint, boolean werror,
        List<Path> sourceRoots, List<String> modules, List<Path> modulePaths, List<Dependency> dependencies) {
    record Dependency(String module, String kind, URI url, String sha256) {}

    static Project read(Path root) throws Exception {
        root = root.toRealPath();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override public void error(SAXParseException error) throws SAXException { throw error; }
            @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
        });
        Element document;
        try (var input = Files.newInputStream(root.resolve("knit.xml"))) {
            document = builder.parse(input).getDocumentElement();
        } catch (SAXException error) {
            throw new InputFailure("Invalid knit.xml: " + error.getMessage());
        }
        if (!document.getTagName().equals("knit")) throw new InputFailure("Expected <knit> root");
        attributes(document, "version");
        if (!required(document, "version").equals("1")) throw new InputFailure("Unsupported knit.xml version");
        String release = "", lint = "";
        boolean werror = false, compilerSeen = false;
        Path output = root.resolve("out");
        var sources = new ArrayList<Path>();
        var modules = new ArrayList<String>();
        var binaries = new ArrayList<Path>();
        var dependencies = new ArrayList<Dependency>();
        var names = new HashSet<String>();
        for (var element : children(document)) {
            switch (element.getTagName()) {
                case "compiler" -> {
                    if (compilerSeen) throw new InputFailure("Duplicate <compiler>");
                    compilerSeen = true;
                    attributes(element, "release", "lint", "werror", "output");
                    release = element.getAttribute("release");
                    if (!release.isEmpty() && !release.matches("[1-9][0-9]*"))
                        throw new InputFailure("release must be a Java release number");
                    if (!release.isEmpty()) {
                        try {
                            int value = Integer.parseInt(release);
                            if (value < 9 || value > Runtime.version().feature())
                                throw new InputFailure("release must be between 9 and the running JDK release");
                        } catch (NumberFormatException error) { throw new InputFailure("Invalid release"); }
                    }
                    lint = element.getAttribute("lint");
                    if (!Set.of("", "all", "none").contains(lint))
                        throw new InputFailure("lint supports all or none");
                    String warning = element.getAttribute("werror");
                    if (!Set.of("", "true", "false").contains(warning))
                        throw new InputFailure("werror must be true or false");
                    werror = warning.equals("true");
                    if (element.hasAttribute("output")) output = root.resolve(required(element, "output")).normalize();
                    for (var setting : children(element)) {
                        switch (setting.getTagName()) {
                            case "source-root" -> { attributes(setting, "path"); sources.add(root.resolve(required(setting, "path")).normalize()); }
                            case "module" -> { attributes(setting, "name"); String name = required(setting, "name"); moduleName(name); modules.add(name); }
                            case "module-path" -> { attributes(setting, "path"); binaries.add(root.resolve(required(setting, "path")).normalize()); }
                            default -> throw new InputFailure("Unsupported compiler setting: " + setting.getTagName());
                        }
                        leaf(setting);
                    }
                }
                case "dependency" -> {
                    attributes(element, "module", "kind", "url", "sha256");
                    leaf(element);
                    String name = required(element, "module");
                    moduleName(name);
                    if (!names.add(name)) throw new InputFailure("Duplicate dependency module: " + name);
                    String kind = required(element, "kind");
                    if (!Set.of("source", "binary").contains(kind)) throw new InputFailure("Unsupported dependency kind: " + kind);
                    URI url;
                    try { url = URI.create(required(element, "url")); }
                    catch (IllegalArgumentException error) { throw new InputFailure("Invalid dependency URL"); }
                    validateUrl(url);
                    String hash = required(element, "sha256").toLowerCase(java.util.Locale.ROOT);
                    if (!hash.matches("[0-9a-f]{64}")) throw new InputFailure("sha256 must contain 64 hexadecimal characters");
                    dependencies.add(new Dependency(name, kind, url, hash));
                }
                default -> throw new InputFailure("Unsupported Knit setting: " + element.getTagName());
            }
        }
        if (sources.isEmpty()) sources.add(root.resolve("src"));
        if (new HashSet<>(modules).size() != modules.size()) throw new InputFailure("Duplicate compile root module");
        return new Project(root, output, release, lint, werror, List.copyOf(sources), List.copyOf(modules),
                List.copyOf(binaries), List.copyOf(dependencies));
    }

    static void validateUrl(URI url) throws InputFailure {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || url.getUserInfo() != null || url.getFragment() != null)
            throw new InputFailure("Artifact URLs must be HTTPS, with a host and without credentials or fragments");
    }

    static void moduleName(String name) throws InputFailure {
        if (!SourceVersion.isName(name) || name.startsWith("java.") || name.startsWith("jdk."))
            throw new InputFailure("Invalid application module name: " + name);
    }

    private static String required(Element element, String name) throws InputFailure {
        String value = element.getAttribute(name);
        if (value.isBlank()) throw new InputFailure("Missing " + name + " on <" + element.getTagName() + ">");
        return value;
    }

    private static void attributes(Element element, String... allowed) throws InputFailure {
        var names = Set.of(allowed);
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            String name = attributes.item(i).getNodeName();
            if (!names.contains(name)) throw new InputFailure("Unsupported attribute " + name + " on <" + element.getTagName() + ">");
        }
    }

    private static List<Element> children(Element element) throws InputFailure {
        var result = new ArrayList<Element>();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element nested) result.add(nested);
            else if (child.getNodeType() != Node.COMMENT_NODE && !child.getTextContent().isBlank())
                throw new InputFailure("Unexpected text inside <" + element.getTagName() + ">");
        }
        return result;
    }

    private static void leaf(Element element) throws InputFailure {
        if (!children(element).isEmpty()) throw new InputFailure("Unexpected nested element in <" + element.getTagName() + ">");
    }
}
