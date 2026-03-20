---
title: "Passive Scan"
editUrl: false
description: "Understand passive backlog status and when to wait before reading findings."
---
Passive scanning in ZAP runs asynchronously after traffic has already been captured. That means a crawl or active scan can finish before passive rules are done producing findings.

Use the passive scan tools when you need to know whether findings are still changing or when you want a bounded wait before reading alerts or generating reports.

## Available Tools

- `zap_passive_scan_status`
- `zap_passive_scan_wait`

These tools are available on both the `guided` and `expert` surfaces.

## `zap_passive_scan_status`

Returns:

- whether passive analysis is complete
- how many records are still waiting to be scanned
- how many passive scan tasks are active
- whether passive scanning is restricted to in-scope traffic only

Use this when:

- you want a fast non-blocking status check
- an agent needs to decide whether to wait or move on
- you are debugging why findings are still changing after crawl or scan steps

## `zap_passive_scan_wait`

Parameters:

- `timeoutSeconds` default `60`
- `pollIntervalMs` default `1000`

Behavior:

- polls the passive backlog until it reaches zero
- returns early when the backlog is drained
- times out deterministically if passive analysis is still running

Use this when:

- you need a stable point before calling findings or report tools
- you want an agent workflow to block until passive analysis has caught up

## When To Wait

Use `zap_passive_scan_wait` after:

- `zap_crawl_start`
- `zap_attack_start`
- `zap_spider_start`
- `zap_ajax_spider`
- `zap_queue_spider_scan`
- `zap_queue_ajax_spider`
- `zap_queue_active_scan`

The rule is simple: if the next step is findings review or report generation, passive wait should usually come first.

## Example Workflow

### Guided flow

```text
1. Run zap_crawl_start or zap_attack_start
2. Wait for completion with zap_crawl_status or zap_attack_status
3. Run zap_passive_scan_wait
4. Read findings or generate a report
```

### Queue-managed flow

```text
1. Submit zap_queue_spider_scan, zap_queue_ajax_spider, or zap_queue_active_scan
2. Wait for the queued job to reach SUCCEEDED
3. Run zap_passive_scan_wait
4. Read findings or generate a report
```

## Notes

- passive wait does not replace queue status
- queue status tells you whether the long-running job is finished
- passive wait tells you whether findings generation has caught up

If passive wait times out, use `zap_passive_scan_status` to inspect backlog and decide whether to retry or proceed with partial results.
