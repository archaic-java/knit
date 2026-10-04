---
name: maintain-knit
description: "Maintain, extend, diagnose, or review Knit: module compilation, artifact acquisition, source packaging, signing, DNS authorization, CLI logging, test fixtures, and documentation. Use when working in archaic-java/knit; complements Archaic Java with project-specific guidance."
---

# Maintain Knit

Use this shared entry point for humans and coding agents. Read the foundation,
then the reference for the task. Start with [README](../../README.md) for setup
and canonical commands; no skill installation is required to read this guidance.

## Foundation

- Use JDK 25, named JPMS modules and checked-in `cmd/` argument files. Read
  applicable AGENTS.md instructions and preserve local changes.
- Bootstrap Knit with the JDK, not Knit itself. Exercise compilation through
  isolated consuming-project fixtures. Keep SHA-256 dependencies explicit;
  compilation is offline and never resolves transitive dependencies.
- The CLI module owns XML, environment access, descriptor identity and publication.
  Signing and DNS are independent API modules without a logging-scope requirement.
- Keep one configured logging v03/Culpa context around a complete CLI command.
  Keep Minau test evidence independent from application logging.
- Use Archaic Java for shared engineering conventions; the references below own
  Knit behavior and local verification. Read the pinned catalog/provider sources
  before changing a service boundary. Do not copy their contracts here.

## Choose the task

| Task | Read | Owning code and verification |
|---|---|---|
| Change configuration, module discovery, compilation or diagnostics | [Compilation](references/compilation.md) | Project, Compilation, SourceArchive, Messages, Output; CompilerSuite and output-preservation cases. |
| Change downloads, hashes or caching | [Acquisition](references/acquisition.md) | Artifacts and Project; FetchSuite's isolated HTTPS fixtures. |
| Change source archives or distribution assembly | [Packaging](references/packaging.md) | Packaging, SourceArchive, cmd/distribute; PackagingSuite and extracted-distribution smoke checks. |
| Change signing, publisher trust or resolver behavior | [Signing](references/signing.md) | Signing APIs, SystemdResolver, SigningCommands and ArtifactIdentity; signing, DNS and CLI integration suites. |
| Change CLI composition or output policy | [Logging](references/logging.md) | Main and CommandOutput; LoggingSuite and separate-stream checks. |
| Add tests, change fixtures or verify a contribution | [Verification](references/verification.md) | Fixture, test module descriptors, cmd/test and CI; independent fixtures and bounded subprocess cleanup. |

## Complete the change

Run the README compile, test and entry-point commands. For distribution changes,
run assembly and the extracted smoke checks described in verification. Report the
checks actually executed. Keep detailed rules in these references, exact API
contracts in source Javadoc, commands in README/command files, and shared rules
in Archaic Java. Validate local links, task routing and claims against code when
editing documentation; update the owning guide with behavior changes.
