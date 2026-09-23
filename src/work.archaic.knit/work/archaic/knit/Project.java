package work.archaic.knit;

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

record Project(Path root, int minimumJdk, String lint, boolean werror, List<Dependency> dependencies) {
    record Dependency(String module, String kind, URI url, String sha256) {
        String label() { return module.isEmpty() ? url.toString() : module; }
    }

    Path output() { return root.resolve("out"); }

    List<Path> sourceRoots() {
        var paths = new ArrayList<Path>();
        paths.add(root.resolve("src"));
        Path linked = root.resolve("lib/src");
        if (Files.exists(linked, java.nio.file.LinkOption.NOFOLLOW_LINKS)) paths.add(linked);
        return List.copyOf(paths);
    }

    List<Path> modulePaths() {
        Path binaries = root.resolve("lib/bin");
        return Files.exists(binaries, java.nio.file.LinkOption.NOFOLLOW_LINKS) ? List.of(binaries) : List.of();
    }

    static Project read(Path root) throws Exception {
        root = root.toRealPath();
        if (!Files.exists(root.resolve("knit.xml"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return new Project(root, 0, "all", false, List.of());
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
        int minimumJdk = 0;
        String lint = "all";
        boolean werror = false, compilerSeen = false;
        var dependencies = new ArrayList<Dependency>();
        var names = new HashSet<String>();
        for (var element : children(document)) {
            switch (element.getTagName()) {
                case "compiler" -> {
                    if (compilerSeen) throw new InputFailure("Duplicate <compiler>");
                    compilerSeen = true;
                    attributes(element, "minimum-jdk", "lint", "werror");
                    leaf(element);
                    if (element.hasAttribute("minimum-jdk")) {
                        String minimum = required(element, "minimum-jdk");
                        try {
                            if (!minimum.matches("[1-9][0-9]*")) throw new NumberFormatException();
                            minimumJdk = Integer.parseInt(minimum);
                            if (minimumJdk > Runtime.version().feature())
                                throw new InputFailure("Project requires JDK " + minimum + "; running JDK is " + Runtime.version().feature());
                        } catch (NumberFormatException error) { throw new InputFailure("minimum-jdk must be a positive Java release number"); }
                    }
                    if (element.hasAttribute("lint")) lint = required(element, "lint");
                    if (!Set.of("all", "none").contains(lint))
                        throw new InputFailure("lint supports all or none");
                    String warning = element.getAttribute("werror");
                    if (!Set.of("", "true", "false").contains(warning))
                        throw new InputFailure("werror must be true or false");
                    werror = warning.equals("true");
                }
                case "dependency" -> {
                    attributes(element, "module", "kind", "url", "sha256");
                    leaf(element);
                    String name = element.getAttribute("module");
                    if (element.hasAttribute("module")) moduleName(required(element, "module"));
                    if (!name.isEmpty() && !names.add(name)) throw new InputFailure("Duplicate dependency module: " + name);
                    String kind = element.hasAttribute("kind") ? required(element, "kind") : "";
                    if (!Set.of("", "source", "binary").contains(kind)) throw new InputFailure("Unsupported dependency kind: " + kind);
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
        return new Project(root, minimumJdk, lint, werror, List.copyOf(dependencies));
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
