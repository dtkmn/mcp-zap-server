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
- `zap_report_read`

Use these when:

- you are on the default `guided` surface
- you want quick triage with less tool selection overhead
- you want report generation and readback without selecting a raw ZAP template
- you do not need expert snapshots or diffs

## Expert Surface

Expert adds the following tools to the guided surface and requires `MCP_SERVER_TOOLS_SURFACE=expert`.

Expert tools:

- `zap_get_findings_summary`
- `zap_alert_details`
- `zap_alert_instances`
- `zap_findings_snapshot`
- `zap_findings_diff`
- `zap_view_templates`
- `zap_generate_report`

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

Raw records retain ZAP's `nodeName`, HTTP `method`, and alert `tags` when
available. `nodeName` identifies a full structural location, including its
origin and path; it is not just the final path segment.

The `SYSTEMIC` tag marks findings that are typically site-wide. Their counts
describe recorded examples, not the total number of affected endpoints. ZAP
may limit additional examples; the tag alone does not prove a limit was reached.

## Snapshot And Diff

Expert-only tools:

- `zap_findings_snapshot`
- `zap_findings_diff`

Use them when:

- you want a stable baseline after a known-good scan
- you need before/after comparison for CI or release gates
- you want to focus on net-new findings instead of total backlog size

New server snapshots use version 2. Comparisons prefer the full `nodeName`
plus HTTP method, falling back to the raw URL when `nodeName` is unavailable.
Rule, risk, confidence, and parameter differences still distinguish findings.
This avoids treating changing parameter values as new locations when ZAP maps
them to the same structural node. Keep the same ZAP context and site-structure
configuration when comparing scans. See [ZAP's alert de-duplication guidance](https://www.zaproxy.org/blog/2025-09-30-alert-de-duplication/).

Snapshots preserve individual example records; diffs count unique finding
identities. Several exported records can therefore count as one finding.

Version 1 baselines remain supported: comparisons use the previous URL-based
algorithm and show an explicit legacy-comparison notice. Export a new baseline
after review to use version 2 identities. Update the bundled CI gate alongside
the server; it handles both old and new server snapshots. Older external
snapshot readers may need an update before consuming version 2 exports.

## Report Artifacts

Available on both surfaces:

- `zap_report_generate`
- `zap_report_read`

Additional expert controls:

- `zap_view_templates`
- `zap_generate_report`

Guided report generation accepts `baseUrl`, `format` (`html` or `json`), and `theme`, and returns the artifact path. Pass that path as `reportPath` to `zap_report_read` to read the artifact through MCP on either surface.

Expert reporting additionally lets you choose a ZAP report template.

## Client Spider Findings

In `v0.13.0`, [Client Spider](../client-spider/) uses these same findings and report tools. Wait for crawl completion and `zap_passive_scan_wait`, then filter the summary, details, and report by the target's `baseUrl`.

These tools read the shared ZAP session, not an exclusive set of findings for one crawl. A `baseUrl` filter narrows the target but does not isolate a scan ID. Other crawls, authentication checks, and active scans against that target can contribute alerts. Recorded alert instances are not necessarily distinct confirmed vulnerabilities.

There is no dedicated Client Spider results-list or Client Map export tool in the server yet. `zap_ajax_spider_results` belongs to AJAX Spider. Use separate ZAP sessions or instances when a comparison requires results attributable to one crawler.

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
