# Knit

Knit compiles explicitly selected Java modules with your JDK. It reads source
JARs without extracting them, presents compiler diagnostics with source excerpts,
downloads dependencies whose complete archive bytes are pinned by SHA-256, and
packages source modules as `.knit.jar` distributions.

The CLI is `knit`; its module is `work.archaic.knit`. JDK 25 or later is required.
JDK 25 is the tested baseline. The running JDK supplies Java syntax and compilation;
compatibility with every future JDK option or behavior is not promised.

For contributor guidance, start with [Maintain Knit](skills/maintain-knit/SKILL.md).

## Build and test

Build Knit with ordinary JDK command files and source links. Place the three
pinned dependency checkouts beside this repository (CI uses the same revisions):

```sh
git clone https://github.com/archaic-java/service-catalog.git ../service-catalog
git -C ../service-catalog checkout --detach 47d5f627b645013b34357d965b1a84e3ba2de347
git clone https://github.com/archaic-java/culpa.git ../culpa
git -C ../culpa checkout --detach e3db79e01fb4891d22877102b2824b4943ebec0b
git clone https://github.com/archaic-java/minau.git ../minau
git -C ../minau checkout --detach f6617e88f81d7218aeb347a278da6d503b27347e
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

## Use Knit

Run commands from the consuming project's root:

```sh
knit compile
knit fetch
knit package work.archaic.example
knit sign dist/example.knit.jar
knit verify dist/example.knit.jar
```

Compilation needs no knit.xml: source modules live in `src/<module-name>` or
`lib/src/<module-name>`, binary modules in `lib/bin`, and output in `out`.
Use optional knit.xml for compiler policy, exact URL/SHA-256 dependencies and
publisher signing configuration. Compilation and packaging are offline; fetch
acquires only explicitly declared artifacts. Verification establishes current
publisher trust through DNSSEC-authenticated keys using Linux systemd-resolved.

Read the maintenance skill's task map for [configuration and diagnostics](skills/maintain-knit/references/compilation.md),
[dependencies](skills/maintain-knit/references/acquisition.md),
[packaging](skills/maintain-knit/references/packaging.md), and
[signing configuration, format and DNS acceptance](skills/maintain-knit/references/signing.md).
Help/version use stdout; diagnostics/progress use stderr. Exit statuses are
0 success, 1 compilation failure, 2 input/download failure, 3 unexpected failure.
For debug and failure evidence, read [CLI logging](skills/maintain-knit/references/logging.md).
For coverage and its limits, read [verification](skills/maintain-knit/references/verification.md).

Runtime launching, test orchestration, incremental compilation, release uploading,
automatic dependency signature policy and registry protocols remain outside scope.
