# CI Gate Contracts

`#142 M1 Gate Contract v1` hardens the CI helper around versioned, machine-readable artifacts instead of ad hoc script output.

The helper now emits these stable contracts under the configured output directory:

- `gate-metadata.json`: `ci_gate_result/v1`
- `seed-requests-results.json`: `ci_gate_seed_requests/v1` when seed requests are configured
- `current-findings.json`: `ci_gate_findings_snapshot/v1`
- `findings-diff.json`: `ci_gate_findings_diff/v1` when a baseline is used on the expert surface
- `artifact-manifest.json`: `ci_gate_artifact_manifest/v1`

Human-readable companions remain alongside those contracts:

- `findings-summary.md`
- `findings-diff.txt`
- `gate-summary.md`
- copied report artifacts under `reports/`

## CI Gate Result v1

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
- `report`
- `manifest_path`
- legacy compatibility fields such as `gate_passed`, `new_findings`, and `resolved_findings`

Important behavior:

- artifact paths inside the contract are relative to the output directory
- the contract avoids embedding runner-local absolute paths
- expert-surface runs always emit a normalized `current-findings.json`, even when no baseline is supplied
- `baseline-mode: enforce` fails the gate when `fail-on-new-findings=true`
  but the helper cannot produce a diff, such as when the baseline file is
  missing or the active MCP endpoint only exposes the guided surface
- `baseline-mode: seed` keeps first-run evidence generation non-blocking while
  the baseline is reviewed

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

## Findings Snapshot v1

`current-findings.json` now contains a normalized, deduplicated finding set:

- `contract_version`
- `target_url`
- `finding_count`
- `findings`

Each finding includes:

- `fingerprint`
- `plugin_id`
- `alert_name`
- `risk`
- `confidence`
- `url`
- `param`

Fingerprints are recomputed from normalized fields rather than trusting legacy raw strings. This is what keeps reruns stable when the legacy snapshot export changes ordering or embeds different export timestamps.

Normalization now includes:

- canonical risk labels such as `high` -> `High`
- canonical confidence labels such as `high` -> `High`
- lower-cased scheme and host for URLs
- sorted query-string parameters so `?b=2&a=1` and `?a=1&b=2` fingerprint the same

The helper still accepts legacy baseline files produced directly by `zap_findings_snapshot`:

- legacy input: `version: 1` with `fingerprints`
- current input: `contract_version: ci_gate_findings_snapshot/v1` with `findings`

## Findings Diff v1

`findings-diff.json` is the machine-readable diff contract when a baseline is available on the expert tool surface.

It contains:

- `contract_version`
- `target_url`
- `baseline`
- `current`
- `suppressions`
- `counts`
- `new_finding_groups`
- `resolved_finding_groups`

`findings-diff.txt` is the deterministic text rendering of the same contract.

The helper intentionally avoids legacy exported-at timestamps in the diff output, because those make identical reruns look different for no security reason.

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

## Artifact Manifest v1

`artifact-manifest.json` ties the gate result, summary, diff, and copied report outputs together.

Each manifest entry includes:

- `name`
- `path`
- `media_type`
- `bytes`
- `sha256` for deterministic contract artifacts

The copied report artifact is included in the manifest without a digest requirement because upstream report renderers may embed their own volatile metadata. The report path itself is still normalized to a stable artifact location under `reports/`.
