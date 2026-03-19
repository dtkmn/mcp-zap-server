---
title: "AJAX Spider"
editUrl: false
description: "Use the browser-backed crawler for JavaScript-heavy apps, protected sites, and queued HA workflows."
---
The AJAX Spider uses a real browser to crawl sites. It is much more effective than the traditional HTTP spider for JavaScript-heavy applications, login-heavy flows, and sites that behave differently for normal browsers.

## Surface Note

Raw AJAX Spider tools are `expert` only:

- `zap_ajax_spider`
- `zap_ajax_spider_status`
- `zap_ajax_spider_stop`
- `zap_ajax_spider_results`
- `zap_queue_ajax_spider`

If you stay on the default `guided` surface, use `zap_crawl_start` with `strategy=browser`.

## When To Use It

Use AJAX Spider when:

- the target is an SPA
- pages depend on client-side routing or JavaScript rendering
- the traditional spider misses navigation paths
- you want browser-driven crawling inside the shared queue lifecycle

## Direct And Queued Options

### Direct

Use `zap_ajax_spider` for simple or single-replica workflows.

### Queue-managed

Use `zap_queue_ajax_spider` when:

- you run multiple MCP replicas
- you want AJAX Spider to appear in `zap_scan_job_status` and `zap_scan_job_list`
- you want cancel, retry, and dead-letter behavior consistent with other queued scans
- you want idempotent admission with `idempotencyKey`

## After The Crawl

If the next step is findings review or report generation, follow the crawl with `zap_passive_scan_wait` so passive analysis has time to finish processing the browser-driven traffic.

## Best Practices

- start with the normal spider when speed matters and the site is simple
- switch to browser crawling when coverage is clearly incomplete
- prefer `zap_queue_ajax_spider` in HA deployments
- use a stable `idempotencyKey` for client retries when queueing
- watch memory usage because browser crawling is heavier than the HTTP spider
