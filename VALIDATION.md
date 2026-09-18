# Validation

Validated on Linux with OpenJDK 17.0.20, Maven 3.9.9, Quarkus 3.27.0,
SmallRye Config 3.13.4, and SmallRye Common Process 2.13.9.

The full Maven reactor completed `verify` successfully:

- Runtime module: 24 tests, all passed.
- Deployment module: compiled and generated Quarkus build-step metadata.
- Packaged application module: 3 tests, all passed.

**Total: 27 passing tests, zero failures/errors/skips.**

The packaged example builds with an intentionally nonexistent CLI path, proving
that packaging does not perform a vault lookup. Separate JVM launches verify:

1. Successful secret injection without printing the secret or CLI stderr.
2. Failed CLI lookup with an expression default: WARN, successful application startup,
   sanitized exit-code reason, no default value or CLI stderr exposed.
3. Failed CLI lookup without a default: ERROR, unsuccessful startup, requested
   property name and sanitized lookup reason visible in output.

Runtime tests additionally verify literal and nested defaults, successful secret
values containing expression syntax, overrides with no lookup or warning, malformed
reference rejection despite defaults, timeout fallback, disabled expressions,
required direct lookup diagnostics, process timeouts, process-tree termination,
in-flight interruption, thread cleanup, output limits, whitespace preservation,
argument safety, caching/concurrency/retries/eviction, expressions and config mappings.

The process-tree test automatically skips if Java cannot enumerate a probe child
process. It passed in this validation environment; the earlier environment had
incompatible PID/proc visibility and skipped that test.

No tests access a real 1Password account. Temporary network settings used for Maven
are not part of the project. Normal Maven Central access is sufficient.

## Not verified here

- Real 1Password desktop/Touch ID approval or service-account authentication.
- Native-image compilation/execution.
- Windows or macOS execution (subprocess tests also support macOS).
- Other Quarkus versions, live reload, or automatic credential rotation.

CLI error classification is deliberately limited: nonzero exits are generic failures
with an exit code, not a claim of item-not-found versus authentication failure.
See README.md for fallback policy and local setup instructions.
