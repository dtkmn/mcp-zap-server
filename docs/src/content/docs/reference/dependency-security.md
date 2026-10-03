---
title: "Dependency Security Status"
description: "Known upstream dependency findings, affected deployment layers, and the review process for MCP ZAP Server."
---

Last reviewed: **3 October 2026**, against the `v0.14.0` preparation commit
`8cbcd1a455e2307f029537d99a6df34bd9f9146d` and its main-branch scan results.
Findings below remain open; this review does not patch their dependencies or
establish that every affected native operation is unreachable.

The maintainer **[@dtkmn](https://github.com/dtkmn)** owns follow-up for each row.
The next review is due **17 October 2026 UTC**, or sooner when a relevant fix or
deployment change becomes available. The current engineering recommendation is
to retain the pinned runtime and static documentation stack while awaiting
compatible fixes, with these findings visible in the release decision.

## Open upstream findings

These six high-severity findings were reported by the
[main Snyk run](https://github.com/dtkmn/mcp-zap-server/actions/runs/37114638325).
Container package versions below were observed in its **Linux AMD64** image;
this run does not establish an ARM64 vulnerability assessment.

| Component and observed version | Public advisory | Scope and disposition |
| --- | --- | --- |
| Debian 13 `gcc-14` source packages, `14.2.0-19` | [CVE-2026-95619](https://security-tracker.debian.org/tracker/CVE-2026-95619) | Inherited runtime `libstdc++` aligned-allocation issue. No application-to-vulnerable-operation path demonstrated; Debian lists no fixed package. Retain the finding and reassess the runtime image when a fix is available. |
| Debian 13 `gcc-14` source packages, `14.2.0-19` | [CVE-2026-102010](https://security-tracker.debian.org/tracker/CVE-2026-102010) | Inherited runtime `libstdc++` binary-heap `erase_if` issue. No application-to-vulnerable-operation path demonstrated; Debian lists no fixed package. Retain separately from the allocation issue. |
| Debian 13 `zlib1g`, `1:1.3.dfsg+really1.3.1-1+b1` | [CVE-2026-85091](https://security-tracker.debian.org/tracker/CVE-2026-85091) | Native compression library in the runtime image. Application reachability remains unproven; Debian lists it as vulnerable without a fixed package. Await a compatible patched base image. |
| Debian 13 `libexpat1`, `2.8.3-1~deb13u1` | [CVE-2026-93990](https://security-tracker.debian.org/tracker/CVE-2026-93990) | Native XML library inherited through the base image. Java XML parsing does not establish a path to this library. Fixed packages are listed for other Debian releases, but no Debian 13 fix is listed. Await a compatible patched base image. |
| Docs `http-cache-semantics`, `4.2.0` | [CVE-2026-93748](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964068) | Astro build dependency; absent from the MCP Java runtime image. No remote optimized-image use found in current docs. No patched library version is listed. The named npm exception expires on 17 October 2026 UTC. |
| Docs `http-cache-semantics`, `4.2.0` | [CVE-2026-93750](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964064) | Separate cache finding in the same docs dependency. The current static-site exposure assessment also applies, but it remains a separate finding with no patched version listed and no npm exception added for it. |

## Deployment boundaries

The Dockerfile pins `gcr.io/distroless/java25-debian13:nonroot` at index digest
`sha256:ca60da1345c0f17b6d019049e6749e15f10fd3c0da86dec938d2b4ec565d0629`.
On the review date, the published tag had that same digest; updating the pin
alone would not change these packages. The image runs as a nonroot user, and
the Helm references restrict container privileges. Those controls reduce attack
surface and consequences; they do not patch native libraries or prove the CVEs
cannot be reached. Removing runtime libraries or switching Debian generations
without compatibility testing is not the remediation plan.

The docs site publishes static files from `docs/dist`; no Astro/Node cache
service is deployed by the Pages workflow. Astro uses the affected package in
its remote-image build cache, and no such optimized-image use was found in the
reviewed content. The Docker build excludes `docs/` and copies only the Java
application and health probe into the runtime image. Keep docs static and
reassess both cache findings before adding server rendering, remote asset
processing, authenticated remote assets, or a publicly exposed development
server. This is a scoped exposure assessment, not a claim that the library is
fixed.

## Scanner results and exceptions

The Snyk workflow permits finding-related scan failures so it can upload SARIF
to GitHub. A green workflow confirms its configured steps completed; review the
actual findings before deciding on a release. The main application and bundled
Java dependencies reported no findings at the configured **high** threshold in
this run, which is not a zero-vulnerability guarantee.

The docs audit uses a named
[GHSA-ch52-4w7c-c8xp exception](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/audit-ci.jsonc)
with an owner, rationale and UTC expiry. It accepts the specified risk rather
than fixing it. It does not suppress every advisory for `http-cache-semantics`,
and this review adds no Snyk ignores or broader npm exceptions. If npm later
reports a separate advisory for CVE-2026-93750, it requires its own review.
The docs audit will block when the existing exception expires if its finding
persists; any renewal requires a fresh assessment and explicit new review date.
Check that an actual, nonempty audit report was produced; absent output is not
successful scan evidence.

## Build tooling and coverage follow-up

The application's Jackson `3.2.3` BOM does not align Gradle plugin classpaths.
The current build-tool graph separately resolves Jackson `3.1.5` through Spring
Boot tooling and Jackson `2.22.1` through CycloneDX tooling. Compatible alignment
and dependency-graph verification remain tracked in
[issue #275](https://github.com/dtkmn/mcp-zap-server/issues/275), with the same
17 October review date. Keep this distinct from the packaged application's
Jackson version; the runtime scan cannot close the build-tool follow-up.

Snyk also reported that one of two detected Gradle manifests did not return
dependencies. The experimental standalone extension sample is a separate build
using a locally staged extension API, and this run did not establish its scan
coverage. The follow-up is to stage that API, scan the standalone build explicitly,
and verify the returned dependency report before changing the workflow. Do not
silently exclude it or infer coverage from staging alone.

## Removing a finding

For each row, check its advisory and resolved package version at the next review.
Adopt a compatible fixed dependency or signed base-image digest, then verify
application packaging, health and integration behavior. Scan the actual release
image for each supported architecture; a clean build or an AMD64 scan alone does
not establish ARM64 results. For docs changes, run the content check, site build
and dependency audit against the updated lockfile.

Remove the npm exception after its affected dependency path is resolved and a
fresh, complete audit report no longer needs it. Keep each risk record until its
finding is resolved or its changed disposition is supported by new evidence.
If no compatible fix is available by the review date, reassess exposure and
record the next decision explicitly; do not silently extend the exception.
