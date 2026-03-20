---
title: "Findings and Reports"
editUrl: false
description: "Choose the right guided or expert findings and report tools for triage, evidence, baselines, and artifacts."
---
MCP ZAP Server supports multiple findings and report layers. The right tool depends on whether you want a fast summary, grouped detail, raw evidence, or a stable artifact.

## Guided Surface

Guided findings and report tools:

- `zap_findings_summary`
- `zap_findings_details`
- `zap_report_generate`

Use these when:

- you are on the default `guided` surface
- you want quick triage with less tool selection overhead
- you do not need raw per-instance evidence, snapshots, diffs, or artifact reads

## Expert Surface

Expert results tools require `MCP_SERVER_TOOLS_SURFACE=expert`.

Expert tools:

- `zap_get_findings_summary`
- `zap_alert_details`
- `zap_alert_instances`
- `zap_findings_snapshot`
- `zap_findings_diff`
- `zap_view_templates`
- `zap_generate_report`
- `zap_report_read`

## Summary Layer

Use:

- `zap_findings_summary` on guided
- `zap_get_findings_summary` on expert

This is the first-pass risk picture. It is the cheapest place to start.

## Grouped Details

Use:

- `zap_findings_details` on guided for grouped detail or bounded instances
- `zap_alert_details` on expert for grouped metadata

Choose grouped detail when you need description, remediation guidance, CWE, WASC, or alert-family context before drilling into raw evidence.

## Raw Instances

Use:

- `zap_findings_details` with `includeInstances=true` on guided
- `zap_alert_instances` on expert

Choose raw instances when you need:

- concrete URLs
- params
- evidence
- attack samples
- message IDs

## Snapshot And Diff

Expert-only tools:

- `zap_findings_snapshot`
- `zap_findings_diff`

Use them when:

- you want a stable baseline after a known-good scan
- you need before/after comparison for CI or release gates
- you want to focus on net-new findings instead of total backlog size

## Report Artifacts

Guided:

- `zap_report_generate`

Expert:

- `zap_view_templates`
- `zap_generate_report`
- `zap_report_read`

Guided report generation uses sane defaults and returns the artifact path.

Expert reporting lets you choose the template and then read the generated artifact back through MCP without manual filesystem access.

## Typical Flow

```text
1. Finish crawl or attack work
2. Run zap_passive_scan_wait
3. Read findings summary
4. Drill into grouped details
5. Expand to raw instances only when you need evidence
6. Generate a report artifact
7. In expert mode, snapshot or diff findings for later comparison
```
