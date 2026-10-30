---
title: "Scan Execution"
editUrl: false
description: "Start, monitor, and stop guided scans through the protected queue with in-memory or PostgreSQL storage."
---
Guided crawl and attack starts always use the existing scan-job queue. Storage determines persistence, not execution routing.

## Start and Track a Scan

Use `zap_crawl_start` for discovery or `zap_attack_start` for an explicitly authorized active scan. Keep the returned **Operation ID** and pass it to the matching status or stop tool:

- `zap_crawl_status` / `zap_crawl_stop`
- `zap_attack_status` / `zap_attack_stop`

Acceptance records a job; it does not prove ZAP has started it. The job can wait for capacity, then prepare and start. A confirmed native start is tracked as `RUNNING`. A lost or uncertain start reply remains held for recovery and must not trigger another automatic start.

An optional `idempotencyKey` reuses an equivalent request from the same requester. Reusing the key with different parameters is rejected. Requests without a key can create separate jobs. Retry budgets do not permit repeating an uncertain start.

## Choose a Crawler

| Strategy | Queued crawler |
| --- | --- |
| `http` | Traditional HTTP Spider |
| `auto` or omitted | HTTP Spider, without automatic fallback |
| `client` | Client Spider for browser-based discovery |
| `browser` | AJAX Spider |

`auto` does not detect JavaScript coverage and never switches to another crawler after a start failure. Select the crawler appropriate for the authorized target. A successful HTTP crawl does not prove JavaScript routes were explored.

Client Spider jobs share the configured spider concurrency limit. AJAX Spider uses a server-generated tracking ID because its native status and stop APIs are global. See [Client Spider](../client-spider/) and [AJAX Spider](../ajax-spider/) for prerequisites, limits and outcome interpretation.

## Authentication and Scan Options

Prepared `kind: form` sessions support HTTP/auto crawling and active scans. Prepared `kind: browser` sessions require `strategy=client`; `strategy=browser` means AJAX Spider and does not accept a guided auth session. See [Authenticated Scanning](../authenticated-scanning-best-practices/).

Guided attacks preserve the `recurse` and `policy` options. Use an operator-configured policy name; the MCP catalog does not expose policy editing. See [Scan Policy Selection](../scan-policy-controls/).

<a id="http-crawl-limits-unreleased-correction"></a>

## HTTP Crawl Limits

`ZAP_SPIDER_MAX_DEPTH` bounds HTTP depth, while `ZAP_SPIDER_MAX_CHILDREN` controls children per page. Both default to **10**; **0** means unlimited. These are independent limits, including for authenticated crawls. Client Spider uses its own depth option.

The corrected HTTP depth handling was introduced in `v0.15.0`; `v0.14.0` sent the depth value as a child-count limit. ZAP checks the child limit against its evolving Sites tree, so sibling links already queued can still be fetched. These settings do not guarantee an exact request count. Use duration limits and target scope alongside them.

## Storage and Recovery

In-memory job history lasts for the server process. PostgreSQL persists job history and supports shared claim-based worker ownership. Preserve the configured operation-signing key when guided handles must survive server restart. Database persistence alone does not prove the outcome of an external ZAP call.

Both stores use the same launch-safety checks. Unresolved launches retain capacity and require evidence or an explicit authorized resolution. See [Queue Retry and Launch Recovery](../../operations/scan-queue-retry-policy/).

For compatibility with older operation handles, see [Upgrading to Guided Tools](../../getting-started/tool-migration/#existing-operation-handles).

## Finish the Evidence Workflow

After crawl or attack completion:

1. Run `zap_passive_scan_wait` and confirm analysis has drained.
2. Read findings for the target's `baseUrl`.
3. Generate and retrieve reports or snapshots as needed.

Completion alone does not prove coverage or a clean security result. See [Findings and Reports](../findings-and-reports/).
