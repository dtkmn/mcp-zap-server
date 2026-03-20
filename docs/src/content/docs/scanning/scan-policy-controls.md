---
title: "Scan Policy Controls"
editUrl: false
description: "Inspect and tune ZAP active-scan policies through the expert MCP surface."
---
This page requires the `expert` surface. Set `MCP_SERVER_TOOLS_SURFACE=expert` if you want these tools exposed.

MCP ZAP Server exposes a bounded set of ZAP-native active-scan policy controls so agents no longer have to guess at opaque policy names or scanner rule IDs.

## Tools

- `zap_scan_policies_list`
- `zap_scan_policy_view`
- `zap_scan_policy_rule_set`

Use them before setting the `policy` parameter on:

- `zap_attack_start`
- `zap_active_scan_start`
- `zap_active_scan_as_user`
- `zap_queue_active_scan`
- `zap_queue_active_scan_as_user`

## Typical Workflow

1. call `zap_scan_policies_list`
2. pick an exact policy name
3. call `zap_scan_policy_view`
4. capture exact numeric rule IDs from the result
5. call `zap_scan_policy_rule_set` if you need to enable, disable, or tune specific rules
6. start the scan with that policy name

## Safe Boundaries

The surface is intentionally bounded:

- policy changes require an exact policy name from `zap_scan_policies_list`
- rule changes require exact numeric scanner rule IDs
- one mutation call can change at most 50 rule IDs
- `attackStrength` must be one of `DEFAULT`, `LOW`, `MEDIUM`, `HIGH`, or `INSANE`
- `alertThreshold` must be one of `DEFAULT`, `LOW`, `MEDIUM`, `HIGH`, or `OFF`
- the tools do not create, import, or delete policies

## What `zap_scan_policy_view` Shows

`zap_scan_policy_view` returns:

- the chosen policy name
- category-level defaults
- scanner rule IDs and names
- each rule's enabled state
- attack-strength and alert-threshold overrides
- current non-default overrides

Use the optional `ruleFilter` when you already know a rule ID or part of the rule name.
