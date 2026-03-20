---
title: "Scan Execution Modes"
editUrl: false
description: "Choose between guided, direct, queue-managed, and Automation Framework workflows."
---
MCP ZAP Server supports multiple scan execution paths. If you do not understand which one you are using, you will misread the behavior around durability, retries, and failover.

## Guided Mode

The default `guided` surface exposes:

- `zap_crawl_start`
- `zap_crawl_status`
- `zap_crawl_stop`
- `zap_attack_start`
- `zap_attack_status`
- `zap_attack_stop`

Guided mode is intent-first. The server chooses direct versus queue execution from deployment topology:

- it prefers `queue` when Postgres-backed job storage or the `postgres-lock` coordinator is enabled
- otherwise it uses `direct`

Guided crawl strategy:

- `strategy=http` for the traditional spider
- `strategy=browser` for AJAX Spider behavior
- `strategy=auto` to let the server decide

Important nuance:

- guided queue mode currently defaults `strategy=auto` to the HTTP spider
- pass `strategy=browser` if you need queued AJAX Spider explicitly

## Direct Mode

Direct mode is available on the `expert` surface.

Direct tools:

- `zap_spider_start`
- `zap_spider_status`
- `zap_spider_stop`
- `zap_spider_as_user`
- `zap_active_scan_start`
- `zap_active_scan_status`
- `zap_active_scan_stop`
- `zap_active_scan_as_user`
- `zap_ajax_spider`
- `zap_ajax_spider_status`
- `zap_ajax_spider_stop`

Use direct mode when:

- you run a single MCP replica
- you want a lightweight start and poll workflow
- you do not need durable job state or idempotent retries

Tradeoffs:

- no durable `ScanJob`
- no dead-letter lifecycle
- no HA failover semantics

## Queue Mode

Queue mode is available on the `expert` surface.

Queue-managed tools:

- `zap_queue_spider_scan`
- `zap_queue_spider_scan_as_user`
- `zap_queue_ajax_spider`
- `zap_queue_active_scan`
- `zap_queue_active_scan_as_user`
- `zap_scan_job_status`
- `zap_scan_job_list`
- `zap_scan_job_cancel`
- `zap_scan_job_retry`
- `zap_scan_job_dead_letter_list`
- `zap_scan_job_dead_letter_requeue`

Use queue mode when:

- you want durable status and history
- client retries should be deduplicated with `idempotencyKey`
- you need cancel, retry, and dead-letter behavior
- you run multiple replicas or expect failover

Queue advantages:

- durable shared job state
- claim-based worker ownership
- retry budgets and backoff
- claim recovery after worker loss

## Automation Framework Plans

Automation Framework tools are also `expert` only:

- `zap_automation_plan_run`
- `zap_automation_plan_status`
- `zap_automation_plan_artifacts`

Use them when you want one repeatable ZAP-native plan file to orchestrate multiple steps.

Do not confuse this with queue mode:

- automation plans are not the durable `ScanJob` queue abstraction
- queue mode remains the main HA-safe orchestration path for long-running shared scans

## Passive Scan Still Applies

Direct mode, queue mode, and guided mode all generate traffic that passive rules analyze in the background.

After crawl or attack completion:

1. confirm the crawl or scan is finished
2. run `zap_passive_scan_wait`
3. then read findings or generate reports

See [Passive Scan](./passive-scan/).

## Recommendation

- use `guided` for the default experience
- use `expert` when you need raw control
- use `queue` for shared, automated, or HA-sensitive workloads
- use direct mode for one-off local work where simplicity matters more than durability
