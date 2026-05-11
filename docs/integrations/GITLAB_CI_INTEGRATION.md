# GitLab CI Integration

This guide shows the GitLab CI template path for the same MCP-backed ZAP security gate flow.

It reuses the shared MCP helper in [`.github/actions/zap-security-gate/mcp_zap_gate.py`](../../.github/actions/zap-security-gate/mcp_zap_gate.py), but packages the surrounding runner logic as GitLab-friendly examples under:

- [`examples/gitlab/zap-security-gate.gitlab-ci.yml`](../../examples/gitlab/zap-security-gate.gitlab-ci.yml)
- [`examples/gitlab/run-zap-security-gate.sh`](../../examples/gitlab/run-zap-security-gate.sh)
- [`examples/gitlab/docker-compose.gitlab-ci.yml`](../../examples/gitlab/docker-compose.gitlab-ci.yml)

The artifact contracts emitted by that helper are defined in [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).

## Current Status

As of March 28, 2026, GitLab is not part of the actively validated near-term acceptance bar for the current 2-3 engineer roadmap.

- GitHub Actions is the primary CI target under active hosted validation.
- The GitLab files in this repo are kept as example templates and a customer-triggered starting point.
- Reopen full GitLab parity work only when a design partner or paid pilot is blocked on GitLab.

If you need GitLab today, treat this guide as a starting template that you validate in your own GitLab project instead of assuming first-class maintained parity.

## What The GitLab Template Does

The GitLab flow follows the same scan and findings sequence as the GitHub Actions template:

1. start ZAP and `mcp-zap-server`
2. run `zap_spider_start`
3. wait with `zap_spider_status`
4. run `zap_active_scan_start` when enabled
5. wait with `zap_active_scan_status`
6. drain passive analysis with `zap_passive_scan_wait`
7. save findings summary and snapshot artifacts
8. run `zap_findings_diff` when a baseline file is present
9. generate a report with `zap_generate_report`

The bundled GitLab compose stack forces `mcp-zap-server` into the `expert` tool surface so snapshot and diff contracts are available during CI. If you override the MCP endpoint to use an external server, the artifact set still depends on that server's configured tool surface.

Artifacts are written into `.zap-artifacts/` by default and uploaded as GitLab job artifacts.

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
- `MCP_SERVER_IMAGE`: required immutable release tag or digest for the MCP server
  image. Bare refs, mutable tags such as `latest`, `dev`, and `main`, and
  placeholder refs such as `<release-tag>` are rejected before Docker starts.
- `ZAP_BASELINE_FILE`: optional path to a prior `zap_findings_snapshot` JSON file
- `ZAP_BASELINE_MODE`: `seed` allows first-run baseline review; `enforce`
  fails when `ZAP_FAIL_ON_NEW_FINDINGS=true` but no findings diff can be produced
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
3. inspect `findings-diff.txt` for net-new findings
4. update the baseline only after intentional acceptance

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

- The GitLab wrapper intentionally uses direct scan tools because they are the simplest fit for a single CI runner.
- For long-lived shared environments, the queue tools remain the recommended production path.
- The compose example enables `ZAP_ALLOW_LOCALHOST=true` and `ZAP_ALLOW_PRIVATE_NETWORKS=true` because CI targets often live on private runner-local networks.
