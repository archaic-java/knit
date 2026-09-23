# Knit

Knit compiles explicitly selected Java modules with your JDK. It reads source
JARs without extracting them, presents compiler diagnostics with source excerpts,
and downloads dependencies whose complete archive bytes are pinned by SHA-256.

The CLI is `knit`; its module is `work.archaic.knit`. JDK 25 or later is required.
JDK 25 is the tested baseline. The running JDK supplies Java syntax and compilation;
compatibility with every future JDK option or behavior is not promised.

## Build and test

Clone this repository as a sibling of `service-catalog`, `peep`, and `minau`.
The bootstrap checks exact revisions and refuses to change existing checkouts:

```sh
bin/bootstrap-dependencies
javac @cmd/compile
java @cmd/test
bin/knit --version
```

Use `java` and `javac` from the same JDK. No Maven, Gradle, or classpath is used.
The checked-in `lib/src` links point to those sibling source modules. Peep supplies
logging v02 Diagnostics; Minau supplies the test runner. There are two existing
serialVersionUID warnings in the pinned catalog when compiling with all lint checks.

`bin/knit` finds its compiled installation relative to the script and invokes
`java` on PATH. Put this repository's `bin` directory on PATH. Do not move or
symlink the script away from its installation; the supported installation is the
checkout plus compiled `out/`. Its working directory remains the consuming project.

Knit can also compile its own sources after the bootstrap:

```sh
bin/knit compile
java --module-path knit-out --module work.archaic.knit/work.archaic.knit.Main --version
```

Its own `knit.xml` writes to `knit-out/`, leaving the running bootstrap intact.

## Project configuration

Run from a directory containing `knit.xml`. A local-only project needs:

```xml
<knit version="1">
  <compiler release="25" lint="all" werror="true"/>
</knit>
```

Default sources are `src/<module-name>/`, each containing `module-info.java`;
default output is `out/`. All local modules are roots unless selected explicitly.
All sources use UTF-8. Explicit source roots replace the default, and linked module
directories are supported:

```xml
<knit version="1">
  <compiler release="25" lint="all" output="build/classes">
    <source-root path="src"/>
    <source-root path="lib/src"/>
    <module name="work.archaic.application"/>
    <module-path path="lib/bin"/>
  </compiler>
</knit>
```

`module-path` accepts an explicit modular JAR, an exploded binary module, or a
directory containing binary modules. Each entry must supply one explicit module;
automatic modules, duplicate modules, duplicate locations, source/binary conflicts,
and descriptor/name mismatches fail. Selected local roots may use other local
source modules. Every declared source artifact is also a compile root, including
providers with no incoming `requires` edge.

Supported compiler attributes are `release` (9 through the running JDK, subject
to that compiler's supported releases), `lint` (`all` or `none`), `werror`
(`true` or `false`), and `output`. Omitted release/lint use compiler defaults;
werror defaults to false. Unknown elements and attributes fail. There are no raw
compiler arguments, argument files, annotation processors, preview mode, plugins,
or dependency-supplied compiler settings. XML external entities and DTDs are disabled.

Paths resolve from the project root and may contain spaces. Output must be a
dedicated non-hidden directory inside that project, without symlink ancestors or
overlap with inputs or cache. Knit refuses to replace a nonempty directory unless
its `.knit-output` marker identifies this project. An empty directory is acceptable.
Move aside old output from other tools before selecting it as Knit output.

## Explicit dependencies

Add one declaration for every external artifact, including indirectly required
modules and chosen providers. This example's hash is a placeholder:

```xml
<knit version="1">
  <compiler release="25"/>
  <dependency module="work.archaic.example" kind="source"
      url="https://example.org/releases/example-sources.jar"
      sha256="REPLACE_WITH_64_HEXADECIMAL_CHARACTERS"/>
</knit>
```

`kind` is `source` or `binary`. Binary artifacts must contain an explicit module
descriptor. Obtain the complete JAR digest from a trusted publication channel,
for example the GitHub release asset API, and pin it here. Knit does not query
GitHub to update hashes and does not infer artifact URLs from module descriptors.

```sh
knit fetch
knit compile
```

`fetch` acquires exactly the declarations and verifies SHA-256 before publishing
cache entries. It allows up to five HTTPS redirects, no URL credentials or HTTP
downgrades, a 20-second connect timeout, a 30-second response-header timeout, a
120-second body deadline, and a 512 MiB artifact limit. Repeated fetches verify and
reuse cached bytes. Failed downloads leave no usable new entry. Remove a corrupt
cache entry explicitly before fetching it again.

The cache is `$XDG_CACHE_HOME/knit/sha256/<digest>/artifact.jar`, falling back to
`$HOME/.cache/knit/sha256/<digest>/artifact.jar`. `KNIT_CACHE` overrides the cache
root, useful for isolated/offline environments. Binary artifacts remain there;
include the selected binary JARs on your application's runtime module path.

`compile` is offline, rechecks every declared artifact's hash, and never fetches
missing modules. Missing artifacts explain how to run fetch; missing Java modules
are compiler diagnostics. There is no transitive resolution, version selection,
registry, signing, certificate trust policy, or automatic upgrade.

## Source archives

One JAR contains one module: root `module-info.java`, UTF-8 `.java` files under
package directories, a manifest, and optional license/notice files. Required
manifest attributes:

```text
Manifest-Version: 1.0
Archaic-Source-Format: 1
Archaic-Minimum-JDK: 25
```

`Archaic-Artifact-Version` is optional descriptive metadata. Minimum JDK is a
compiler prerequisite, not a bytecode target or future compatibility guarantee.
The consumer's `release` setting still governs language/API compatibility.

Legal notices are LICENSE, LICENCE, NOTICE, or COPYING, optionally with suffixes
such as `.txt` or `-MIT`, at the root or directly inside META-INF. Class files,
runtime resources, nested archives, scripts, ambiguous/duplicate entry paths, and
unknown format versions are rejected. Resource-bearing libraries can be supplied
as explicit binary modular JARs. Preview-dependent source artifacts are unsupported.

Knit mounts archives through the JDK ZIP filesystem and registers their source
roots with the standard compiler file manager. No source extraction is performed.
Module relationships remain exclusively in `module-info.java`.

## Diagnostics and output

Diagnostics preserve javac's severity, code, full message, and emission order.
They include a location and source excerpt where available. Archive locations use
`module-name!/entry.java`, not cache paths. Output is plain text, supports tabbed
and Unicode source, and retains compiler output not delivered through the diagnostic
listener. Unicode marker width counts code points; terminal-specific wide/combining
glyph widths are not calculated. Multiline ranges are indicated after the first line.

Compilation uses a fresh staging directory. Failed compilation leaves the previous
successful output intact and says so explicitly. Successful compilation replaces it,
removing stale classes. Concurrent compilation of one project is rejected with a
file lock. Publication uses same-filesystem atomic renames with rollback on an
ordinary rename failure; the two renames are not a crash-atomic transaction. An
interrupted process may leave a `.knit-backup-*` or `.knit-stage-*` directory for
manual recovery. Never run old output assuming a failed build produced new classes.

Diagnostics and progress use stderr. Help and version use stdout. Exit statuses:
0 success, 1 Java compilation failure, 2 input/download failure, 3 unexpected failure.

## Verification and scope

Minau tests run isolated CLI processes and verify compilation from source JARs,
service discovery, diagnostics, hashes, malformed inputs, output safety, removal of
stale classes, and HTTPS acquisition through a local TLS server. Tests require no
external network access; `keytool` creates an ephemeral test certificate.

Knit's bootstrap uses plain JDK commands. Compilation itself uses the public
JavaCompiler and JavacTask tree APIs, with no compiler internals. Fetch and compile
are distinct logging v02 Goals. No contracts or provider implementations are copied
into this repository. The implementation is intentionally one production module.

A future metadata registry can help discover catalogs, providers, URLs, and hashes.
Accepted selections would still be explicit project inputs. Runtime launching,
test orchestration, incremental compilation, publishing, signatures, and registry
protocols are outside this initial version.
