# GitHub Actions Integration

This guide shows how to run `mcp-zap-server` as a GitHub Actions security gate without inventing your own MCP client glue.

The integration ships as a reusable composite action in [`.github/actions/zap-security-gate`](../../.github/actions/zap-security-gate/action.yml) plus a copyable workflow example in [`examples/github-actions/zap-security-gate.yml`](../../examples/github-actions/zap-security-gate.yml).

The [Juice Shop workflow](../../.github/workflows/zap-security-gate-juice-shop.yml)
provides a runnable example that builds the image, starts Juice Shop on the same
Compose network and runs the action twice. Review runner permissions, target
connectivity, secrets and artifact access in your own repository before enabling
a release gate.

The artifact contracts emitted by that helper are defined in [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).
For the team operating model around seed mode, artifact review, baseline
acceptance, and enforcement, use the
[Seeded API Gate Playbook](../scanning/SEEDED_API_GATE_PLAYBOOK.md).

For pilot installation, rollback, and preflight checks, use the
[GitHub CI Pack Pilot Install Runbook](../operator/runbooks/GITHUB_CI_PACK_PILOT_INSTALL.md).

## What The Action Does

The action brings up a minimal ZAP + MCP stack, optionally replays configured HTTP seed requests through the ZAP proxy, then uses the MCP tools already exposed by this repository:

1. optional seed HTTP requests through the ZAP proxy
2. `zap_crawl_start`
3. `zap_crawl_status`
4. `zap_attack_start` (optional)
5. `zap_attack_status` (optional)
6. `zap_passive_scan_wait`
7. `zap_findings_summary`
8. `zap_findings_snapshot`
9. compare the normalized snapshot against the baseline file when present
10. `zap_report_generate`

The helper uses guided start, status and report tools. It detects snapshot support from `tools/list`; the current guided catalog provides it. Older guided servers without snapshots cannot produce an enforceable baseline comparison. The helper never falls back to retired direct-start tools.

Artifacts emitted into the configured output directory include:

- `findings-summary.md`
- `current-findings.json`
- `seed-requests-results.json` when seed requests are configured
- `findings-diff.txt` and `findings-diff.json` when a baseline is used
- `gate-metadata.json`
- `artifact-manifest.json`
- `webhook-delivery.json` when the optional callback step is enabled
- copied report artifacts under `reports/` when the generated report file can be mapped back to the runner workspace

The Compose stack keeps launch receipts in the MCP-only `mcp-launch-safety`
volume, outside the shared ZAP workspace and uploaded artifacts. Preserve that
volume when replacing the MCP container. Do not delete unresolved receipts to
unblock a scan; follow [launch recovery](../src/content/docs/operations/scan-queue-retry-policy.md).
The journal protects uncertain starts but does not make in-memory job history durable.

## Default Use Case

The default template is aimed at OSS and enterprise teams alike:

- OSS/self-hosted teams get a copyable CI gate with no extra orchestrator code
- enterprise teams get the same reference flow that later quality-gate packs can build on

The out-of-the-box template is best for targets that are already reachable from the ZAP container, such as:

- a staging URL
- a preview environment
- a service started in the same compose project

## Copy The Workflow

Start from [`examples/github-actions/zap-security-gate.yml`](../../examples/github-actions/zap-security-gate.yml).

Before copying it into another repository, run the helper behavior tests:

```bash
python3 -m pip install PyYAML==6.0.3
python3 -m unittest discover -s tests/python -p 'test_*.py'
```

These tests cover the gate helper behavior and execute both GitHub and GitLab
gate entrypoints. Use the Juice Shop workflow to check container integration in
your environment. Run the helper tests with Python 3.9+, Helm and Docker Compose
available; the Compose checks render configuration without starting containers.
PyYAML is a test-only dependency for checking rendered Helm resources.

If you are using this repository directly, copy it into `.github/workflows/zap-security-gate.yml`.

If you are calling the action from another repository, replace the local action reference with:

```yaml
uses: dtkmn/mcp-zap-server/.github/actions/zap-security-gate@<release-tag>
```

Replace the placeholder with an existing published release tag.

## Baseline Strategy

The action expects an optional baseline file that contains either:

- the JSON returned by `zap_findings_snapshot`, including legacy exports
- the normalized `current-findings.json` contract emitted by a previous helper run

Recommended pattern:

1. keep an accepted baseline file in your repo, such as `.zap/baselines/main-findings.json`
2. run the gate on pull requests or release branches
3. check scanner comparability in `findings-diff.json`, then inspect comparable
   net-new findings in `findings-diff.txt`
4. update the baseline only after an intentionally accepted security state change

The provenance-aware contracts are included in released `v0.16.0`.
Upgrade the action and server together; see the
[release and upgrade guide](../releases/RELEASE_NOTES_0.16.0.md). Snapshot v3
records the running ZAP version and installed add-on inventory. Comparability is
`matching` only when
both exports contain the same measured engine version and complete add-on ID,
version, and status inventory. Old v1/v2 baselines remain readable, but their
provenance is `unknown`.

In `baseline-mode: seed`, a missing or incomparable baseline stays non-blocking
and the action emits current evidence for review. In `baseline-mode: enforce`
with `fail-on-new-findings: "true"`, missing, `different`, or `unknown`
comparison evidence fails the run, even when no added observations appear.
Incomparable diffs expose informational added/removed observations with `null`
new/resolved counts; they do not call a disappearing alert resolved.

If the scanner changed, review a fresh snapshot in seed mode before deliberately
accepting a replacement baseline. The action does not overwrite the approved
baseline. A matching scanner does not prove unchanged scan coverage or that an
application defect was fixed; review the target evidence too. See
[CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md) for contract migration.

## Seeded API Requests

Spiders do not invent JSON `POST` payloads for you. If the surface that matters is an API endpoint, provide a seed request file so the helper drives representative traffic through the ZAP proxy before snapshot/report generation.

Example seed file:

```json
{
  "requests": [
    {
      "name": "valid-bid-request",
      "method": "POST",
      "url": "http://app:8080/bid-request",
      "headers": {
        "Content-Type": "application/json"
      },
      "body": {
        "id": "ci-seed-bid-1",
        "site": {
          "id": "site-1",
          "domain": "example.com"
        },
        "device": {
          "ip": "192.0.2.10",
          "ua": "ci-zap-seed",
          "lmt": 0
        }
      },
      "expectedStatus": [200]
    }
  ]
}
```

Wire it into the action:

```yaml
with:
  target-url: http://app:8080/bid-request
  seed-requests-file: .zap/seed-requests/bid-request.json
```

The helper writes `seed-requests-results.json` without echoing request headers or bodies. If any seed request returns an unexpected status or cannot be sent through the ZAP proxy, the run fails before claiming scan coverage.

## Suppression Strategy

If you need temporary, reviewable exceptions without mutating the stored baseline, supply `suppressions-file`.

That file uses the `ci_gate_suppressions/v1` contract documented in [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md). Suppressions are applied to normalized baseline/current finding sets before diff calculation, while the raw current finding snapshot remains intact for auditability.

## Optional Webhook Callback

You can send the gate result to an external system after the upload step:

```yaml
env:
  ZAP_WEBHOOK_URL: ${{ secrets.ZAP_WEBHOOK_URL }}

- name: Send webhook callback
  if: always() && env.ZAP_WEBHOOK_URL != ''
  uses: ./.github/actions/zap-webhook-callback
  with:
    webhook-url: ${{ env.ZAP_WEBHOOK_URL }}
    metadata-path: ${{ steps.zap_gate.outputs.metadata-path }}
    summary-path: ${{ steps.zap_gate.outputs.summary-path }}
    snapshot-path: ${{ steps.zap_gate.outputs.snapshot-path }}
    diff-path: ${{ steps.zap_gate.outputs.diff-path }}
    report-local-path: ${{ steps.zap_gate.outputs.report-local-path }}
    output-path: zap-artifacts/webhook-delivery.json
```

That callback helper is documented in [Webhook Callbacks](./WEBHOOK_CALLBACKS.md) and includes retry/backoff plus optional HMAC signing.

## Example Workflow

```yaml
name: ZAP Security Gate

on:
  workflow_dispatch:
    inputs:
      target_url:
        description: URL that the bundled ZAP container can reach
        required: true
        type: string

jobs:
  zap-security-gate:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v7

      - name: Run MCP ZAP security gate
        uses: ./.github/actions/zap-security-gate
        with:
          target-url: ${{ inputs.target_url }}
          mcp-server-image: ghcr.io/dtkmn/mcp-zap-server:<release-tag>
          baseline-file: .zap/baselines/main-findings.json
          baseline-mode: enforce
          seed-requests-file: .zap/seed-requests/main.json
          output-dir: zap-artifacts
          fail-on-new-findings: "true"
          max-new-findings: "0"

      - name: Upload ZAP gate artifacts
        if: always()
        uses: actions/upload-artifact@v7
        with:
          name: zap-security-gate
          path: zap-artifacts
```

## App-Under-Test In The Same Workflow

If your app only exists inside CI, add a compose override file in the caller repository and include the app service in `compose-services`.

Start from [`examples/github-actions/docker-compose.app-under-test.yml`](../../examples/github-actions/docker-compose.app-under-test.yml). It declares a minimal `app` service; replace the example image with the service image you want to scan.

Minimal workflow pattern:

```yaml
- name: Run MCP ZAP security gate
  uses: ./.github/actions/zap-security-gate
  with:
    target-url: http://app:80
    mcp-server-image: ghcr.io/dtkmn/mcp-zap-server:<release-tag>
    baseline-mode: seed
    run-active-scan: "false"
    fail-on-new-findings: "false"
    compose-override-file: examples/github-actions/docker-compose.app-under-test.yml
    compose-services: app zap mcp-server
```

Only add `seed-requests-file` after replacing the example app with a service
that actually serves the seeded endpoint. The bundled example seed file is an
API shape example, not a valid request for the default nginx app.

Replace `<release-tag>` with a pinned release tag or sha256 digest. The action
rejects the placeholder before it starts Docker so pilot failures stay obvious.

Use `baseline-mode: seed` for the first evidence run. Use
`baseline-mode: enforce` only after the baseline exists and has been reviewed.
When `fail-on-new-findings: "true"` is paired with `baseline-mode: enforce`,
the action fails if it cannot produce a comparable findings diff.

Example override file:

```yaml
services:
  app:
    image: ghcr.io/your-org/your-app-under-test:v1.2.3
    expose:
      - "80"
```

That keeps the app, ZAP, and the MCP server on the same compose network, so ZAP can reach `http://app:80`.

This repo's Juice Shop validation workflow uses the same pattern via [`.github/zap/docker-compose.juice-shop.yml`](../../.github/zap/docker-compose.juice-shop.yml).

<a id="what-juice-shop-proves"></a>

## Choosing A CI Target

Juice Shop is a good CI smoke target because it exercises a real web app with enough surface area to stress the artifact contracts.

Dynamic application content and crawl coverage can change findings between
runs, so Juice Shop is unsuitable for assuming a strict zero-drift result.
Use it to check:

- the GitHub Action wiring works
- the action can start a caller-owned app service on the same compose network
- `current-findings.json`, `findings-diff.json`, `artifact-manifest.json`, and copied report artifacts are emitted in CI

For `max-new-findings=0`, select a controlled target with stable content, seeds,
scanner inventory and scan settings, and review repeated runs before enforcing
the baseline.

## Guided Queue Execution in CI

All crawl and attack starts use guided Operation IDs backed by the protected queue, including a single ephemeral runner. Explicit failed, cancelled or uncertain start outcomes fail the gate rather than being treated as completed scans or automatically retried. In-memory storage is sufficient for process-local job history; use PostgreSQL when history must survive server restart.

## Tuning Inputs

Important action inputs:

- `target-url`: required scan target
- `seed-requests-file`: optional JSON file with HTTP requests replayed through the ZAP proxy before crawling; use this for API endpoints that a spider cannot discover
- `baseline-file`: optional findings snapshot JSON from a prior accepted run
- `baseline-mode`: `seed` allows first-run baseline review; `enforce` fails
  when `fail-on-new-findings=true` but no findings diff can be produced
- `run-active-scan`: set to `false` for a lighter crawl-only gate
- `max-new-findings`: threshold used when `fail-on-new-findings` is `true`
- `suppressions-file`: optional suppression contract file for temporary accepted exceptions
- `scan-policy`: optional ZAP active-scan policy name
- `compose-override-file`: caller-owned compose file for an app-under-test service
- `mcp-server-image`: when `start-stack=true`, set this to a pinned MCP server
  release tag or sha256 digest. Bare refs, malformed digests, and mutable channel
  tags such as `latest`, `dev`, and `main` are rejected.

## Outputs

The action exposes these outputs for follow-on workflow steps:

- `gate-passed`
- `baseline-used`
- `new-findings`
- `resolved-findings`
- `summary-path`
- `snapshot-path`
- `diff-path`
- `diff-json-path`
- `manifest-path`
- `report-path`
- `report-local-path`
- `metadata-path`

## Notes

- The helper action writes a Markdown summary to the GitHub Actions step summary.
- It generates ephemeral API keys when you do not supply `zap-api-key` or `mcp-api-key`.
- The default compose stack enables `ZAP_ALLOW_LOCALHOST=true` and `ZAP_ALLOW_PRIVATE_NETWORKS=true` because CI often scans services living on runner-local or compose-private networks.
- If your target blocks shared runner traffic or ZAP-style crawling, start with a cooperative staging target before turning the gate into a required PR check.

## Report Format Compatibility

The shared helper accepts `traditional-html-plus` and `traditional-json-plus` for its report-template option and maps them to guided HTML or JSON generation. Other template names are rejected before any scan starts. Arbitrary raw ZAP report-template selection is no longer exposed.
