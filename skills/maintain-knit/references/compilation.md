# Compilation and diagnostics

## Contents

- [Project conventions](#project-conventions)
- [Source archives](#source-archives)
- [Diagnostics and output](#diagnostics-and-output)


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
