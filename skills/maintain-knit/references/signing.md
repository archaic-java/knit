# Knit signing v1

## Contents

- [Configuration and keys](#configuration-and-keys)
- [Modules and API](#modules-and-api)
- [Artifact format](#artifact-format)
- [DNSSEC implementation](#dnssec-implementation)
- [Validation](#validation)


Knit verifies that an artifact is signed by a key currently authorized by the
DNSSEC-protected publisher domain corresponding to its JPMS module namespace.
It does not establish historical authorization, artifact safety, or dependency
graph trust. Domain transfer and signing-key/DNS compromise remain trust risks.

## Configuration and keys

```xml
<knit version="1">
  <signing publisher="archaic.work" key-id="2026-01"
           private-key-env="KNIT_SIGNING_KEY"/>
</knit>
```

The environment variable contains a file path, not key bytes. The file is an
unencrypted PKCS#8 DER Ed25519 private key, at most 16 KiB. This local storage
format is independent of the raw public-key and signature wire formats. A
publisher can generate the key pair using `KeyPairGenerator.getInstance("Ed25519")`,
save `getPrivate().getEncoded()` to its credential store, and publish
`Base64.getEncoder().encodeToString(Keys.encode(getPublic()))` in DNS. The
application neither generates nor stores persistent keys automatically.

Publish records such as:

```dns
_knit.archaic.work. TXT "v=knit1 alg=ed25519 id=2026-01 state=active key=<base64>"
```

TXT character-strings concatenate without separators. Records use exactly these
five ASCII fields, separated by single spaces, in any order. Duplicate/unknown
fields, unsupported algorithms, noncanonical Base64, invalid keys and unknown
states fail closed. Unrelated TXT protocols are ignored. Multiple matching records
are rejected. Key IDs match `[A-Za-z0-9._-]{1,64}` and are case-sensitive.

`active` and `retired` keys verify. `revoked` keys reject all their artifacts.
Signing is offline and does not check DNS state: the publisher is responsible
for signing new artifacts only with active keys. Rotate by publishing a new active
key, allowing TTL propagation, changing signing configuration, then retiring the
old key. Keep retired keys published while their artifacts must remain verifiable.
Revocation/deletion takes effect as DNS caches expire; there is no separate Knit cache.

## Modules and API

`work.archaic.knit` owns XML, credential loading, source/binary descriptor reading,
CLI output and packaging publication. `work.archaic.knit.signing` exposes:

- `Metadata(namespace, publisher, keyId)`.
- `Signing.sign(Path, Metadata, PrivateKey)` for atomic replacement of an unsigned JAR.
- `Signing.inspect(Path)` for structural/metadata validation, without establishing trust.
- `Signing.check(Path, expectedModule, PublicKey)` for cryptographic checking only.
- `Signing.verify(Path, expectedModule, AuthorizationResolver)` for publisher trust.
- `Keys.encode/decode` for the raw Ed25519 public-key encoding.

The caller **must** obtain namespace/expectedModule from the actual descriptor.
The low-level signing module does not parse Java source or depend on the compiler.
The CLI always establishes identity itself, using public compiler APIs for source
and `ModuleFinder` for explicit binary modules (including JDK multi-release selection).
Source descriptors are limited to 64 KiB. Automatic modules are rejected.

`AuthorizationResolver` returns the complete TXT RRset plus DNSSEC authentication
evidence. It is an explicitly supplied interface, with no global provider discovery.
Tests supply controlled responses. `work.archaic.knit.dns` implements it with
`SystemdResolver`; its alternate-executable constructor requires a trusted executable
and supports isolated process tests. No production CLI option bypasses DNSSEC.

`UNSIGNED` means both signing entries are absent; partial entries are rejected.
`VERIFIED` is the only trust success. `REJECTED` includes unauthenticated responses,
revoked/missing/ambiguous keys, namespace mismatch, and invalid signatures.
`UNAVAILABLE` denotes inability to obtain/read authorization or artifact data.
Interruption propagates. No unsuccessful outcome is reported as verified.

## Artifact format

Two regular entries carry the signing data:

```text
META-INF/KNIT/metadata
META-INF/KNIT/signature
```

Metadata is strict UTF-8, without a BOM, with exactly these five lines in order,
LF endings and a final LF. Comments, blank lines, duplicate/unknown fields and
whitespace variations are rejected. Metadata is limited to 4096 bytes.

```text
version=knit1
namespace=work.archaic.example
publisher=archaic.work
algorithm=ed25519
key-id=2026-01
```

Publishers are lowercase ASCII DNS names, without a trailing dot, at least two
labels, and at most 247 characters (leaving space for `_knit`). Namespace must
equal the reversed labels or begin with those labels followed by `.`. It uses
Java identifier components, without ignorable characters, and is limited to
3000 UTF-8 bytes. This direct convention does not translate hyphenated domain
labels into Java identifiers. The CLI additionally validates application module
names using the JDK. Namespace must equal the descriptor's module name.

The signature entry is exactly 88 ASCII bytes: standard padded Base64 of the raw
64-byte Ed25519 signature, without whitespace or a trailing newline. DNS keys
are canonical padded Base64 of the raw 32-byte compressed RFC 8032 public key.

Canonical signature input:

| Field | Encoding |
|---|---|
| Marker | ASCII `knit1-semantic-jar` followed by one zero byte |
| Signed entry count | 4-byte nonnegative big-endian integer |
| Entry-name byte length | 4-byte nonnegative big-endian integer |
| Entry name | Exact UTF-8 bytes |
| Uncompressed content length | 8-byte nonnegative big-endian integer |
| Content | Exact uncompressed bytes |

The last four fields repeat for each signed file entry. Include every file except
the exact signature entry, including metadata and the manifest. Sort names
lexicographically by unsigned UTF-8 bytes; a prefix sorts before its extension.
No name normalization, case folding, separators, padding or trailing data is used.
Integer values fit the nonnegative signed Java `int`/`long` ranges. Actual content
length must match its declared length.

Reject duplicate names, ambiguous paths, file/directory collisions, and unknown
lengths. Empty directories are ignored; directories containing any payload are
rejected. Read physical JAR entries with the standard JAR API, independent of
multi-release logical lookup. Recompression, entry order and timestamp changes
do not affect the signature. Existing JAR manifest signature formats are not used.

Signing/verification is bounded to 128 MiB of uncompressed contents plus entry
name/framing overhead, and 100000 physical entries. Plain Ed25519 providers may
buffer the message; this bounds that work without changing the agreed algorithm
to prehashed Ed25519. Unsigned source consumption retains its existing limits.

Signing snapshots an unsigned JAR into a sibling temporary archive with metadata,
computes the signature, writes and validates the completed archive, then replaces
the artifact with an atomic rename. Failures remove temporary files and preserve
the original artifact. Recompression uses fixed timestamps. Already signed JARs
are rejected, rather than implicitly replacing their publisher. Callers must not
concurrently edit or replace the input artifact during an operation.

## DNSSEC implementation

The initial production resolver targets Linux with trusted `busctl` on PATH and
`systemd-resolved` available on the local system bus, configured for DNSSEC validation
(normally `DNSSEC=yes`). It calls the documented `ResolveRecord` D-Bus method and
checks `SD_RESOLVED_AUTHENTICATED`, unicast DNS protocol and non-synthetic/non-zone
origin. It disables CNAMEs, search suffixes, synthesis, local-zone answers and stale
answers. A resolver supporting `SD_RESOLVED_NO_STALE` is required; unsupported
flags fail the lookup rather than weakening validation. Root/local DNSSEC trust
anchors belong to the trusted system resolver configuration.

The query uses a 20-second service timeout and a 25-second process deadline.
Replies are bounded to 1 MiB/4096 records. TXT RR owner, class, type, wire lengths
and ASCII contents are checked. Remote AD bits and ordinary JNDI DNS answers do
not establish trust. Inherited remote-system-bus selection is removed. Missing
services and failed validation cannot produce a verified result.

References: [systemd-resolved D-Bus API](https://www.freedesktop.org/software/systemd/man/latest/org.freedesktop.resolve1.html),
[API source](https://github.com/systemd/systemd/blob/main/man/org.freedesktop.resolve1.xml),
[busctl output implementation](https://github.com/systemd/systemd/blob/main/src/busctl/busctl.c).

## Validation

Follow the [README build commands](../../../README.md#build-and-test) with JDK 25. All three test modules
are included. Tests cover independent fixed-key canonical signature vectors (including UTF-8 ordering),
raw-key encoding, repacking (including Unicode names), payload/name/metadata
tampering, malformed/duplicate entries, directory payloads, authorization states,
malformed DNS key records, interrupted/unavailable resolution, packaging failure
preservation, final hashes, source/binary descriptor identity, standalone commands,
and offline consumption of signed source distributions.

The DNS tests execute a controlled busctl fixture emitting the documented D-Bus
wire record representation. They check authentication/protocol/origin flags,
owner/type mismatches, truncated replies and concatenated TXT strings. They do
not exercise a live systemd-resolved DNSSEC chain. Deployment acceptance should
verify a real signed artifact against its published key using the target Linux
resolver, then verify rejection after switching to a revoked key (after TTL expiry).
