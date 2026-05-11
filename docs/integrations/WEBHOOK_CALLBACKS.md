# Webhook Callbacks

This guide covers the shared webhook callback layer used by the GitHub Actions and GitLab CI scan templates.

The callback helper is implemented in [`.github/actions/zap-webhook-callback/send_zap_webhook.py`](../../.github/actions/zap-webhook-callback/send_zap_webhook.py), with:

- a reusable GitHub composite action in [`.github/actions/zap-webhook-callback/action.yml`](../../.github/actions/zap-webhook-callback/action.yml)
- a GitLab wrapper in [`examples/gitlab/send-zap-webhook.sh`](../../examples/gitlab/send-zap-webhook.sh)

## What It Sends

The webhook posts one JSON payload after the CI security gate finishes.

High-level payload shape:

```json
{
  "event": "zap_security_gate.completed",
  "sentAt": "2026-03-16T10:00:00+00:00",
  "status": "passed",
  "provider": "github",
  "gate": {
    "target_url": "https://staging.example.com",
    "baseline_used": true,
    "new_findings": 0,
    "resolved_findings": 1,
    "gate_passed": true
  },
  "artifacts": {
    "metadataPath": {
      "path": "/workspace/.zap-artifacts/gate-metadata.json",
      "exists": true
    },
    "diffPath": {
      "path": "/workspace/.zap-artifacts/findings-diff.txt",
      "exists": true
    }
  },
  "ci": {
    "provider": "github",
    "repository": "dtkmn/mcp-zap-server",
    "runId": "123456789"
  }
}
```

The exact `ci` object depends on whether the helper runs under GitHub Actions, GitLab CI, or a generic shell environment.

## Delivery Headers

The helper always sends:

- `Content-Type: application/json`
- `User-Agent: mcp-zap-server-webhook/1.0`
- `X-MCP-ZAP-Event`
- `X-MCP-ZAP-Delivery-Id`

Optional headers:

- `Authorization: Bearer ...` when a bearer token is configured
- `X-MCP-ZAP-Signature-Sha256` when a shared secret is configured
- any additional headers from the JSON header map

## Signing

If you provide a shared secret, the helper signs the exact JSON request body using HMAC SHA-256.

Header format:

```text
X-MCP-ZAP-Signature-Sha256: sha256=<hex-digest>
```

That gives downstream systems a simple integrity check without forcing a specific identity provider.

## Retry Contract

The helper retries on:

- network errors
- `408`
- `409`
- `425`
- `429`
- `500`
- `502`
- `503`
- `504`

Default retry settings:

- max attempts: `4`
- initial backoff: `2s`
- max backoff: `30s`
- multiplier: `2x`

If the receiver returns `Retry-After`, the helper respects it and folds it into the exponential backoff calculation.

## GitHub Actions Usage

Optional step after the gate action:

```yaml
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
    output-path: .zap-artifacts/webhook-delivery.json
```

Typical job-level environment:

```yaml
env:
  ZAP_WEBHOOK_URL: ${{ secrets.ZAP_WEBHOOK_URL }}
```

## GitLab CI Usage

Optional script line after the gate:

```yaml
script:
  - bash examples/gitlab/run-zap-security-gate.sh
  - |
    if [ -n "${ZAP_WEBHOOK_URL:-}" ]; then
      bash examples/gitlab/send-zap-webhook.sh
    fi
```

Useful variables:

- `ZAP_WEBHOOK_URL`
- `ZAP_WEBHOOK_SECRET`
- `ZAP_WEBHOOK_BEARER_TOKEN`
- `ZAP_WEBHOOK_HEADERS_JSON`
- `ZAP_WEBHOOK_MAX_ATTEMPTS`
- `ZAP_WEBHOOK_INITIAL_BACKOFF_SECONDS`
- `ZAP_WEBHOOK_MAX_BACKOFF_SECONDS`
- `ZAP_WEBHOOK_BACKOFF_MULTIPLIER`
- `ZAP_WEBHOOK_ALLOW_FAILURE`

## Delivery Record Artifact

When you set an output path, the helper writes a delivery record JSON file containing:

- whether delivery succeeded
- how many attempts were made
- the final HTTP status code when available
- a redacted webhook target
- per-attempt retry details

Recommended default:

- `.zap-artifacts/webhook-delivery.json`

## When To Use This

Use webhook callbacks when you want to:

- notify a deployment system after a gate passes
- fan out to Slack, Teams, or a custom event bridge
- trigger a downstream quality gate or audit sink
- capture scan gate status outside the CI provider itself

For teams that only need in-platform artifacts, GitHub Actions uploads or GitLab job artifacts may already be enough. The webhook helper is the portable handoff layer when you need a system outside CI to react.
