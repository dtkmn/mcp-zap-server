---
title: "Scan Execution"
editUrl: false
description: "Start, monitor, and stop scans through the protected queue with in-memory or PostgreSQL storage."
---
All new crawls and active scans enter the scan-job queue. Storage determines persistence, not execution routing.

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

For queued crawls, `auto` selects HTTP without checking JavaScript coverage or switching crawlers after a start failure. Select the crawler appropriate for the authorized target. A successful HTTP crawl does not prove JavaScript routes were explored.

Client Spider jobs share the configured spider concurrency limit. AJAX Spider uses a server-generated tracking ID because its native status and stop APIs are global. See [Client Spider](../client-spider/) and [AJAX Spider](../ajax-spider/) for prerequisites, limits and outcome interpretation.

## Ask for a Crawl

With MCP ZAP Connect, **“crawl this site”** requests one HTTP crawl of the authorized target. The agent calls `zap_crawl_start` with `strategy=http` and tracks the returned Operation ID.

**“Crawl this site thoroughly”** asks the agent to run two passes in order. HTTP Spider follows ordinary links. Client Spider can discover routes that appear only after JavaScript runs in a browser.

1. Start the HTTP crawl and poll `zap_crawl_status` with its Operation ID. For a queued HTTP crawl, wait for `Status: SUCCEEDED`. For a direct HTTP crawl on v0.17.1, wait for `Completed: yes` in its status reply. If the HTTP crawl fails, is cancelled, or has an uncertain outcome, stop the sequence and report its status.
2. If browser crawling is in scope for the same target, call `zap_crawl_start` once with `strategy=client`. Track this pass with its own Operation ID. If you use idempotency keys, give each pass a different key. If the browser or required browser login profile is unavailable, report that only the HTTP pass completed. Never continue an authenticated crawl anonymously.
3. If asked to stop, end the sequence and use `zap_crawl_stop` with the current pass’s Operation ID if it is still outstanding. After the browser finishes, check the discovered traffic before claiming coverage.

The agent submits each pass separately. Use explicit `strategy=http` for the first pass. A single call with `strategy=auto` does not request both spiders. A thorough crawl authorizes neither a wider target scope nor an active scan.

## Authentication and Scan Options

Prepared `kind: form` sessions support HTTP/auto crawling and active scans. Prepared `kind: browser` sessions require `strategy=client`; `strategy=browser` means AJAX Spider and does not accept a prepared authentication session. See [Authenticated Scanning](../authenticated-scanning-best-practices/).

When starting an active scan, use `recurse` to control recursion and `policy` to select a scan policy. Use an operator-configured policy name; the MCP catalog does not expose policy editing. See [Scan Policy Selection](../scan-policy-controls/).

<a id="http-crawl-limits-unreleased-correction"></a>

## HTTP Crawl Limits

`ZAP_SPIDER_MAX_DEPTH` bounds HTTP depth, while `ZAP_SPIDER_MAX_CHILDREN` controls children per page. Both default to **10**; **0** means unlimited. These are independent limits, including for authenticated crawls. Client Spider uses its own depth option.

The corrected HTTP depth handling was introduced in `v0.15.0`; `v0.14.0` sent the depth value as a child-count limit. ZAP checks the child limit against its evolving Sites tree, so sibling links already queued can still be fetched. These settings do not guarantee an exact request count. Use duration limits and target scope alongside them.

## Storage and Recovery

In-memory job history lasts for the server process. PostgreSQL persists job history and supports shared claim-based worker ownership. Preserve the configured operation-signing key to keep Operation IDs valid across server restarts. Database persistence alone does not prove the outcome of an external ZAP call.

Both stores use the same launch-safety checks. Unresolved launches retain capacity and require evidence or an explicit authorized resolution. See [Queue Retry and Launch Recovery](../../operations/scan-queue-retry-policy/).

For compatibility with older operation handles, see [Migrating older clients](../../getting-started/tool-migration/#existing-operation-handles).

## Finish the Evidence Workflow

After crawl or attack completion:

1. Run `zap_passive_scan_wait` and confirm analysis has drained.
2. Read findings for the target's `baseUrl`.
3. Generate and retrieve reports or snapshots as needed.

Completion alone does not prove coverage or a clean security result. See [Findings and Reports](../findings-and-reports/).
