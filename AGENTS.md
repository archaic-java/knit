# Knit

JDK 25 baseline. Compile with `javac @cmd/compile`, test with `java @cmd/test`,
and exercise `bin/knit --version` and `bin/knit compile`. Bootstrap dependencies
with `bin/bootstrap-dependencies`; see README for pinned sibling checkouts.

Use named JPMS modules, public JDK APIs, and the Archaic Java skill. No Maven,
Gradle, compiler internals, automatic modules, or classpath fallback. Knit users
configure compilation through `knit.xml`; command files bootstrap Knit itself.

Source links live in `lib/src`. Generated `out/` and `knit-out/` are ignored.
Logging v02 Goals express fetch and compile intentions. Tests use Minau v02,
inline `assert condition : "violated expectation"`, and `-ea`; no assertion helpers.
Do not alter catalog contracts or add transitive dependency resolution.
