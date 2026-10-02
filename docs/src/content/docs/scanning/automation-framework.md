---
title: "Automation Framework"
editUrl: false
description: "Run ZAP Automation Framework plans through the expert MCP surface."
---
This page requires the `expert` surface. Set `MCP_SERVER_TOOLS_SURFACE=expert` if you want these tools exposed.

MCP ZAP Server exposes ZAP's native Automation Framework through MCP.

This is not a custom workflow engine in this repo. It is a bounded wrapper around the ZAP Automation Framework add-on so agents can run repeatable ZAP plans, poll progress, and inspect generated artifacts.

## Tools

- `zap_automation_plan_run`
- `zap_automation_plan_status`
- `zap_automation_plan_artifacts`

## Requirements

The automation tools require:

- the ZAP `automation` add-on
- a filesystem workspace that both the MCP runtime and ZAP can read and write

Relevant settings:

- `ZAP_AUTOMATION_LOCAL_DIRECTORY`
- `ZAP_AUTOMATION_ZAP_DIRECTORY`

## How Plan Input Works

`zap_automation_plan_run` accepts exactly one of:

- `planPath` for an existing plan file inside the configured automation workspace
- `planYaml` for inline YAML content

In both cases, the server writes a normalized per-run copy of the plan before calling ZAP.

That gives you:

- a stable run-specific plan file path
- a stable run-specific artifacts directory
- bounded report output handling for MCP clients

The returned `Plan File` path is the one to reuse with `zap_automation_plan_artifacts`.

### YAML Limits

The limits below apply to the current development checkout and are not included in released v0.13.0.

Inline plans and workspace plan files must fit within 1 MiB of UTF-8 input and contain a single YAML document with a mapping at the root. Plans must define at least one `env.contexts` entry. The normalized per-run copy must satisfy the same limits, so an expanded plan that exceeds them is rejected before the plan is written or ZAP is called.

The server checks the composed YAML graph before constructing or normalizing the plan. The limits are:

- 10,000 expanded node occurrences, including mapping keys and values
- expanded depth of 50, including paths through aliases
- 1,048,576 expanded scalar characters, including mapping keys and repeated scalar aliases
- 50 aliases to collections and parser nesting depth of 50

Bounded acyclic aliases, merge mappings, and ordinary typed scalars are supported. Cyclic aliases and collection-valued mapping keys are rejected. Mapping keys must be scalars; their parsed scalar values are converted to strings during normalization. YAML object tags that construct arbitrary Java classes are unsupported.

These limits also apply when `zap_automation_plan_artifacts` reads a plan to find its declared reports. Invalid or oversized plan input is rejected before ZAP starts the plan.

## Example Inline Plan

```json
{
  "tool": "zap_automation_plan_run",
  "arguments": {
    "planFileName": "nightly-plan.yaml",
    "planYaml": "env:\n  contexts:\n    - name: local-target\n      urls:\n        - http://example.com/\njobs:\n  - type: requestor\n    requests:\n      - url: http://example.com/\n        method: GET\n        responseCode: 200\n  - type: passiveScan-wait\n    parameters:\n      maxDuration: 2\n  - type: report\n    parameters:\n      template: traditional-json-plus\n      reportFile: nightly-report\n      reportTitle: Nightly Automation Report\n      displayReport: false\n    sites:\n      - example.com"
  }
}
```

## Status And Artifacts

Use the response from `zap_automation_plan_run` like this:

1. capture `Plan ID`
2. poll `zap_automation_plan_status`
3. capture `Plan File`
4. call `zap_automation_plan_artifacts` with that `Plan File`

If you need a deeper read of a generated report file, use `zap_report_read`.

## Relationship To Queue Mode

Automation Framework plans and queue-managed scans solve different problems.

Use queue mode when you need:

- durable `ScanJob` state
- retries and dead-letter handling
- HA-safe worker claims
- centralized job lifecycle operations

Use Automation Framework plans when you need:

- a repeatable ZAP-native multi-step workflow
- one plan file that captures requestor, spider, passive-wait, report, and related jobs
- lightweight MCP control over an existing Automation Framework plan
