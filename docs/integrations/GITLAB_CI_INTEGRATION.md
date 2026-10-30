# GitLab CI Integration

This guide shows the GitLab CI template path for the same MCP-backed ZAP security gate flow.

It reuses the shared MCP helper in [`.github/actions/zap-security-gate/mcp_zap_gate.py`](../../.github/actions/zap-security-gate/mcp_zap_gate.py), but packages the surrounding runner logic as GitLab-friendly examples under:

- [`examples/gitlab/zap-security-gate.gitlab-ci.yml`](../../examples/gitlab/zap-security-gate.gitlab-ci.yml)
- [`examples/gitlab/run-zap-security-gate.sh`](../../examples/gitlab/run-zap-security-gate.sh)
- [`examples/gitlab/docker-compose.gitlab-ci.yml`](../../examples/gitlab/docker-compose.gitlab-ci.yml)

The artifact contracts emitted by that helper are defined in [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).

<a id="current-status"></a>

## Runner Preparation

The GitLab files are example templates that reuse the shared MCP helper.
Validate runner permissions, service connectivity, secrets and artifact handling
in your own GitLab project before using them as a release gate.

## What The GitLab Template Does

The GitLab flow follows the same scan and findings sequence as the GitHub Actions template:

1. start ZAP and `mcp-zap-server`
2. optionally replay configured HTTP seed requests through the ZAP proxy
3. run `zap_crawl_start`
4. wait with `zap_crawl_status`
5. run `zap_attack_start` when enabled
6. wait with `zap_attack_status`
7. drain passive analysis with `zap_passive_scan_wait`
8. save findings summary and snapshot artifacts
9. compare the normalized snapshot against the baseline file when present
10. generate a report with `zap_report_generate`

The helper uses guided start, status and report tools. It detects snapshot support from `tools/list`; the current guided catalog provides it. Older guided servers without snapshots cannot produce an enforceable baseline comparison. The helper never falls back to retired direct-start tools.

Artifacts are written into `.zap-artifacts/` by default and uploaded as GitLab job artifacts.

The Compose stack keeps launch receipts in the MCP-only `mcp-launch-safety`
volume, outside the shared ZAP workspace and uploaded artifacts. Preserve that
volume when replacing the MCP container. Do not delete unresolved receipts to
unblock a scan; follow [launch recovery](../src/content/docs/operations/scan-queue-retry-policy.md).
The journal protects uncertain starts but does not make in-memory job history durable.

## Copy The Template

Start from [`examples/gitlab/zap-security-gate.gitlab-ci.yml`](../../examples/gitlab/zap-security-gate.gitlab-ci.yml).

If you use this repository directly in GitLab, copy that file into your project root as `.gitlab-ci.yml`.

If you only want the security gate job inside another GitLab pipeline, copy:

- `examples/gitlab/zap-security-gate.gitlab-ci.yml`
- `examples/gitlab/run-zap-security-gate.sh`
- `examples/gitlab/docker-compose.gitlab-ci.yml`
- `.github/actions/zap-security-gate/mcp_zap_gate.py`

## Minimal Example

```yaml
stages:
  - security

variables:
  DOCKER_HOST: tcp://docker:2375
  DOCKER_TLS_CERTDIR: ""
  MCP_SERVER_IMAGE: ghcr.io/dtkmn/mcp-zap-server:<release-tag>
  ZAP_TARGET_URL: https://staging.example.com
  ZAP_BASELINE_FILE: .zap/baselines/main-findings.json
  ZAP_BASELINE_MODE: seed
  # ZAP_SEED_REQUESTS_FILE: examples/github-actions/seed-requests.json
  ZAP_FAIL_ON_NEW_FINDINGS: "false"

zap_security_gate:
  stage: security
  image: docker:27-cli
  services:
    - name: docker:27-dind
      command: ["--tls=false"]
  before_script:
    - apk add --no-cache bash curl python3
  script:
    - bash examples/gitlab/run-zap-security-gate.sh
  artifacts:
    when: always
    paths:
      - .zap-artifacts/
```

## Required Variables

Important variables used by the wrapper script:

- `ZAP_TARGET_URL`: required target URL reachable from the ZAP container
- `MCP_SERVER_IMAGE`: required pinned release tag or sha256 digest for the MCP
  server image. Bare refs, malformed digests, mutable tags such as `latest`,
  `dev`, and `main`, and placeholder refs such as `<release-tag>` are rejected
  before Docker starts.
- `ZAP_BASELINE_FILE`: optional path to a prior `zap_findings_snapshot` JSON file
- `ZAP_BASELINE_MODE`: `seed` allows first-run baseline review; `enforce`
  fails when `ZAP_FAIL_ON_NEW_FINDINGS=true` but no comparable findings diff can
  be produced, including changed or unknown scanner provenance
- `ZAP_SEED_REQUESTS_FILE`: optional JSON file with HTTP requests replayed
  through the ZAP proxy before crawling; use this for API endpoints that a
  spider cannot discover
- `ZAP_PROXY_URL`: ZAP proxy URL used for seeded requests, default `http://docker:8090`
- `ZAP_SUPPRESSIONS_FILE`: optional path to a `ci_gate_suppressions/v1` JSON file
- `ZAP_FAIL_ON_NEW_FINDINGS`: `true` or `false`
- `ZAP_MAX_NEW_FINDINGS`: integer threshold for the diff gate
- `ZAP_RUN_ACTIVE_SCAN`: set to `false` for crawl-only mode
- `ZAP_SCAN_POLICY`: optional active-scan policy name
- `ZAP_OUTPUT_DIR`: artifact directory, default `.zap-artifacts`

## Optional Webhook Callback

Add an optional callback after the gate script:

```yaml
script:
  - bash examples/gitlab/run-zap-security-gate.sh
  - |
    if [ -n "${ZAP_WEBHOOK_URL:-}" ]; then
      bash examples/gitlab/send-zap-webhook.sh
    fi
```

Useful webhook variables:

- `ZAP_WEBHOOK_URL`
- `ZAP_WEBHOOK_SECRET`
- `ZAP_WEBHOOK_BEARER_TOKEN`
- `ZAP_WEBHOOK_HEADERS_JSON`
- `ZAP_WEBHOOK_MAX_ATTEMPTS`
- `ZAP_WEBHOOK_INITIAL_BACKOFF_SECONDS`
- `ZAP_WEBHOOK_MAX_BACKOFF_SECONDS`
- `ZAP_WEBHOOK_BACKOFF_MULTIPLIER`
- `ZAP_WEBHOOK_ALLOW_FAILURE`

The callback contract is documented in [Webhook Callbacks](./WEBHOOK_CALLBACKS.md).

## GitLab Runner Assumption

The example assumes a Docker executor with `docker:dind` available. That is why it uses:

```yaml
image: docker:27-cli
services:
  - name: docker:27-dind
    command: ["--tls=false"]
variables:
  DOCKER_HOST: tcp://docker:2375
  DOCKER_TLS_CERTDIR: ""
```

If your GitLab setup uses a shell runner instead, override `MCP_SERVER_URL` and the Docker execution pattern to match that environment.
If needed, also override `MCP_HEALTH_URL` so the wrapper script waits on the correct health endpoint before calling `/mcp`.

## App-Under-Test In The Same Pipeline

If your app only exists inside the GitLab pipeline, supply an override compose file and include the app service in `ZAP_COMPOSE_SERVICES`.

Example variables:

```yaml
variables:
  ZAP_TARGET_URL: http://app:8080
  ZAP_COMPOSE_OVERRIDE_FILE: .gitlab/zap/docker-compose.app-under-test.yml
  ZAP_COMPOSE_SERVICES: app zap mcp-server
```

Example override:

```yaml
services:
  app:
    image: registry.example.com/your-group/your-app:latest
    expose:
      - "8080"
```

That keeps the app, ZAP, and the MCP server in the same compose network so the scan target can be addressed as `http://app:8080`.

## Baseline And Artifacts

Recommended baseline pattern is the same as the GitHub template:

1. keep a reviewed baseline snapshot in the repo
2. compare every new run against it
3. check scanner comparability in `findings-diff.json`, then inspect comparable
   net-new findings in `findings-diff.txt`
4. update the baseline only after intentional acceptance

The provenance-aware contracts are included in released `v0.16.0`.
Upgrade the copied helper and server together; see the
[release and upgrade guide](../releases/RELEASE_NOTES_0.16.0.md). Snapshot v3 records
the running ZAP version and installed add-ons. The gate compares the measured
engine version and complete add-on ID, version, and status inventories.
Matching provenance allows the normal new/resolved counts. `different` or
`unknown` provenance produces `null` comparison counts and informational
added/removed observations instead; removed observations are not labelled
resolved.

Legacy v1/v2 baselines remain readable with unknown provenance. In `seed` mode,
review the current snapshot before accepting a v3 replacement. With
`ZAP_BASELINE_MODE=enforce` and `ZAP_FAIL_ON_NEW_FINDINGS=true`, incomparable
evidence fails the gate. The helper never overwrites the approved baseline.
Matching provenance alone does not establish equal scan coverage or application
remediation. See [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md) for
consumer migration and report linkage.

If you need temporary accepted exceptions without changing the stored baseline, provide `ZAP_SUPPRESSIONS_FILE` and use the suppression contract documented in [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).

Default artifacts include:

- `findings-summary.md`
- `current-findings.json`
- `findings-diff.txt` and `findings-diff.json` when a baseline was used
- `gate-metadata.json`
- `artifact-manifest.json`
- `webhook-delivery.json` when the optional callback wrapper runs
- copied report files when a generated report can be mapped back to the runner workspace

## Notes

- The GitLab wrapper uses guided queued scans and polls their Operation IDs. Failed, cancelled or uncertain outcomes fail the gate without another automatic start.
- Use PostgreSQL when job history must survive a server restart; storage does not change the guided execution path.
- The compose example enables `ZAP_ALLOW_LOCALHOST=true` and `ZAP_ALLOW_PRIVATE_NETWORKS=true` because CI targets often live on private runner-local networks.

## Report Format Compatibility

The shared helper accepts `traditional-html-plus` and `traditional-json-plus` for its report-template option and maps them to guided HTML or JSON generation. Other template names are rejected before any scan starts. Arbitrary raw ZAP report-template selection is no longer exposed.
