---
title: "Runtime Policy Bundles"
editUrl: false
description: "Dry-run and enforce Policy Bundle v1 guardrails for MCP tool execution."
---
Runtime policy is the 0.7.0 control plane for deciding which MCP tool calls are allowed before the server executes them.

Use it when a client should be able to discover and request security actions, but should not be trusted to choose any tool, target, or timing on its own.

## What It Controls

Policy Bundle v1 evaluates:

- the exact MCP tool name, such as `zap_attack_start` or `zap_report_generate`
- the target host or URL when the tool exposes `target`, `targetUrl`, `baseUrl`, or `endpointUrl`
- the evaluation time in the bundle timezone

It does not replace authentication, per-tool scopes, ZAP URL validation, or network egress controls. Treat policy bundles as the runtime approval layer, not as the only boundary.

## Configuration

Relevant settings:

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `MCP_POLICY_MODE` | `off` | Runtime behavior. Use `off`, `dry-run`, or `enforce`. |
| `MCP_POLICY_BUNDLE` | empty | Inline Policy Bundle v1 JSON. Useful for tests and small local pilots. |
| `MCP_POLICY_BUNDLE_FILE` | empty | Absolute or container-local path to a Policy Bundle v1 JSON file. Prefer this for operations. |

If both `MCP_POLICY_BUNDLE` and `MCP_POLICY_BUNDLE_FILE` are set, the inline bundle is evaluated first. Do not set both in production unless you deliberately want the inline value to win.

Application config equivalent:

```yaml
mcp:
  server:
    policy:
      mode: dry-run
      bundle-file: /etc/mcp-zap/policy-bundle.json
```

## Modes

`off` disables runtime policy checks.

`dry-run` evaluates the configured policy and emits `policy_decision` audit events, but it does not block tool execution. This is the rollout mode. Use it until you have real evidence that intended tool calls are allowed and unsafe ones are denied.

`enforce` blocks denied tool execution. If you enable `enforce` without a configured bundle, the active policy provider abstains and tool calls are denied with `no_active_policy_provider_configured`. That is not a bug. It is fail-closed behavior.

## Policy Bundle Shape

A bundle is a JSON document with this shape:

```json
{
  "apiVersion": "mcp.zap.policy/v1",
  "kind": "PolicyBundle",
  "metadata": {
    "name": "ci-guided-guardrails",
    "description": "Allow guided CI scans on sandbox hosts during staffed hours.",
    "owner": "security-platform",
    "labels": {
      "channel": "ci",
      "surface": "guided"
    }
  },
  "spec": {
    "defaultDecision": "deny",
    "evaluationOrder": "first-match",
    "timezone": "Australia/Sydney",
    "rules": [
      {
        "id": "allow-guided-ci-sandbox-hours",
        "description": "Permit the approved guided scan path for sandbox hosts.",
        "decision": "allow",
        "reason": "Sandbox CI is approved during staffed rollout hours.",
        "match": {
          "tools": [
            "zap_target_import",
            "zap_crawl_start",
            "zap_attack_start",
            "zap_findings_summary",
            "zap_report_generate"
          ],
          "hosts": ["*.sandbox.example.com"],
          "timeWindows": [
            {
              "days": ["mon", "tue", "wed", "thu", "fri"],
              "start": "08:00",
              "end": "18:00"
            }
          ]
        }
      }
    ]
  }
}
```

Rules are evaluated in `first-match` order. Put explicit deny rules before broad allow rules. Keep `defaultDecision` set to `deny` unless you have a narrow, documented reason to do otherwise.

The repository ships example bundles at:

- `examples/policy-bundles/ci-guided-guardrails.json`
- `examples/policy-bundles/expert-readonly-triage.json`

Use those as starting points, not as production policy. Your actual bundle must name your approved hosts, tools, owners, and rollout windows.

## Dry-run Tool

The expert surface exposes `zap_policy_dry_run` for previewing a bundle against one tool call before you publish it.

Required setup:

- `MCP_SERVER_TOOLS_SURFACE=expert`
- caller has `zap:policy:dry-run` scope when authorization is enforced

Example:

```json
{
  "tool": "zap_policy_dry_run",
  "arguments": {
    "policyBundle": "{\"apiVersion\":\"mcp.zap.policy/v1\",\"kind\":\"PolicyBundle\",\"metadata\":{\"name\":\"ci-guided-guardrails\",\"description\":\"CI sandbox guardrails\",\"owner\":\"security-platform\"},\"spec\":{\"defaultDecision\":\"deny\",\"evaluationOrder\":\"first-match\",\"timezone\":\"Australia/Sydney\",\"rules\":[{\"id\":\"allow-sandbox-attack\",\"description\":\"Allow sandbox attack\",\"decision\":\"allow\",\"reason\":\"Sandbox target is approved.\",\"match\":{\"tools\":[\"zap_attack_start\"],\"hosts\":[\"*.sandbox.example.com\"]}}]}}}",
    "toolName": "zap_attack_start",
    "target": "https://api.sandbox.example.com/orders",
    "evaluatedAt": "2026-06-02T01:00:00Z"
  }
}
```

The response includes:

- `contractVersion`
- `validation.valid` and validation errors
- `decision.result`, `decision.source`, `decision.reason`, and `matchedRuleId` when a rule matched
- normalized request details, including host and bundle-local time
- a rule trace for explainability

If the dry run says `invalid`, do not deploy the bundle. Fix unknown tool names, malformed targets, bad timestamps, duplicate rule IDs, or invalid time windows first.

## Rollout Guidance

Start with policy disabled while you confirm authentication, scopes, URL validation, and scan workflows.

Then run local or CI previews with `zap_policy_dry_run` for every tool and target pattern the client needs.

Next deploy the bundle with:

```bash
MCP_POLICY_MODE=dry-run
MCP_POLICY_BUNDLE_FILE=/etc/mcp-zap/policy-bundle.json
```

Watch audit events and Prometheus output for:

- `policy_decision` audit events
- `outcome=dry_run_allow`
- `outcome=dry_run_deny`
- unexpected `validationValid=false`
- expected denies that still proceed because dry-run is non-blocking

Only move to enforcement after the dry-run evidence is boring:

```bash
MCP_POLICY_MODE=enforce
MCP_POLICY_BUNDLE_FILE=/etc/mcp-zap/policy-bundle.json
```

After enforcement, denied calls should be explainable by `reason`, `matchedRuleId`, and `correlationId`. If operators cannot explain a denial from those fields, the policy bundle is not production-ready.

## Failure Patterns

Common mistakes:

- enabling `enforce` without `MCP_POLICY_BUNDLE` or `MCP_POLICY_BUNDLE_FILE`
- relying on broad allow rules with `defaultDecision=allow`
- forgetting `mcp:tools:list` when clients need discovery
- using ZAP scan-policy names where MCP tool names are required
- omitting read/report tools from a bundle that allows scan execution
- testing only happy-path allows and never proving that production attack paths deny

The blunt version: a policy bundle you have not dry-run against realistic denied requests is theater. Prove both sides before calling it a release control.
