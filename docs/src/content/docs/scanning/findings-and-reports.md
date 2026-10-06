---
title: "Findings and Reports"
editUrl: false
description: "Choose the right guided or expert findings and report tools for triage, evidence, baselines, and artifacts."
---
MCP ZAP Server supports multiple findings and report layers. The right tool depends on whether you want a fast summary, grouped detail, raw evidence, or a stable artifact.

## Required Findings Scope

Findings reads require a nonblank `baseUrl` for the scanned target and visible
scan-history evidence for that target. This applies to guided summary/details
and expert summary, details, instances, snapshots and diffs. Supply the target
you scanned, such as `http://juice-shop:3000`; other supported filters narrow
that target's findings. Missing `baseUrl` or visible evidence is rejected.

These tools do not provide a global read of the shared ZAP session. A target
scope also does not isolate results to one scan ID; see
[Client Spider findings](#client-spider-findings) below.

## Guided Surface

Guided findings and report tools:

- `zap_findings_summary`
- `zap_findings_details`
- `zap_report_generate`
- `zap_report_read`
- `zap_report_read_chunk` (unreleased)

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

The scoped-report protection and paged readback below are **unreleased** and are
not included in `v0.14.0`. Check `tools/list` on your running server before using
`zap_report_read_chunk`.

Available on both surfaces:

- `zap_report_generate`
- `zap_report_read`
- `zap_report_read_chunk` (unreleased)

Additional expert controls:

- `zap_view_templates`
- `zap_generate_report`

Guided report generation accepts `baseUrl`, `format` (`html` or `json`), and
`theme`, and returns the artifact path. Supply `baseUrl` for an absolute HTTP(S)
origin or path prefix. A scoped report includes that target's alert evidence and
omits shared-session insights, statistics, sequences and automation diagnostics.
JSON retains engine identity and generation timestamps. Scoped HTML uses the
standard template's default appearance; light/dark themes apply to full-session
HTML. A path filter remains a prefix filter, not a scan-ID boundary.

Omitting `baseUrl` explicitly requests the full ZAP session. Expert reports with
a nonblank `sites` filter support the reviewed traditional JSON, HTML and
Markdown templates; unsupported scoped templates are rejected. Native Automation
Framework report jobs use ZAP's own templates and are not sanitized by this MCP
generation path. Use separate ZAP sessions or engines when workloads require
client isolation.

### Preview Or Complete Retrieval

Pass the generated path as `reportPath` to `zap_report_read` for a preview. Its
default is 20,000 UTF-16 characters and its maximum is 200,000; `Truncated: yes`
means the complete artifact has not been retrieved.

For complete retrieval, call `zap_report_read_chunk`:

```json
{
  "reportPath": "<path returned by zap_report_generate>",
  "offset": 0,
  "maxChars": 20000
}
```

The JSON result contains `content`, `offset`, `nextOffset`, `endOfFile`,
`charactersReturned`, `totalCharacters`, `offsetUnit` and `artifactSha256`.
Append `content` exactly, preserving newlines. On each subsequent call, use the
returned `nextOffset` and supply the first page's hash as `expectedSha256`.
Stop when `endOfFile` is true (`nextOffset` is then null). A changed file is
rejected; restart retrieval rather than combining pages from different reports.

Offsets and page sizes count **Unicode code points**. Supplementary Unicode
characters count as one code point; pages never split a UTF-16 surrogate pair.
Joined or multi-character emoji can span pages; append the content exactly to
reconstruct them. The default page is 20,000 code points, capped
at 200,000. Reports must be valid UTF-8 and at most **50 MiB**. Each page streams
the file to verify its complete SHA-256 and length while retaining only the
requested page in memory. This bounds memory; reading many small pages still
requires repeated file processing. The hash covers the original UTF-8 bytes.

Both read tools enforce the caller's report directory. They reject traversal,
symbolic-link paths, generation staging files and multiple hard links where the
file system exposes link counts. The report directory must remain controlled by
trusted server processes.

Expert reporting additionally lets you choose a ZAP report template.

## Client Spider Findings

In `v0.13.0`, [Client Spider](../client-spider/) uses these same findings and report tools. Wait for crawl completion and `zap_passive_scan_wait`, then filter the summary, details, and report by the target's `baseUrl`.

These tools read the shared ZAP session, not an exclusive set of findings for one crawl. A `baseUrl` filter narrows the target but does not isolate a scan ID. Other crawls, authentication checks, and active scans against that target can contribute alerts. Recorded alert instances are not necessarily distinct confirmed vulnerabilities.

There is no dedicated Client Spider results-list or Client Map export tool in the server yet. `zap_ajax_spider_results` belongs to AJAX Spider. Use separate ZAP sessions or instances when a comparison requires results attributable to one crawler.

## Typical Flow

```text
1. Finish crawl or attack work
2. Run zap_passive_scan_wait
3. Read findings summary with the scanned target's baseUrl
4. Drill into grouped details using the same baseUrl
5. Expand to raw instances only when you need evidence
6. Generate a report artifact
7. Retrieve the complete report, following page offsets if the preview truncates
8. In expert mode, snapshot or diff findings for later comparison
```
