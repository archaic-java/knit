/** Linux DNSSEC authorization through the local systemd-resolved service. */
module work.archaic.knit.dns {
    requires transitive work.archaic.knit.signing;
    exports work.archaic.knit.dns;
}
