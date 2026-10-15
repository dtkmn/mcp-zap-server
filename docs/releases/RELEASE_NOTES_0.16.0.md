# Release Notes and Upgrade Guide - Version 0.16.0

**MCP ZAP Server `v0.16.0` is released.** The
[GitHub release](https://github.com/dtkmn/mcp-zap-server/releases/tag/v0.16.0) and
[release-image workflow](https://github.com/dtkmn/mcp-zap-server/actions/runs/38648232124)
use revision `0ebe11c3ae4c17de59fde0654e19b3e35e42ca20`. The workflow successfully
published `v0.16.0` and `latest` images to GHCR and Docker Hub for **Linux AMD64
and ARM64**. Chart `0.16.0` is included in the tagged source and selects the
matching MCP image.

Both registries published this multi-architecture image index digest:

```text
sha256:c62be3c0622a52cd5e81747dde3c32117e480853ae2733bb3f3281434618f673
```

For immutable deployment, use
`ghcr.io/dtkmn/mcp-zap-server:v0.16.0@sha256:c62be3c0622a52cd5e81747dde3c32117e480853ae2733bb3f3281434618f673`
or the same tag and digest with `docker.io/dtkmn/mcp-zap-server`.
The [MCP Registry](https://registry.modelcontextprotocol.io/v0.1/servers/io.github.dtkmn%2Fmcp-zap-server/versions/latest)
published `0.16.0` as active/latest on **15 October 2026 in Sydney**
(`2026-10-14T23:17:48.215193Z`). Its package records reference the versioned
images. Image publication does not change the separate Mac preview or plugin
directory status described below.

## What Changes For Users

- Findings snapshots record the running ZAP engine and installed add-ons.
  New/resolved findings require complete matching scanner inventories; changed
  or unknown inventories produce informational observations and fail enforced
  baseline gates.
- Automation Framework plans validate whole-plan durations and runtime
  capabilities before dispatch. Status reports no longer infer success from a
  finish timestamp and an empty error list.
- Automation plan and artifact handling rejects unsafe linked paths and
  nonliteral report output locations. Target-scoped MCP reports retain alerts
  recorded at an origin's empty root path.
- The local Docker launcher preserves settings and credentials, starts a
  versioned image, and checks container health and authenticated MCP readiness.
  MCP ZAP Connect adds Cursor onboarding through the existing shared setup skill.

## Upgrade From v0.15.0

Align the server image, chart `0.16.0`, copied CI action or
helper, and external evidence readers. The chart and local Compose defaults
select image `v0.16.0`; an existing `.env` image choice or Helm image override
must be updated deliberately. For digest-pinned deployments, select the digest
of the newly published image rather than reusing the old release's digest.

Preserve API keys, private client settings, report workspaces and database data.
No new database migration is required from `v0.15.0`: Flyway migrations remain
**V1–V8**. Storage options introduced in `v0.15.0` retain their existing migration
requirements; this upgrade does not move files into a new workspace layout.

### Review PostgreSQL Authentication Settings

The bundled PostgreSQL JDBC driver advances from `42.7.12` to `42.7.14` for
[upstream security fixes](https://github.com/pgjdbc/pgjdbc/releases/tag/REL42.7.14).
If an operator-supplied JDBC URL uses `requireAuth` with every authentication
method excluded, or with no method named, connections now fail instead of
silently ignoring the restriction. Replace such an accidental setting with
the methods your database actually supports, such as `requireAuth=scram-sha-256`.
Unset values, valid positive lists and partial exclusions retain their behavior.
Use `sslmode=verify-full` with a trusted CA for remote databases; the driver fix
does not configure transport security for you.

The documentation lockfile also advances Zod from `4.6.5` to `4.6.7`. This is a
docs build dependency and adds no Node.js requirement to the Java server.
Remaining upstream findings and their deployment boundaries are listed in the
[dependency security status](../src/content/docs/reference/dependency-security.md).

### Migrate CI Evidence And Baselines

Update the gate implementation **together with the server**. External consumers
must support these contracts before accepting the upgrade:

| Artifact | v0.16.0 contract | Required consumer behavior |
| --- | --- | --- |
| Server findings export | `version: 3` | Read the measured `scanner` record. |
| `current-findings.json` | `ci_gate_findings_snapshot/v3` | Preserve identity mode and scanner provenance. |
| `findings-diff.json` | `ci_gate_findings_diff/v3` | Check `scanner_comparison.status` before interpreting counts. |
| `gate-metadata.json` | `ci_gate_result/v2` | Accept nullable comparison counts and scanner evidence. |

Comparability is `matching` only when both exports contain the same measured
ZAP version and complete add-on ID, version and status inventory. Optional MCP
build identity is useful context but does not determine scanner comparability.
Unavailable revision or image digests remain `null`; an image tag is not a
measured running-image digest.

For `different` or `unknown` provenance, new, resolved and unchanged counts are
`null`. Added, removed and retained observations remain available for review.
Do not coerce unknown counts to zero or describe removed observations as fixes.
Matching inventory also does not prove equal crawl coverage, authentication,
scan policy or application remediation.

Version 1 and 2 snapshots remain readable with **unknown comparability**. Node
identity remains version 2; legacy v1 retains its URL identity. Suppression,
seed-request and artifact-manifest contracts remain v1. To restore enforcement:

1. Upgrade the server and gate, and keep ZAP and its add-ons stable during the
   scan and evidence export.
2. Use `baseline-mode: seed` to produce a fresh v3 snapshot for review.
3. Review the target evidence and scanner inventory, then deliberately accept
   the replacement baseline. Do not add today's inventory to an old snapshot.
4. Return to `baseline-mode: enforce` with `fail-on-new-findings: true`. A missing
   or incomparable baseline fails enforcement, even with zero added observations.

The helper never automatically overwrites an accepted baseline. See the
[CI gate contracts](../scanning/CI_GATE_CONTRACTS.md) for field-level details.

### Review Automation Plans And Status Readers

`env.parameters.maxDuration` is a whole-plan limit in **seconds**. Accepted YAML
integers are `0` through `2147483647`; zero means unlimited. Negative values,
null, fractions, quoted strings and overflow are rejected. Omitting it retains
the existing behavior. Declaring it requires measured **ZAP 2.17.0 or newer** and
**Automation Framework 0.61.0 or newer**. Unknown or unsupported runtime
capabilities are rejected before the plan is written or dispatched. Unsupported
authentication `pollMethod` configurations remain rejected.

Job-level `spider` and `passiveScan-wait` durations remain in **minutes**. The
canonical example sets a 300-second whole-plan limit and an explicit
`alwaysRun: true` report job to capture partial evidence after a soft stop.

Expiry is cooperative. In Automation Framework `0.61.0`, the timer starts after
environment/configuration preparation; report jobs marked `alwaysRun` and
in-flight requestor work can run past expiry. Stopping a passive wait leaves
background passive analysis active. With Automation Framework `0.61.0` and
Spider `0.20.0`, slow seed-page preparation can allow crawling to start after
expiry. Keep job limits and inspect scanner state. A strict traffic cutoff
requires an operator-controlled engine or network boundary.

Update status consumers for `Successful: pending` while running, `no` when a
finished plan reports errors, and `unknown` otherwise. `Completed: yes` means
ZAP supplied a finish timestamp. The native progress API does not expose its
termination reason; clean cancellation, zero errors or report presence do not
prove a complete successful scan. Review warnings, job messages, target traffic
and passive status before treating evidence as final. A client timeout alone
does not cancel the native plan.

### Review Automation Workspace And Report Paths

Use ordinary directories and regular files inside the configured automation
workspace. Symbolic links in the root, plan paths, report directories or selected
report files are rejected; multiple hard links are rejected when the filesystem
exposes their count. Reads use no-follow handling and artifact previews are
bounded. Ordinary missing report outputs retain their existing behavior.

Report jobs must use literal `reportDir` and `reportFile` values without `${...}`
substitutions. `reportFile` is a filename pattern: directory separators, drive
components, dollar signs and `.`/`..` names are rejected. Omitted or empty values
retain the defaults, and ordinary ZAP filename patterns remain supported.

These checks do not protect against hostile local writers concurrently swapping
filesystem ancestors. Limit write access to trusted MCP/ZAP processes and use
filesystem permissions or deployment isolation. Native Automation Framework
reports retain ZAP's own template content; the MCP target-scoped report
sanitization does not apply to that path.

See [Automation Framework](../src/content/docs/scanning/automation-framework.md)
for shared workspace configuration and the supported plan contract.

### Target-Scoped Report Correction

MCP reports scoped to `https://target.example` and
`https://target.example/` now include alerts recorded with an empty root path.
Other origins and ports stay excluded, and non-root path prefixes remain
case-sensitive. Temporary report contexts stay outside scan scope and are
removed afterward. Existing artifact authorization and session-metadata
filtering remain enforced. Report family/instance totals still need to be
compared with concrete findings evidence rather than assumed interchangeable.

## Installation And Compatibility Boundaries

- Guided routing is unchanged: `auto` uses HTTP, with AJAX fallback only when an
  unauthenticated direct HTTP start fails. It does not detect JavaScript coverage
  or select Client Spider. Select `client` explicitly for JavaScript apps;
  explicit AJAX compatibility remains available. The routing redesign in
  [#319](https://github.com/dtkmn/mcp-zap-server/issues/319) is deferred.
- Java **25**, the configured ZAP **2.17.0** baseline and Gateway Core/WebFlux
  **0.11.0** remain unchanged. Running add-on inventory must be measured.
  The extension API remains `experimental-local`; its local proof version
  follows `0.16.0` without a public binary compatibility promise.
- MCP ZAP Connect remains plugin **0.3.0**. Codex and Cursor use the same Docker
  setup skill with private client settings and status-only verification.
  Public directory submission/approval is pending.
- The maintainer-supplied Apple Silicon preview remains unsigned, with public
  signing/notarization and clean browser-download installation verification
  pending; no public Mac binary is attached to `v0.16.0`. Previously built
  `v0.15.0` previews and recorded private Mac/ChatGPT
  tests are historical evidence; they do not validate a `v0.16.0` binary.
  Mac packaging and its source companion remain a separate manual workflow;
  automatic Mac packaging is paused.
- Recorded EC2 and EKS evaluations used `v0.15.0` or the explicitly documented
  matched prerelease application/chart. Current instructions select published
  `v0.16.0`; those older evaluations do not prove this version on AWS.

The JDBC and Zod patches above do not clear the remaining native-library and
docs cache advisories. Review the dated
[dependency security status](../src/content/docs/reference/dependency-security.md)
and verify release checks and the published artifacts separately before
deployment.
