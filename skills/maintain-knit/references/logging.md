# CLI logging policy

[Main](../../../src/work.archaic.knit/work/archaic/knit/Main.java) selects exactly
one logging v03 Log provider through ServiceLoader. The README pins Culpa and the
catalog. Read their guidance at those revisions for context lifecycle and limits.

Each fetch, compile, package, sign or verify command executes on the caller thread
in one configured context, including project parsing and final command output.
Help, version and syntax errors need no provider. Composition failures are rendered
separately because no context exists. Outer catches choose exit status after context
completion; they do not publish the same failure again.

Operational objects implement Logging and reuse the command context. Failure
evidence is discarded on success; expensive debug details use lazy suppliers.
Enable debug with `-Dknit.debug=true`, or `JAVA_TOOL_OPTIONS=-Dknit.debug=true` for
the launcher. The three-argument Configuration selects the pinned catalog's default
retention settings; do not independently redefine those defaults here.

CommandOutput supplies application sinks to preserve Knit's concise error format.
Expected input, compilation and interruption failures omit stacks; unexpected
failures keep their original stacks. Compiler diagnostics and progress retain their
ordinary format. All these outputs use stderr; help and version use stdout.
Exit statuses are 0 success, 1 compilation failure, 2 input/download failure and
3 unexpected failure. Unsuccessful verification is a failed command with status 2.

Signing and DNS API modules remain usable without a logging context. Parser and
resolver concurrency must retain its operational purpose; do not introduce threads
or helper-level contexts solely for logging.

Verify through LoggingSuite: success with debug off/on, preserved diagnostics,
shared ordered evidence, one failure publication, and provider composition failures.
CLI integration checks also verify output streams and signing errors.
