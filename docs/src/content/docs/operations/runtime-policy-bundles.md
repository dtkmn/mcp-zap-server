---
title: "Runtime Policy Bundles"
editUrl: false
description: "Configure dry-run and enforcement modes for Policy Bundle v1 guardrails."
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
        "description": "Permit target-bearing guided CI calls for sandbox hosts during staffed hours.",
        "decision": "allow",
        "reason": "Sandbox CI is approved during staffed rollout hours.",
        "match": {
          "tools": [
            "zap_target_import",
            "zap_crawl_start",
            "zap_attack_start",
            "zap_findings_summary",
            "zap_findings_details",
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
      },
      {
        "id": "allow-guided-ci-followups-staffed-hours",
        "description": "Permit guided lifecycle checks and workspace-confined report readback that do not carry a target parameter.",
        "decision": "allow",
        "reason": "Guided follow-up calls are approved during staffed hours; report readback is bounded by report/workspace artifact confinement, not host matching.",
        "match": {
          "tools": [
            "zap_crawl_status",
            "zap_attack_status",
            "zap_passive_scan_wait",
            "zap_passive_scan_status",
            "zap_report_read",
            "zap_report_read_chunk"
          ],
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

Host-free requests do not match rules that require `hosts`.
For guided lifecycle calls such as operation status, passive wait/status, or
report readback, use a separate host-free rule unless the runtime can recover a
target from signed operation/report state. A host-free report preview or chunk rule is
not sandbox-host scoped; it authorizes report readback by path, with safety
coming from tool scope, time windows, and `ReportService` report/workspace
artifact confinement.

`zap_report_read_chunk`, introduced in `v0.15.0`, uses `zap:report:read`, like the
existing preview. The examples include it for servers that expose paged reads;
check the running server's tool catalog before calling it.

The repository ships example bundles at:

- `examples/policy-bundles/ci-guided-guardrails.json`
- `examples/policy-bundles/guided-readonly-triage.json`

Use those as starting points, not as production policy. Your actual bundle must name your approved hosts, tools, owners, and rollout windows.

## Previewing Decisions

The former expert policy-preview tool is retired. Runtime policy configuration still supports `dry-run`, which records decisions for actual tool calls without blocking them. It is not a simulation: only perform actions that are authorized to execute. Rules must name tools in the current guided catalog; granting an old name does not restore it.

## Rollout Guidance

Start with policy disabled while you confirm authentication, scopes, URL validation, and scan workflows.

Configure the bundle in `dry-run` mode for an authorized workflow:

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
