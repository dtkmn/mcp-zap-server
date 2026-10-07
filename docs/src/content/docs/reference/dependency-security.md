---
title: "Dependency Security Status"
description: "Known upstream dependency findings, affected deployment layers, and the review process for MCP ZAP Server."
---

Runtime and Snyk baseline: **3 October 2026**, against the `v0.14.0` preparation commit
`8cbcd1a455e2307f029537d99a6df34bd9f9146d` and its main-branch scan results.
The six upstream findings in the table below were open in that scan; this
dated baseline does not establish that every affected native operation is
unreachable. The build-tool alignment section was updated on **4 October 2026**
for `v0.15.0`. The documentation dependency update below was reviewed on
**6 October 2026**; it does not establish closure of the historical Snyk alerts.
Preparing `v0.15.0` does not refresh this dated Snyk baseline.

The maintainer **[@dtkmn](https://github.com/dtkmn)** owns follow-up for each row.
The next review of the remaining findings is due **17 October 2026 UTC**, or
sooner when a relevant fix or deployment change becomes available.

## Findings from the 3 October scan

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
| Docs `http-cache-semantics`, `4.2.0` | [CVE-2026-93748](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964068) | Astro build dependency; absent from the MCP Java runtime image. The current docs lockfile resolves `4.3.0`, outside the npm advisory's affected range through `4.2.0`; the obsolete npm exception has been removed. Closure of the historical Snyk alert has not been established. |
| Docs `http-cache-semantics`, `4.2.0` | [CVE-2026-93750](https://security.snyk.io/vuln/SNYK-JS-HTTPCACHESEMANTICS-19964064) | Separate cache finding in the same docs dependency. On 6 October, Snyk still lists all versions as affected and no fixed version. Keep this finding visible; selecting `4.3.0` or passing npm audit does not establish its remediation or Snyk alert closure. |

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

## Documentation dependency update — 6 October 2026

The current docs lockfile resolves `http-cache-semantics` to `4.3.0`, outside
the affected range through `4.2.0` in
[GHSA-ch52-4w7c-c8xp](https://github.com/advisories/GHSA-ch52-4w7c-c8xp).
A fresh npm report no longer flags that advisory, so its temporary exception
has been removed from
[`audit-ci.jsonc`](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/audit-ci.jsonc).
The allowlist is empty. The separate Snyk cache finding remains as described in
the historical findings table.

The newly reported
[GHSA-rj75-hqrm-r3gf](https://github.com/advisories/GHSA-rj75-hqrm-r3gf)
affects `postcss-selector-parser` versions below `7.1.6`. The existing docs
dependency overrides now select `^7.1.6`, and the updated lockfile resolves
`7.1.6`. A clean local installation, the CI audit command, the content check
and the site build passed. The complete npm report contained zero reported
vulnerabilities, and the generated CSS was unchanged from the previous build.
These local results do not establish a passing GitHub run or resolve the
separate Snyk findings. No Snyk ignores or new npm exceptions were added.

The docs audit continues to block unaccepted findings of moderate severity or
higher. Future exceptions require a named advisory, owner, rationale and UTC
expiry. Review each newly reported advisory separately and confirm that a
complete audit report was produced.

## Build tooling and coverage follow-up

The application's Jackson `3.2.3` BOM does not align Gradle plugin classpaths.
The `v0.15.0` build uses two separate Gradle buildscript BOMs: Jackson
`3.1.7` for Spring Boot tooling and Jackson `2.22.3` for CycloneDX tooling. These
replace the previously resolved `3.1.5` and `2.22.1` families and meet the fixed
version floors for the reviewed [core memory-growth](https://github.com/advisories/GHSA-7hhh-6rmp-j9qf),
[core CPU-exhaustion](https://github.com/advisories/GHSA-p6pp-m3f8-5c89) and
[databind CPU-exhaustion](https://github.com/advisories/GHSA-cxp5-3px4-pw24)
advisories, alongside the earlier Jackson findings tracked in
[issue #275](https://github.com/dtkmn/mcp-zap-server/issues/275).

Regular platform constraints align the modules in each family without forcing
all dependency configurations. Shared `jackson-annotations` remains `2.22`;
the application's selected runtime graph and packaged Jackson `3.2.3` family
are unchanged. Plugin versions and Gateway Core/WebFlux `0.11.0` are unchanged.
This removes affected versions from the selected build-tool graph; it does not
establish that an attacker could reach the vulnerable library operations in
the previous build.

Published `v0.14.0` does not contain this build-tool alignment. Verify the fresh
GitHub dependency submission and targeted alert state before closing #275;
local dependency resolution and a runtime scan alone do not establish alert
closure. The follow-up retains the **17 October 2026 UTC** review deadline.
See the [v0.15.0 release notes](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/releases/RELEASE_NOTES_0.15.0.md)
for the broader upgrade requirements and publication checks.

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

Remove an npm exception after its affected dependency path is resolved and a
fresh, complete audit report no longer needs it. Keep each risk record until its
finding is resolved or its changed disposition is supported by new evidence.
If no compatible fix is available by the review date, reassess exposure and
record the next decision explicitly; do not silently extend the exception.
