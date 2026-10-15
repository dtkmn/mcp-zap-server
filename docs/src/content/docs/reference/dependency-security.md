---
title: "Dependency Security Status"
description: "Known upstream dependency findings, affected deployment layers, and the review process for MCP ZAP Server."
---

Dependency assessment recorded **15 October 2026** for `v0.16.0`, now
[released](https://github.com/dtkmn/mcp-zap-server/releases/tag/v0.16.0). The
[pre-patch main Snyk run](https://github.com/dtkmn/mcp-zap-server/actions/runs/38561648021)
analyzed revision `19055b52d2bc3655514fa9f87827f0d2348a1a5c` on **14 October**,
before the two dependency patches below. It reported eight high-severity
results representing seven distinct advisories across its Gradle, docs and
container categories. GitHub also retains older category records for some
of those advisories; those records are not additional distinct vulnerabilities.

The container results cover that run's **Linux AMD64** image. They do not
establish an ARM64 assessment or a scan of the final `v0.16.0` release image.
Release-image and Registry publication are verified separately in the
[release guide](/mcp-zap-server/releases/0.16.0/); publication does not refresh
this dated security assessment or close GitHub alerts.

The maintainer **[@dtkmn](https://github.com/dtkmn)** owns follow-up. Review
remaining findings again by **17 October 2026 UTC**, or sooner when a relevant
upstream fix or deployment change becomes available.

<a id="dependency-patches-in-the-v0160-candidate"></a>

## Dependency patches shipped in v0.16.0

| Component | Advisory and affected behavior | Release disposition |
| --- | --- | --- |
| PostgreSQL JDBC `42.7.12` → `42.7.14` | [GHSA-rhp9-mr79-r74h / CVE-2026-107314](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-rhp9-mr79-r74h): an operator-supplied `requireAuth` value excluding every method, or naming none, silently allowed any authentication method. | Adopt the upstream patched driver. Supplied defaults do not set this property; deployment-specific exposure is unproven. Invalid restrictions now reject the connection; valid settings retain their behavior. Review JDBC URL overrides when upgrading. |
| Docs Zod `4.6.5` → `4.6.7` | [SNYK-JS-ZOD-20510278](https://security.snyk.io/vuln/SNYK-JS-ZOD-20510278): excessive validation issue accumulation in containers. The [upstream patch](https://github.com/colinhacks/zod/pull/6652) checks leading container size limits before parsing elements. | Refresh the existing lockfile within Astro and Sitemap's compatible ranges; no direct dependency or override is needed. This does not impose limits on every unbounded schema. Zod is used by the static docs build and is absent from the Java runtime image. |

Focused before/after checks against the resolved libraries showed the invalid
JDBC restrictions being rejected while normal authentication controls remained
accepted. Oversized bounded arrays, nested arrays and sets produced one size
error after the Zod update, rather than an error for each invalid element;
a valid bounded array still parsed. These scoped checks do not prove safety
for every JDBC deployment or every Zod schema.

The local Snyk tests recorded during release preparation reported no **high or
critical** vulnerabilities in the root Gradle project at the configured high
threshold. The docs Snyk test no longer reported Zod and retained the two cache
advisories below. The complete local npm audit reported zero vulnerabilities,
and `audit-ci.jsonc` had an empty allowlist.
No Snyk ignore, patch policy or new npm exception was added. GitHub alert closure
and scanning both final release-image architectures remain separate checks.

## Remaining upstream findings

The dated assessment retains five advisory records: three native-library
findings from the selected AMD64 scan and two documentation-build findings.
These records remain in scope until fresh evidence changes their disposition.

| Component and reported version | Public advisory | Deployment scope and next action |
| --- | --- | --- |
| Debian 13 `zlib1g`, `1:1.3.dfsg+really1.3.1-1+b1` | [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | Inherited native compression library. The vulnerable nonblocking gzip operation has no demonstrated application call path here; this is a proof gap, not a remediation claim. Debian's tracker lists no fixed Trixie package. Retain the finding and review a compatible patched base image. |
| Debian 13 `libexpat1`, `2.8.3-1~deb13u1` | [CVE-2026-93990](https://security-tracker.debian.org/tracker/CVE-2026-93990) | Native XML surrogate-handling issue. Java XML parsing alone does not establish a path to this library. Fixes in other Debian releases do not establish a compatible Debian 13 fix; keep the runtime finding visible. |
| Debian 13 `libexpat1`, `2.8.3-1~deb13u1` | [CVE-2026-77214](https://security-tracker.debian.org/tracker/CVE-2026-77214) | Separate native buffer over-read requiring the affected parse-buffer API and invalid caller-supplied length. No application-to-operation path is demonstrated. Debian lists a fixed unstable package, but no fixed Trixie package. Await a compatible patched runtime. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93748 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964068) | Snyk still reports the cache-reuse advisory with no fixed version. The [npm/GitHub advisory](https://github.com/advisories/GHSA-ch52-4w7c-c8xp) limits its affected range to `<= 4.2.0`; the maintainer disputes the report in [upstream #56](https://github.com/kornelski/http-cache-semantics/issues/56), now closed. Those sources disagree; a clean npm audit or the closed issue does not clear the Snyk record. |
| Docs `http-cache-semantics`, `4.3.0` | [CVE-2026-93750 / Snyk](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964064) | Separate `Vary` cache-matching advisory. The fresh Snyk test still reports all versions affected and no fix. Keep it recorded and reassess before adding a publicly reachable cache or remote asset processing service. |

## Deployment boundaries

The Dockerfile retains its signed, multi-architecture
`gcr.io/distroless/java25-debian13:nonroot` index pin:
`sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629`.
The upstream tag resolved to a different index during the assessment;
`v0.16.0` retains the pin above. A newer digest does not establish that these
specific native findings are fixed. Nonroot execution and restricted container
privileges reduce exposure and consequences; they do not patch libraries.
Do not mix Debian generations or remove native runtime libraries without
compatibility evidence.

The Pages workflow publishes only static `docs/dist` files. No Astro/Node cache
service is deployed there. Astro's image cache is a build-time dependency, and
no optimized remote-image use was found in the reviewed content. The Docker
build excludes the docs dependency tree. These facts defeat the reported
cross-user HTTP cache path for the current deployed documentation, but do not
prove the dependency safe for other applications. Reassess before introducing
SSR, remote/authenticated assets, a public development server, or an HTTP cache.

## Scanner scope and historical follow-up

The Snyk workflow allows finding-related test failures so it can upload SARIF.
A green workflow means the configured steps completed, not that all findings
were fixed. Keep the actual findings and resolved dependency versions attached
to the release assessment.

The **3 October** baseline also recorded two `gcc-14`/`libstdc++` advisories,
[CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) and
[CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010).
They are absent from the latest selected main scan; absence does not establish
application remediation or replace the earlier native reachability assessment.

The Jackson build-tool alignment shipped in `v0.15.0`: Spring Boot tooling
resolves Jackson `3.1.7`, CycloneDX tooling `2.22.3`, and the application uses
Jackson `3.2.3`. These are separate classpaths. Keep the previously tracked
standalone extension-sample scan coverage gap separate from the fresh root
Gradle project result; successful root dependency resolution does not prove
that optional build was scanned.

## Removing a finding

Retain each record until a fresh scan or supported scope assessment justifies
its changed disposition. Verify resolved packages, legitimate behavior and
actual release-image results for each supported architecture before claiming
remediation. Known dependency findings may remain in a release with documented
scope and follow-up; do not describe that release as vulnerability-free.

Remove an exception only after a complete audit no longer needs it. New exceptions
require the advisory, owner, deployment rationale and UTC expiry. If no compatible
fix is available by the review date, record the next decision explicitly.
