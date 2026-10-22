---
title: "Dependency Security Status"
description: "Known upstream dependency findings, affected deployment layers, and the review process for MCP ZAP Server."
---

Use this page when evaluating the dependencies in a release or choosing a
runtime image. Advisory applicability depends on the resolved package, image
architecture and deployment configuration. Image publication and a successful
workflow do not establish that all dependency findings are fixed.

Consult the linked upstream advisories and the packages in your deployed image
before making an upgrade decision. The [release guide](/mcp-zap-server/releases/0.17.0/)
describes the scanner's version and image requirements.

## Dependency updates in v0.17.0 source

Verify publication before selecting a `v0.17.0` image. These source dependency
updates do not establish that an existing deployment or a published artifact
has been upgraded.

| Component | Advisory and scope | Source update |
| --- | --- | --- |
| Go healthcheck standard library | [GO-2026-6809 / CVE-2026-111943](https://pkg.go.dev/vuln/GO-2026-6809) affects `crypto/x509.Certificate.Verify` before Go `1.27.3` on the 1.27 branch. The configured probe uses fixed loopback HTTP, disables proxies and rejects redirects; that operation does not perform TLS certificate verification. | Build the static helper with Go `1.27.3`. Keep the Docker builder and the source module's preferred toolchain aligned. A compiler declaration alone does not update an already built binary. |
| Debian 13 `libexpat1` | [DSA-6566-1](https://security-tracker.debian.org/tracker/DSA-6566-1) provides a fixed Trixie security package, `2.9.0-1~deb13u1`. Java XML parsing alone does not establish a path to native Expat. | The selected Java 25 / Debian 13 nonroot base contains `2.9.0-1~deb13u1` for Linux AMD64 and ARM64. This does not fix the separate zlib finding below. |
| Standalone extension sample Spring dependencies | [Spring data-binding advisory](https://spring.io/security/cve-2026-59282/) affects Framework `7.0.0`–`7.0.8` under its stated data-binding conditions. | Align the example's `compileOnly` Boot dependency to `4.1.1`, matching the application's Framework `7.0.9` baseline. The metadata example does not bundle Spring implementation libraries or provide an untrusted data-binding or expression-evaluation service. |

<a id="dependency-patches-in-the-v0160-candidate"></a>

## Dependency patches shipped in v0.16.0

| Component | Advisory and affected behavior | Release disposition |
| --- | --- | --- |
| PostgreSQL JDBC `42.7.12` → `42.7.14` | [GHSA-rhp9-mr79-r74h / CVE-2026-107314](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-rhp9-mr79-r74h): an operator-supplied `requireAuth` value excluding every method, or naming none, silently allowed any authentication method. | Adopt the upstream patched driver. Supplied defaults do not set this property; deployment-specific exposure is unproven. Invalid restrictions now reject the connection; valid settings retain their behavior. Review JDBC URL overrides when upgrading. |
| Docs Zod `4.6.5` → `4.6.7` | [SNYK-JS-ZOD-20510278](https://security.snyk.io/vuln/SNYK-JS-ZOD-20510278): excessive validation issue accumulation in containers. The [upstream patch](https://github.com/colinhacks/zod/pull/6652) checks leading container size limits before parsing elements. | Refresh the existing lockfile within Astro and Sitemap's compatible ranges; no direct dependency or override is needed. This does not impose limits on every unbounded schema. Zod is used by the static docs build and is absent from the Java runtime image. |

## Remaining upstream findings

The advisories below affect native runtime packages or documentation-build
dependencies. Match each record to the actual package and architecture in your
deployment; an assessment of one image does not cover another architecture or a
later image build.

| Component and reported version | Public advisory | Deployment scope and next action |
| --- | --- | --- |
| Debian 13 `zlib1g`, `1:1.3.dfsg+really1.3.1-1+b1` | [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | Debian and [Snyk](https://security.snyk.io/vuln/SNYK-DEBIAN13-ZLIB-19520500) list no fixed Debian 13 package. An [upstream source fix](https://github.com/madler/zlib/commit/df84af25dc1942490e1d1c899a07619152a46148) exists, but the [current release, `1.3.2`](https://zlib.net/), does not contain it. The [affected-version range remains disputed](https://github.com/madler/zlib/issues/1310); do not classify the installed `1.3.1` code as unaffected solely from the newer function named in the CVE summary. No application call path to the affected gzip writer is demonstrated here. Retain the finding and select a compatible patched base when available; source-patch availability alone does not establish a fixed runtime image. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93748 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964068) | Snyk lists the cache-reuse advisory without a fixed version. The [npm/GitHub advisory](https://github.com/advisories/GHSA-ch52-4w7c-c8xp) limits its affected range to `<= 4.2.0`; the maintainer disputes the report in [upstream #56](https://github.com/kornelski/http-cache-semantics/issues/56), now closed. Those sources disagree; a clean npm audit or the closed issue does not clear the Snyk record. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93750 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964064) | Separate `Vary` cache-matching advisory. Snyk lists all versions as affected without a fixed version. Keep it recorded and reassess before adding a publicly reachable cache or remote asset processing service. |

## Deployment boundaries

The Dockerfile selects the signed, multi-architecture
`gcr.io/distroless/java25-debian13:nonroot` index pin:
`sha256:69514ce1edfe8759f5169870e8f9d4c9f2b831fb0b305a6809a269a25c515a2f`.
This is the base-image index, not the published MCP application image digest.
Inspect the packages in the actual application image before a deployment; a newer
digest alone does not establish that every native finding is fixed. Nonroot execution
and restricted container privileges reduce exposure and consequences; they do
not patch libraries.
Do not mix Debian generations or remove native runtime libraries without
compatibility evidence.

The Pages workflow publishes only static `docs/dist` files. No Astro/Node cache
service is deployed there. Astro's image cache is a build-time dependency; the
static-site deployment does not expose it as a cross-user HTTP cache. The Docker build excludes the
docs dependency tree. This deployment boundary does not establish that the
dependency is safe in another application. Reassess before introducing
SSR, remote/authenticated assets, a public development server, or an HTTP cache.

<a id="scanner-scope-and-historical-follow-up"></a>

## Assessing Your Build

The Snyk workflow allows finding-related test failures so it can upload SARIF.
A green workflow means the configured steps completed, not that all findings
were fixed. Inspect the actual findings and resolved dependency versions when
reviewing a release.

Assess native compiler runtime packages separately from Java dependencies. In
particular, check any installed `gcc-14` or `libstdc++` package against
[CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) and
[CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010).
The absence of a scanner result does not establish that an affected operation
is unreachable or patched.

The Jackson build-tool alignment shipped in `v0.15.0`: Spring Boot tooling
resolves Jackson `3.1.7`, CycloneDX tooling `2.22.3`, and the application uses
Jackson `3.2.3`. These are separate classpaths. Assess standalone extension
samples and optional builds separately from the root Gradle project.

## Removing a finding

Retain each record until a fresh scan or supported scope assessment justifies
its changed disposition. Verify resolved packages, legitimate behavior and
actual release-image results for each supported architecture before claiming
remediation. Known dependency findings may remain in a release with documented
scope and follow-up; do not describe that release as vulnerability-free.

Remove an exception only after a complete dependency assessment no longer
needs it. Record the advisory, owner, deployment rationale and UTC expiry for
any new exception in the dependency-check configuration.
