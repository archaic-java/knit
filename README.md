# Knit

Knit compiles explicitly selected Java modules with your JDK. It reads source
JARs without extracting them, presents compiler diagnostics with source excerpts,
downloads dependencies whose complete archive bytes are pinned by SHA-256, and
packages source modules as `.knit.jar` distributions.

The CLI is `knit`; its module is `work.archaic.knit`. JDK 25 or later is required.
JDK 25 is the tested baseline. The running JDK supplies Java syntax and compilation;
compatibility with every future JDK option or behavior is not promised.

## Build and test

Build Knit with ordinary JDK command files and source links. Place the three
pinned dependency checkouts beside this repository (CI uses the same revisions):

```sh
git clone https://github.com/archaic-java/service-catalog.git ../service-catalog
git -C ../service-catalog checkout --detach 01b46cabadd358d52d6e437045a77f1a0f304c04
git clone https://github.com/archaic-java/culpa.git ../culpa
git -C ../culpa checkout --detach 29dd85509112da847b65ab41de38a833324f59a9
git clone https://github.com/archaic-java/minau.git ../minau
git -C ../minau checkout --detach 93bb952390b412fbaa38b2805bcf954fb6b5bd82
javac @cmd/compile
java @cmd/test
bin/knit --version
```

For existing sibling checkouts, verify their revisions and preserve any local work
before changing versions. The checked-in `lib/src` links point to these modules.
Use `java` and `javac` from the same JDK. No Maven, Gradle, or classpath is used.
Culpa supplies logging v03 contexts; Minau supplies the test runner. The pinned
dependency modules currently emit serial and exported-API warnings when compiling
with all lint checks.

`bin/knit` finds its compiled installation relative to the script and invokes
`java` on PATH. Put this repository's `bin` directory on PATH. Do not move or
symlink the script away from its installation; it uses the checkout's compiled
`out/` or the distribution's modular JARs. Its working directory remains the
consuming project.

To build a downloadable CLI archive after compiling and testing, run
`sh cmd/distribute <version>`. The resulting `dist/knit-<version>.tar.gz` holds
the launcher and modular JARs for Knit (including signing and DNS), Culpa, and the service catalog. Minau and
test modules are excluded. A matching `.sha256` file records the archive digest.
CI smoke-tests the extracted archive and publishes both files
as a workflow artifact; a `v*` tag also creates a GitHub Release with that archive.
The archive needs a JDK 25 or later on PATH, but no sibling source checkouts:

```sh
tar -xzf knit-<version>.tar.gz
export PATH="$PWD/knit/bin:$PATH"
knit --version
```

The launcher uses `modules/` in a distribution and `out/` in a source checkout.
Keep it in its installation directory; it resolves its modules relative to itself.

Knit does not use itself to build this repository. Its build is defined only by
`cmd/compile`, `cmd/test`, and dependency links; CI tests behavior with isolated
fixture projects rather than a second self-compilation configuration.

## Project conventions

Run `knit compile` from the consuming project's root. **No knit.xml is required.**
The layout is fixed:

| Path | Meaning |
| --- | --- |
| `src/<module-name>/` | Project source modules, each with module-info.java |
| `lib/src/<module-name>/` | Optional linked source modules |
| `lib/bin/` | Optional explicit binary modular JARs or exploded module directories |
| `out/` | Compiled output owned by Knit |

All discovered source modules are compilation roots, including tests and providers
without incoming `requires` edges. Module descriptors define names and relationships;
they do not select downloads. All sources use UTF-8. Automatic modules, duplicate
modules/locations, source/binary conflicts, and descriptor/name mismatches fail.
Existing but broken conventional paths fail rather than being silently ignored.

Knit assumes the running JDK is suitable. To require a minimum, optionally add:

```xml
<knit version="1">
  <compiler minimum-jdk="25"/>
</knit>
```

A running JDK below this minimum is rejected. Otherwise its language, APIs, and
bytecode target are used without `--release`. For example, minimum 25 under JDK 26
produces Java 26 bytecode, not output guaranteed to run on Java 25. No maximum JDK
or compatibility range is declared. Knit's own JDK 25 runtime baseline is separate.
No new META-INF directory or manifest is required in local source modules.

The optional compiler element supports only `minimum-jdk` (a positive release
number), `lint` (`all` by default, or `none`), and `werror` (`false` by default).
An empty `<knit version="1"/>` uses the same defaults as an absent file. Unknown
attributes and elements fail; XML external entities and DTDs are disabled.

The earlier `release`, `output`, `source-root`, `module`, and `module-path` compiler
settings are no longer supported. Remove layout declarations and use the fixed
paths; replace release with minimum-jdk only when a prerequisite is intended.
Knit does not read javac argument files, allow raw compiler arguments, or enable
annotation processors, preview mode, plugins, or dependency-supplied options.

Paths and linked checkouts may contain spaces. Knit rejects output symlinks and
input/cache overlap. A nonempty `out/` must have a `.knit-output` marker identifying
this project; an empty directory is acceptable. Move aside old output from other
tools before selecting this project for Knit compilation.

## Explicit dependencies

Add one declaration for every external artifact, including indirectly required
modules and chosen providers. This example's hash is a placeholder:

```xml
<knit version="1">
  <dependency url="https://example.org/releases/work.archaic.example-260923-1425.knit.jar"
      sha256="REPLACE_WITH_64_HEXADECIMAL_CHARACTERS"/>
</knit>
```

Only `url` and `sha256` are required. After hash verification, Knit derives the
module name from the source or binary module descriptor and detects the artifact
kind. A source archive containing compiled classes is rejected as ambiguous.
Optional `module="work.archaic.example"` and `kind="source"` or `kind="binary"`
assert the expected identity/format and fail on disagreement. Duplicate inferred
module names also fail. Binary artifacts must contain an explicit module descriptor.
Obtain the complete JAR digest from a trusted publication channel,
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
registry, automatic signature trust enforcement, or automatic upgrade.

## Package a module

```sh
knit package work.archaic.example
```

This creates a file such as `dist/work.archaic.example-260923-1425.knit.jar`
and reports its SHA-256 digest. The suffix before `.knit.jar` is the packaging
time in UTC (`YYMMDD-HHmm`), accurate to the minute. Files for the same module
sort from older to newer within a century. Packaging in the same minute replaces
that minute's file; packages from earlier minutes remain in `dist`.
Select exactly one module from `src/<module-name>` or `lib/src/<module-name>`;
linked module directories are supported. No knit.xml or prior compilation is
required. Packaging is offline and does not fetch or resolve dependencies.

The result is a **source archive**, containing the selected module's Java sources,
legal notices, and a generated `META-INF/MANIFEST.MF`. There are no compiled classes
or bundled dependencies. No META-INF files are added to the source tree. Project
root legal notices are included as well; a module-specific notice takes precedence
when it has the same archive path. Unsupported files, including runtime resources,
class files, and source-tree symlinks, fail clearly instead of being silently omitted.
A module directory itself may be a link, but links inside it are rejected.

The generated manifest uses source format 1. Its required `Archaic-Minimum-JDK`
value comes from the project's optional `minimum-jdk`, or the running JDK when
omitted. The fallback records the packaging JDK; it does not infer the earliest
JDK that could compile the sources. Packaging verifies the descriptor's syntax
and module name, but does not type-check the module or certify compatibility.

Entries have fixed timestamps and deterministic ordering, so unchanged inputs
produce the same bytes with the same JDK implementation regardless of source-file
modification times. The packaging time appears only in the filename, not in the
archive bytes or manifest. Repackaging replaces the named artifact only after a
complete, validated temporary archive is ready. Failure preserves an existing archive.
The `dist` directory and destination file may not be symbolic links, and packaging
never deletes other files in `dist`. Concurrent successful packages use last-writer
wins publication; callers should not edit sources during packaging.

The `.knit.jar` suffix identifies Knit's generated source distributions. Readers
still validate contents and accept existing source archives ending in `.jar`;
renaming an arbitrary JAR does not make it a valid Knit source archive.

Upload the generated file wherever you publish releases. Consumers can declare its
URL and printed digest and use the existing `fetch` and `compile` commands.

## Signing and verification

Configure signing in the publishing project's `knit.xml`:

```xml
<knit version="1">
  <signing publisher="archaic.work" key-id="2026-01"
           private-key-env="KNIT_SIGNING_KEY"/>
</knit>
```

Set `KNIT_SIGNING_KEY` to the path of an unencrypted Ed25519 PKCS#8 DER private-key
file. Relative paths resolve against the project root. With this declaration,
`knit package <module>` signs before publication and reports the final signed JAR's
SHA-256. Missing or invalid signing credentials fail packaging; no unsigned
fallback is published. Without the declaration, packaging remains unsigned.
`fetch` and `compile` do not load the private key.

`knit sign <jar>` uses the same project configuration for an existing source or
explicit binary modular JAR. It atomically replaces an unsigned regular file;
already signed artifacts and symbolic links are rejected. `knit verify <jar>`
requires no project configuration and succeeds only when current publisher trust
is established. It distinguishes unsigned, verified, rejected, and unavailable
results; unsuccessful verification exits with status 2.

Public keys are authorized by DNSSEC-authenticated `_knit.<publisher>` TXT records.
The Linux resolver uses trusted `busctl` on PATH and local `systemd-resolved` on
the system bus, with DNSSEC validation enabled. It accepts only authenticated
unicast DNS replies, rejects local synthesized/zone data, and disables aliases,
search suffixes and stale answers. Missing services, unsupported resolver flags,
or lookup failure never establish trust. Signing and offline compilation need
neither service. [Signing details and format](docs/signing.md) describe key
publication, the public API, limits and tests.

The signed namespace is the actual JPMS module name. Signing and verification
read its descriptor independently of signing metadata. Signed source archives
remain consumable offline with existing SHA-256 dependency pinning. Neither
`fetch` nor `compile` automatically establishes DNS publisher trust.

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
Compilation always uses the running JDK's language and APIs; there is no target-release setting.

Legal notices are LICENSE, LICENCE, NOTICE, or COPYING, optionally with suffixes
such as `.txt` or `-MIT`, at the root or directly inside META-INF. Class files,
runtime resources, nested archives, scripts, ambiguous/duplicate entry paths, and
unknown format versions are rejected. The two exact Knit signing entries are permitted; incomplete or malformed signing metadata is rejected without a DNS lookup. Resource-bearing libraries can be supplied
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
stale classes, HTTPS acquisition through a local TLS server, and packaging/consuming source archives. Tests require no
external network access; `keytool` creates an ephemeral test certificate.

Knit's bootstrap uses plain JDK commands. Compilation itself uses the public
JavaCompiler and JavacTask tree APIs, with no compiler internals. Each fetch,
compile, or package command runs in one configured logging v03 context on the
calling thread. Operational objects
record bounded evidence which is discarded on success and printed once on failure.
Compiler diagnostics and progress keep their ordinary stderr format.
Debug details use lazy suppliers; enable them with the Java system property
`-Dknit.debug=true` (for the launcher, `JAVA_TOOL_OPTIONS=-Dknit.debug=true`).
No contracts or provider implementations are copied into this repository.
The CLI/packaging, signing, and DNS authorization are separate production modules.

A future metadata registry can help discover catalogs, providers, URLs, and hashes.
Accepted selections would still be explicit project inputs. Runtime launching,
test orchestration, incremental compilation, release uploading, automatic dependency signature policy, and registry
protocols are outside this initial version.
