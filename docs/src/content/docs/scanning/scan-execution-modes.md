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
- `strategy=client` for Client Spider browser crawling
- `strategy=auto` to let the server decide

Important nuance:

- guided queue mode currently defaults `strategy=auto` to the HTTP spider
- pass `strategy=browser` if you need queued AJAX Spider explicitly
- pass `strategy=client` to use Client Spider explicitly in either execution mode

Client Spider is useful for JavaScript-heavy applications. It runs one headless Firefox browser per crawl and returns a ZAP scan ID, so status and stop operations address that specific crawl. Queued Client Spider jobs share the existing spider concurrency limit, retry settings, and cancellation handling. `strategy=browser` continues to use AJAX Spider.

Client Spider uses `ZAP_MAX_SPIDER_SCAN_DURATION` (minutes; default 15, 0 means unlimited) for both direct and queued crawls. Before starting a crawl, the server sets ZAP's native Client Spider duration option, which ZAP copies into that scan. The limit therefore continues to apply if the MCP server restarts. Keep this setting consistent across MCP replicas sharing one ZAP instance. If ZAP cannot accept the duration option, the crawl is not started.

This uses ZAP's native duration enforcement: it stops the crawl when a browser event is processed after the deadline. It is not a hard wall-clock watchdog for an unresponsive browser. API request, queue startup/wait, and cancellation timeouts remain separate.

Client Spider requires ZAP's Client Side Integration (`client`) add-on version 0.27.0 or newer, Selenium, Firefox, and a compatible WebDriver. The supplied Compose and Helm configurations install `client`, and the default ZAP image includes the browser and WebDriver. If you connect to your own ZAP deployment, provide those prerequisites there.

For authenticated guided Client Spider crawling, configure an operator auth profile with `kind: browser`, prepare it using `zap_auth_session_prepare`, and check it with `zap_auth_session_validate`. Pass the returned session ID to `zap_crawl_start` as `authSessionId` with `strategy=client`. This works in direct and queued modes. The profile uses the existing login URL, username, credential reference, and login indicators; ZAP logs into headless Firefox itself. See the [browser profile example](../authenticated-scanning-best-practices/#browser-authentication-for-client-spider).

HTTP `kind: form` sessions remain supported with `strategy=http` or `auto` and active scans. Browser sessions currently require explicit `strategy=client`; HTTP, auto, AJAX, and active-scan routes reject them. The crawl strategy `browser` still means AJAX Spider, which is separate from the auth profile kind `browser`.

The expert path can use the existing `zap_context_upsert`, `zap_context_auth_configure`, and `zap_user_upsert` tools to set up a separate browser-auth context. Configure `authMethodName=browserBasedAuthentication`, with `loginPageUrl` and `browserId=firefox-headless` in the URL-encoded `authMethodConfigParams`, and enable the user. Browser authentication requires ZAP's Authentication Helper add-on and ZAP 2.16.1 or newer for API configuration. Keep guided profile contexts managed by their profiles.

The expert Client Spider start and queue tools accept an optional `maxDepth` (0 means unlimited); omission uses `ZAP_SPIDER_MAX_DEPTH`. For authenticated crawling, supply both `contextName` and `userName` from an existing ZAP configuration. These are ZAP names, not numeric IDs or login credentials. They use the existing `zap:scan:spider:run` permission, with `zap:scan:read` and `zap:scan:stop` for lifecycle access.

Upgrade all workers sharing a queue before submitting Client Spider jobs; older workers do not recognize the new job type.

After the crawl finishes, use `zap_passive_scan_wait` before reading findings. A Client Spider scan ID identifies the crawl; it does not make the shared ZAP findings store exclusive to that crawl.

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
- `zap_client_spider_start`
- `zap_client_spider_status`
- `zap_client_spider_stop`

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
- `zap_queue_client_spider_scan`
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

See [Passive Scan](../passive-scan/).

## Recommendation

- use `guided` for the default experience
- use `expert` when you need raw control
- use `queue` for shared, automated, or HA-sensitive workloads
- use direct mode for one-off local work where simplicity matters more than durability
