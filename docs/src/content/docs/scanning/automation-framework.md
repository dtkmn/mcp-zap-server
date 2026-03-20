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
