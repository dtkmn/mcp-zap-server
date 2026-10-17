---
title: "Dependency Security Status"
description: "Known upstream dependency findings, affected deployment layers, and the review process for MCP ZAP Server."
---

Use this page when evaluating the dependencies in a release or choosing a
runtime image. Advisory applicability depends on the resolved package, image
architecture and deployment configuration. Image publication and a successful
workflow do not establish that all dependency findings are fixed.

Consult the linked upstream advisories and the packages in your deployed image
before making an upgrade decision. The [release guide](/mcp-zap-server/releases/0.16.0/)
describes the scanner's version and image requirements.

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
| Debian 13 `zlib1g`, `1:1.3.dfsg+really1.3.1-1+b1` | [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | Inherited native compression library. The vulnerable nonblocking gzip operation has no demonstrated application call path here; this is a proof gap, not a remediation claim. Debian's tracker lists no fixed Trixie package. Retain the finding and review a compatible patched base image. |
| Debian 13 `libexpat1`, `2.8.3-1~deb13u1` | [CVE-2026-93990](https://security-tracker.debian.org/tracker/CVE-2026-93990) | Native XML surrogate-handling issue. Java XML parsing alone does not establish a path to this library. Fixes in other Debian releases do not establish a compatible Debian 13 fix; keep the runtime finding visible. |
| Debian 13 `libexpat1`, `2.8.3-1~deb13u1` | [CVE-2026-77214](https://security-tracker.debian.org/tracker/CVE-2026-77214) | Separate native buffer over-read requiring the affected parse-buffer API and invalid caller-supplied length. No application-to-operation path is demonstrated. Debian lists a fixed unstable package, but no fixed Trixie package. Await a compatible patched runtime. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93748 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964068) | Snyk lists the cache-reuse advisory without a fixed version. The [npm/GitHub advisory](https://github.com/advisories/GHSA-ch52-4w7c-c8xp) limits its affected range to `<= 4.2.0`; the maintainer disputes the report in [upstream #56](https://github.com/kornelski/http-cache-semantics/issues/56), now closed. Those sources disagree; a clean npm audit or the closed issue does not clear the Snyk record. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93750 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964064) | Separate `Vary` cache-matching advisory. Snyk lists all versions as affected without a fixed version. Keep it recorded and reassess before adding a publicly reachable cache or remote asset processing service. |

## Deployment boundaries

The Dockerfile retains its signed, multi-architecture
`gcr.io/distroless/java25-debian13:nonroot` index pin:
`sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629`.
A newer digest does not establish that these specific native findings are
fixed; inspect its package versions before replacing the pin. Nonroot execution
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
