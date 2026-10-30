---
title: "Scan Policy Selection"
editUrl: false
description: "Inspect configured ZAP active-scan policies and select one for guided active testing."
---
Use `zap_scan_policies_list` and `zap_scan_policy_view` to inspect ZAP's current active-scan policies before choosing one. Both are read-only and require `zap:scan:policy:read`; they do not start a scan or change rules. Starting an attack separately requires `zap:scan:attack:run` and authorization to test the target.

First, list the configured policy names:

```json
{
  "tool": "zap_scan_policies_list",
  "arguments": {}
}
```

Inspect a returned name. Replace `approved-policy` in these examples with a name actually returned by ZAP:

```json
{
  "tool": "zap_scan_policy_view",
  "arguments": {
    "scanPolicyName": "approved-policy",
    "limit": "25"
  }
}
```

The view includes whole-policy category and rule counts, enabled states, attack strengths, alert thresholds and an override count. Detailed rule rows support:

- `scanPolicyName`: required; matching is case-insensitive.
- `ruleFilter`: optional exact rule ID or category ID, or a case-insensitive rule-name substring.
- `limit`: optional whole-number string from `1` to `100`; defaults to `25`.

The response states how many matching rules are shown. Narrow the filter or increase the limit to inspect more. Filtering and limits affect only the inspection output; they do not change which rules a scan uses.

When active testing is authorized, pass the selected policy name to `zap_attack_start`:

```json
{
  "tool": "zap_attack_start",
  "arguments": {
    "targetUrl": "https://app.example.com/",
    "recurse": "true",
    "policy": "approved-policy"
  }
}
```

Omit `policy` from `zap_attack_start` to use the configured default. A policy read grant alone does not permit an attack, and no policy-editing tool is exposed through MCP. Prepare or change approved policies through operator-managed ZAP configuration.

Policies are shared engine-level configuration. Inspection reads their current state; it does not reserve or freeze that state for a later scan.

Choose a policy appropriate to the authorized target and permitted test intensity. Policy selection does not grant active-scan authorization or expand target scope. Track and stop the scan with the Operation ID returned by `zap_attack_start`.

See [Scan Execution](../scan-execution-modes/) and [Upgrading to Guided Tools](../../getting-started/tool-migration/).
