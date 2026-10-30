# CI Gate Contracts

The CI helper emits versioned, machine-readable artifacts for findings review
and baseline enforcement. Snapshot version 3 records the scanner that produced
the export. A missing finding is only a comparable observation when the ZAP
engine and complete add-on inventory match the baseline.

These provenance contracts are included in released `v0.16.0`.
Upgrade the server and gate together, following the
[release and upgrade guide](../releases/RELEASE_NOTES_0.16.0.md). A `v0.15.0`
server does not export snapshot v3.

The helper now emits these stable contracts under the configured output directory:

- `gate-metadata.json`: `ci_gate_result/v2`
- `seed-requests-results.json`: `ci_gate_seed_requests/v1` when seed requests are configured
- `current-findings.json`: `ci_gate_findings_snapshot/v3`, including normalized
  legacy inputs with their original identity mode and unknown provenance
- `findings-diff.json`: `ci_gate_findings_diff/v3` when a baseline is used and snapshot support is available
- `artifact-manifest.json`: `ci_gate_artifact_manifest/v1`

Human-readable companions remain alongside those contracts:

- `findings-summary.md`
- `findings-diff.txt`
- `gate-summary.md`
- copied report artifacts under `reports/`

## CI Gate Result v2

`gate-metadata.json` is now the canonical gate result contract.

Top-level fields include:

- `contract_version`
- `target_url`
- `tool_surface`
- `crawl_reference`
- `attack_reference`
- `active_scan_requested`
- `scan_policy`
- `baseline_mode`
- `baseline`
- `enforcement`
- `suppressions`
- `seed_requests`
- `findings`
- `scanner`
- `scanner_comparison`
- `report`
- `manifest_path`
- legacy compatibility fields such as `gate_passed`, `new_findings`, and `resolved_findings`

Important behavior:

- artifact paths inside the contract are relative to the output directory
- the contract avoids embedding runner-local absolute paths
- guided runs with snapshot support emit a normalized `current-findings.json`, even when no baseline is supplied
- `baseline-mode: enforce` fails the gate when `fail-on-new-findings=true`
  but the helper cannot produce a comparable diff. This includes a missing
  baseline, an older MCP endpoint without snapshot support, a changed scanner, or unknown scanner
  provenance. Informational added/removed records do not satisfy enforcement.
- `baseline-mode: seed` keeps first-run evidence generation non-blocking while
  the baseline is reviewed
- the helper never overwrites an accepted baseline; replacing it is a separate
  review decision
- copied reports are linked to the snapshot's scanner metadata through the
  gate artifacts; this does not rewrite ZAP's native report metadata
- `enforcement.diff_available` is `true` only for matching scanner provenance,
  even when an informational diff artifact exists for another status

## Seed Requests v1

`seed-requests-results.json` is emitted when a GitHub Actions `seed-requests-file`
or GitLab `ZAP_SEED_REQUESTS_FILE` is configured.

The seed file shape is:

```json
{
  "requests": [
    {
      "name": "valid-api-request",
      "method": "POST",
      "url": "http://app:8080/api/resource",
      "headers": {
        "Content-Type": "application/json"
      },
      "body": {
        "example": true
      },
      "expectedStatus": [200, 201, 204]
    }
  ]
}
```

The result contract includes:

- `contract_version`
- `proxy_url`
- `request_count`
- `failure_count`
- `requests`

Each request result includes only bounded audit data: `name`, `method`,
sanitized `url`, `status`, `expected_status`, `ok`, and `response_bytes`.
Request headers and bodies are intentionally not copied into CI artifacts.
URL userinfo is removed, query strings are reduced to `?redacted`, and the
stored proxy URL is sanitized the same way.

If any seed request returns an unexpected status or cannot be sent through the
ZAP proxy, the helper fails before it claims scan coverage.

## Findings Snapshot v3

`current-findings.json` contains normalized findings and their recorded URL
examples. Exact duplicates are removed, but distinct URL examples for the same
ZAP node remain available for legacy comparisons and suppression matching.

- `contract_version`
- `identity_version`: `1` for legacy URL identity or `2` for node identity
- `target_url`
- `finding_count`: unique finding identities
- `recorded_example_count`: retained example records in node identity mode
- `scanner`: measured scanner provenance, unknown for legacy inputs
- `findings`

Each finding includes:

- `fingerprint`
- `plugin_id`
- `alert_name`
- `risk`
- `confidence`
- `url`
- `param`
- `node_name`, `method`, and `tags` in v2 and v3, preserved when available

Fingerprints are recomputed from normalized fields rather than trusting legacy raw strings. This is what keeps reruns stable when the legacy snapshot export changes ordering or embeds different export timestamps.

Server versions 2 and 3 use the full, case-sensitive `node_name` plus HTTP
method for endpoint identity, with a distinct URL fallback when the node name is absent. Rule,
name, risk, confidence, and parameter distinctions remain. Tags are metadata,
not identity. A `SYSTEMIC` tag means the finding is typically site-wide; counts
describe recorded findings or examples, not all affected endpoints.

Normalization now includes:

- canonical risk labels such as `high` -> `High`
- canonical confidence labels such as `high` -> `High`
- lower-cased scheme and host for URLs
- sorted query-string parameters so `?b=2&a=1` and `?a=1&b=2` fingerprint the same

The helper accepts both server exports and normalized gate baselines:

- server input: `version: 1`, `version: 2`, or `version: 3` with `fingerprints`
- gate input: `ci_gate_findings_snapshot/v1`, `/v2`, or `/v3` with `findings`

The helper normalizes all accepted inputs into gate snapshot v3. Legacy input
remains readable: v1 retains `identity_version: 1`, and v2 retains
`identity_version: 2`. Neither version supplies scanner provenance, so its
comparability is `unknown`. Do not add today's scanner inventory to an old
baseline: it cannot establish which scanner produced those findings. Review a
fresh v3 snapshot before accepting a replacement baseline.

### Scanner Provenance

The v3 server export and normalized gate snapshot preserve this structure:

```json
{
  "scanner": {
    "version": 1,
    "serverVersion": "<running MCP server build version>",
    "serverRevision": null,
    "serverImageDigest": null,
    "zapImageDigest": null,
    "zapVersion": "<running ZAP version>",
    "addOns": [
      { "id": "<installed add-on ID>", "version": "<installed version>", "status": "<installed status>" }
    ],
    "inventoryComplete": true
  }
}
```

ZAP version and installed add-on ID, version, and status are read from the
running engine at snapshot export. `inventoryComplete` indicates whether a
complete usable add-on inventory was obtained. A failed or incomplete read
leaves provenance unknown; its error text is not copied into artifacts.
Add-on status records its reported maturity, such as `alpha`, `beta`, or
`release`; a non-release add-on does not make the inventory incomplete.

The MCP server build version comes from runtime build metadata. Revision is
optional runtime Git metadata. Unavailable values are `null`. Image tags do
not establish running image digests; the server does not inspect a Docker
daemon to fill these fields. The scanner record does not include credentials,
environment contents, local paths, or raw errors.

This is export-time provenance, not a historical scan log. Matching inventories
do not establish equal crawl coverage, authentication, scan policy, target
content, or application remediation. Keep those conditions stable and review
the concrete evidence before interpreting a missing alert as a fix.

## Findings Diff v3

`findings-diff.json` is the machine-readable diff contract when a baseline and snapshot capability are available.

It contains:

- `contract_version`
- `identity_version`
- `target_url`
- `baseline`
- `current`
- `scanner_comparison`: `status` and a bounded `reason`
- `suppressions`
- `counts`
- `new_finding_groups`
- `resolved_finding_groups`
- `observed_changes`
- `observed_added_finding_groups`
- `observed_removed_finding_groups`

`findings-diff.txt` is the deterministic text rendering of the same contract.

When both snapshots preserve node identity, `identity_version` is `2` and
comparisons use node identity with method and URL fallback. If either preserves
legacy v1 identity, it is `1` and both sides use the legacy URL algorithm; the
text output states this explicitly.
The diff's `baseline` and `current` each include unique `finding_count` and
`recorded_example_count` after suppressions.

Scanner comparability is separate from finding identity:

| Status | Meaning | Diff and enforcement behavior |
| --- | --- | --- |
| `matching` | Both snapshots record the same measured ZAP version and complete add-on ID, version, and status inventory. | New, resolved, and unchanged counts use the selected identity mode. A resolved count means no longer observed in the current snapshot; it is not proof of remediation. |
| `different` | Complete measured inventories or engine versions differ. | Comparison counts are `null`; added, removed, and retained observations are informational. Blocking baseline enforcement fails. |
| `unknown` | One snapshot lacks a usable ZAP version or complete add-on inventory, including v1/v2 baselines. | Comparison counts are `null`; added, removed, and retained observations are informational. Blocking baseline enforcement fails. |

Optional MCP server version, revision, and image digests remain useful context
but do not determine this status. A change in those fields alone is not a
scanner inventory difference.

`scanner_comparison.reason` is `null` for matching provenance,
`scanner_inventory_changed` for different provenance, and
`scanner_provenance_unknown` for unknown provenance. The diff's `baseline` and
`current` each preserve their own `scanner` record; gate metadata also links
the baseline and current scanner records to the workflow's result.

`observed_changes` retains set-difference counts as `added`, `removed`, and
`retained` after suppressions. `observed_added_finding_groups` and
`observed_removed_finding_groups` remain available for review. For `different`
or `unknown` provenance, these are not new security regressions or resolved vulnerabilities. Neither
the machine-readable contract nor its text summary labels removed observations
as resolved in that situation. Zero added observations still do not make an
incomparable baseline suitable for enforcement. `counts.new`, `counts.resolved`,
and `counts.unchanged` are each `null`, and the comparable new/resolved group
lists are empty for these two statuses.

The helper intentionally avoids legacy exported-at timestamps in the diff output, because those make identical reruns look different for no security reason.

### Contract Migration

Update the bundled gate with the server. External consumers must explicitly
handle snapshot v3, diff v3, and result v2 before upgrading. Node-based
comparisons keep identity version `2`, with legacy fallback `1`; adding
provenance does not change node fingerprints.
Suppression and artifact-manifest contract versions remain `v1`.

Consumers must check comparability before using new/resolved counts, accept
`null` counts for non-comparable results, and display observed changes as
informational. Do not coerce unknown counts to zero. Older stored snapshots
remain readable, but an enforcement baseline needs a reviewed v3 export with
usable scanner provenance.

## Suppressions Contract

The helper now accepts an optional suppression file via:

- GitHub Actions input: `suppressions-file`
- GitLab variable: `ZAP_SUPPRESSIONS_FILE`

Contract shape:

```json
{
  "contract_version": "ci_gate_suppressions/v1",
  "suppressions": [
    {
      "id": "known-sqli-on-admin",
      "reason": "Tracked internally while fix is in progress",
      "match": {
        "plugin_id": "40018",
        "alert_name": "SQL Injection",
        "url": "https://example.com/admin",
        "param": "id"
      },
      "expires_at": "2026-12-31T00:00:00Z"
    }
  ]
}
```

Rules may match by:

- `fingerprint`
- any exact combination of `plugin_id`, `alert_name`, `risk`, `confidence`, `url`, and `param`

Rules without `expires_at` stay active until removed. Expired rules are retained in contract output but are not applied.

Suppressions are applied to the normalized baseline and current finding sets before diff calculation. That means the gate result remains auditable:

- raw findings stay in `current-findings.json`
- policy-time suppression effects show up in `findings-diff.json` and `gate-metadata.json`

For compatibility, the JSON counters `suppressed_baseline_findings` and
`suppressed_current_findings` keep their names but count removed example records.
The text output labels them as recorded examples. Suppressing one URL example
does not remove a node identity if another unsuppressed example remains.

## Artifact Manifest v1

`artifact-manifest.json` ties the gate result, summary, diff, and copied report outputs together.

Each manifest entry includes:

- `name`
- `path`
- `media_type`
- `bytes`
- `sha256` for deterministic contract artifacts

The copied report artifact is included in the manifest without a digest requirement because upstream report renderers may embed their own volatile metadata. The report path itself is still normalized to a stable artifact location under `reports/`.

The manifest and gate metadata link reports generated in this gate workflow to
the current snapshot and its scanner provenance. They do not modify native
ZAP report fields or establish the inventory used by an earlier scan. Reports,
snapshots, and summaries still require review for target-specific sensitive
information before sharing outside the team.
