# Knit

JDK 25 baseline. Compile with `javac @cmd/compile`, test with `java @cmd/test`,
and exercise `bin/knit --version`. See README for pinned sibling dependency
checkouts. Build Knit only with command files; test compilation using fixtures.

Use named JPMS modules, public JDK APIs, and the Archaic Java skill. No Maven,
Gradle, compiler internals, automatic modules, or classpath fallback. Knit users
use conventional src, lib/src, lib/bin, and out paths. Optional `knit.xml` declares
minimum-jdk, lint policy, and exact artifacts; no target-release or layout settings.

Source links live in `lib/src`. Generated `out/` and `dist/` are ignored.
Logging v03/Culpa binds one configured context per command on the calling thread.
Signing and DNS have separate production/test modules; run all suites via cmd/test.
Signing APIs take explicit values; keep XML and private-key environment access in the CLI module.
Operational objects implement Logging; debug messages use suppliers. Tests use Minau v02,
inline `assert condition : "violated expectation"`, and `-ea`; no assertion helpers.
Do not alter catalog contracts or add transitive dependency resolution.

Read [Maintain Knit](skills/maintain-knit/SKILL.md) before editing, then follow its
task map. If local skills are not discovered automatically, read that file directly.
It is also the shared human contributor guide; keep detailed guidance in its references.
