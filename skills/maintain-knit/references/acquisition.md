# Explicit artifact acquisition

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
