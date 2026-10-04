# Source packaging

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
