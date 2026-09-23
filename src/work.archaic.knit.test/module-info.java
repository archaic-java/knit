module work.archaic.knit.test {
    requires work.archaic.service.catalog;
    requires java.compiler;
    requires jdk.httpserver;
    exports work.archaic.knit.test to work.archaic.minau;
}
