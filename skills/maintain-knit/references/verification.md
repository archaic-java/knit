# Verification and test fixtures

Use the [README](../../../README.md#build-and-test) for pinned sibling checkouts
and the canonical compile, test and launch commands. CI uses the same revisions
and runs all three test modules. Use one full JDK 25 for compiler and launcher.

Tests use catalog testing v02 and Minau, with public suite records and package-private
case records. Keep assertions inline with explanatory messages and launch with `-ea`.
Use the catalog for suite/case/trail semantics and Minau for discovery, selection and
reporting; inspect the pinned checkouts rather than assuming moving-main behavior.

## Fixtures and coverage

Acquire mutable fixtures inside each case and close them before returning. Fixture
owns temporary project/cache paths and child CLI processes; each case is isolated.
Capture stdout and stderr independently when checking CLI channels. Bound child
execution and cleanup, including interruption. Helpers may prepare inputs or collect
observations; they must not become assertion wrappers. Record selected observations
in TestTrail, independently of application logging. Trail notes are bounded by Minau;
large output should be split into useful observations rather than one oversized note.

FixtureSuite verifies interruption cleanup through a startup signal and bounded join.
CompilerSuite, FetchSuite and PackagingSuite cover source JAR compilation, module
identity, service discovery, diagnostics, hashes, malformed inputs, output safety,
stale classes, isolated HTTPS acquisition and packaging/consumption. LoggingSuite
covers configured CLI contexts. SigningSuite and SigningIntegrationSuite cover
cryptographic vectors, malformed artifacts, authorization and CLI publication.
ResolverSuite uses a controlled busctl executable, not a live DNSSEC chain; read
[signing acceptance](signing.md#validation) before claiming deployment trust.

## Distribution checks

After compile and tests, run `sh cmd/distribute <version>`. Follow the
[CI smoke checks](../../../.github/workflows/verify.yml): extract the archive,
run its launcher outside the checkout, and compile/package an isolated module.
Minau and test modules must be excluded from the distribution.

## Documentation checks

Validate SKILL.md metadata, every local link/anchor and the task map. Compare
examples and guarantees with their owning implementation/Javadoc and pinned
dependencies. Preserve signing format rationale; a test must not silently introduce
a stronger API promise. Update README and CI together when dependency pins change.
