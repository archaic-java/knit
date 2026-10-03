/** Explicit acquisition and compilation of named Java modules. */
module work.archaic.knit {
    requires work.archaic.knit.signing;
    requires work.archaic.knit.dns;
    requires java.compiler;
    requires jdk.compiler;
    requires jdk.zipfs;
    requires java.net.http;
    requires java.xml;
    requires work.archaic.service.catalog;
    uses work.archaic.service.logging.v03.Log;
}
