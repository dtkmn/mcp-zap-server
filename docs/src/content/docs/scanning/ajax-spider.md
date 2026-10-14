---
title: "AJAX Spider"
editUrl: false
description: "Use the existing AJAX Spider API route for browser crawling, with direct or queue-managed lifecycle control."
---
AJAX Spider uses a real browser to explore rendered pages and JavaScript navigation. For new JavaScript application workflows, prefer explicit [Client Spider](../client-spider/) with `strategy=client`, following [ZAP's recommendation](https://www.zaproxy.org/blog/2026-09-08-zap-updates-august-2026/). Existing AJAX workflows remain available with `strategy=browser`. Browser crawling does not guarantee that every route is discovered or that WAF and bot protections are bypassed.

## Surface Note

Raw AJAX Spider tools are `expert` only:

- `zap_ajax_spider`
- `zap_ajax_spider_status`
- `zap_ajax_spider_stop`
- `zap_ajax_spider_results`
- `zap_queue_ajax_spider`

If you stay on the default `guided` surface, use `zap_crawl_start` with `strategy=browser`.

Client Spider supports guided browser authentication profiles. The AJAX API route selected by `strategy=browser` rejects guided `authSessionId`. `zap_ajax_spider_results` reads global AJAX API results; it is not a lookup by native Client Spider scan ID.

## When To Use It

Keep AJAX Spider for existing workflows that need its API or crawl behavior. Guided selection remains:

- `http`: traditional HTTP link discovery in direct or queued mode.
- `client`: explicit Client Spider in either mode.
- `browser`: the AJAX Spider API route in either mode.
- `auto` or omitted: HTTP Spider; only an unauthenticated direct start falls back to AJAX when HTTP start fails. Queued `auto` and prepared form-login sessions stay with HTTP.

`auto` does not inspect JavaScript coverage. HTTP start success alone is not a reason to assume a modern application's routes were explored.

The supplied Compose and Helm configurations explicitly install both `spiderAjax` and `client`. ZAP [removed AJAX Spider from weekly/nightly defaults](https://www.zaproxy.org/blog/2026-10-01-zap-updates-september-2026/) but keeps it available in the Marketplace and provides legacy compatibility through Client Spider when AJAX is absent. That compatibility belongs to ZAP. An alternate or minimal image still needs compatible APIs, a browser, and the required add-ons; verify those before using it with this server.

## Direct And Queued Options

### Direct

Use `zap_ajax_spider` for simple or single-replica workflows. AJAX status and stop address the global AJAX crawler, rather than a native scan ID. Do not treat an old guided AJAX Operation ID as an independent crawler instance. Use Client Spider when you need native scan-specific status and stopping.

### Queue-managed

Use `zap_queue_ajax_spider` when:

- you run multiple MCP replicas
- you want AJAX Spider to appear in `zap_scan_job_status` and `zap_scan_job_list`
- you want cancel, retry, and dead-letter behavior consistent with other queued scans
- you want idempotent admission with `idempotencyKey`

AJAX queue jobs retain a server-generated backend tracking ID. It is not an independent native ZAP scan ID; the underlying AJAX status and stop API remain global.

## After The Crawl

ZAP's AJAX status confirms whether the crawler is running, not whether crawling succeeded. A finished queue lifecycle also does not prove coverage. Check the discovered traffic and follow the crawl with `zap_passive_scan_wait` before findings review or report generation.

In AJAX Spider `23.33.0`, the stop API can report stopped while its worker is still initializing browsers or finishing cleanup. ZAP can still reject a new start as busy until that worker finishes. Queued work waits for the engine within its configured wait limit; a stop response does not establish readiness for another crawl or immediate termination of all requests.

Crawler selection and vulnerability-rule coverage are separate. Weekly/nightly defaults also omit the DOM XSS Active Scan Rule add-on; using Client Spider does not by itself provide the same active checks. Record installed rules when comparing findings across images.

## Best Practices

- use HTTP Spider for simple server-rendered sites
- prefer Client Spider for new JavaScript application workflows, and verify actual discovered traffic
- prefer `zap_queue_ajax_spider` in HA deployments
- use a stable `idempotencyKey` for client retries when queueing
- watch memory usage because browser crawling is heavier than the HTTP spider
