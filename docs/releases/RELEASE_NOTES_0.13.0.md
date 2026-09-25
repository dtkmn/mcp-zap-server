# Release Notes - Version 0.13.0

These notes describe **MCP ZAP Server `v0.13.0`**. Release preparation and a merge
to `main` do not publish images. Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for publication
status and verify that the release workflow has published the versioned image
before deploying.

## Highlights

- Explore JavaScript applications with ZAP Client Spider through guided `strategy=client`, expert direct tools, or the durable scan queue.
- Track and stop each crawl using its native ZAP scan ID. Queued crawls use the existing spider capacity, retry, cancellation, and history handling.
- Sign in through operator-managed `kind: browser` authentication profiles for Client Spider. Automatic session detection supports cookies and header tokens during verification, fixing the case where Juice Shop browser login succeeded but the separate verification request omitted its bearer token.
- Require a positive ZAP authentication verdict and the configured logged-in indicator in a fresh response before reporting successful browser authentication.
- Reject malformed stored crawl-depth values with a clear error before starting a crawl.

## Using Client Spider

On the guided surface, call `zap_crawl_start` with `targetUrl` and
`strategy: client`, then pass its Operation ID to `zap_crawl_status` or
`zap_crawl_stop`. The deployment selects direct or queued execution.

On the expert surface, use `zap_client_spider_start`,
`zap_client_spider_status`, and `zap_client_spider_stop` for direct control.
`zap_queue_client_spider_scan` returns a queue job ID for
`zap_scan_job_status` and `zap_scan_job_cancel`.

The existing guided `strategy: browser` still selects AJAX Spider.
`strategy: auto` does not select Client Spider. See the
[Client Spider guide](../src/content/docs/scanning/client-spider.md) for examples,
tool permissions, and authenticated workflows.

## Upgrade Notes

### ZAP and browser prerequisites

- Install ZAP Client Side Integration (`client`) **0.27.0 or newer**, Selenium, Firefox, and a compatible WebDriver. Supplied Compose and Helm configurations install `client`; the default ZAP image includes the browser and WebDriver.
- Browser login additionally requires Authentication Helper. API configuration of browser authentication requires ZAP **2.16.1 or newer**; the project's configured and tested ZAP baseline remains **2.17.0**.
- Set `ZAP_API_READ_TIMEOUT_MS=60000` for browser authentication (`mcp.zapClient.readTimeoutMs: 60000` in Helm). Browser login and validation can exceed the default 10-second API read timeout. This setting affects requests from the server to ZAP, not the total crawl duration.
- The supported application runtime remains **Java 25**. This release does not introduce another runtime or change the ZAP baseline.

### Browser authentication

Configure an operator-managed `kind: browser` profile using the existing
credential-reference mechanism. Prepare it with `zap_auth_session_prepare`,
using a protected response that proves login as `targetUrl`, then require
`Valid: true` from `zap_auth_session_validate` before starting the crawl with
`authSessionId`. A public page returning HTTP `200` does not prove login.

Guided browser profiles enable automatic session management so verification can
replay the detected cookie or header token. This automatic setup applies to
guided browser profiles; expert ZAP context configuration remains
operator-managed.

Browser profiles currently work **only with Client Spider**. Guided HTTP, AJAX,
`auto`, and active-scan paths reject them. Keep a separate form profile for
supported HTTP and active-scan workflows. Custom login steps, MFA, and CAPTCHA
are not configured by this feature. See
[browser authentication setup](../src/content/docs/scanning/authenticated-scanning-best-practices.md#browser-authentication-for-client-spider).

### Queue rollout and crawl limits

Upgrade **all workers sharing a scan queue** before submitting `CLIENT_SPIDER`
jobs. Older workers do not understand the new job type, including retained
records. Finishing or cancelling a crawl does not make its stored job readable
by `0.12.0`; account for those records before rolling workers back.

No new database migration is required when upgrading from `0.12.0`: the migration
inventory remains V1 through V8. Deployments upgrading from an earlier version
must still apply its required migrations; see the
[0.12.0 upgrade notes](./RELEASE_NOTES_0.12.0.md#postgresql-scan-job-state).
Migration execution remains opt-in.

Both direct and queued Client Spider crawls use
`ZAP_MAX_SPIDER_SCAN_DURATION` (**15 minutes** by default; `0` means unlimited).
The server configures ZAP's native duration before starting and rejects the
start if that configuration fails. Keep this setting consistent across replicas
sharing ZAP. It is enforced during browser event processing, so it is not a hard
deadline for a hung browser.

`ZAP_SPIDER_MAX_DEPTH` defaults to **10**. Expert direct and queue calls can
override it with `maxDepth`; `0` means unlimited. Each crawl uses one headless
Firefox browser and queued crawls share the existing spider concurrency limit.

### Findings and reports

Client Spider discovers content and generates traffic and browser observations;
it does not itself run an active vulnerability scan. After checking crawl
coverage and waiting for passive analysis with `zap_passive_scan_wait`, use the
existing `zap_findings_summary`, `zap_findings_details`, `zap_report_generate`,
and `zap_report_read` tools.

These findings and reports belong to the **shared ZAP session**. A `baseUrl`
filter narrows the target but does not isolate one crawl's scan ID. Repeated
crawls and other scans can contribute to the same report. There is no dedicated
Client Spider discovery-list or per-crawl export tool, and AJAX Spider's
`zap_ajax_spider_results` does not return Client Spider results.

A status of `100%` or a queue job marked `SUCCEEDED` means the crawl reached its
terminal state; it does not prove complete coverage. Confirm actual discovered
traffic and authenticated responses. No controlled comparison has established
a coverage advantage over the other crawlers.

During packaged validation with ZAP `2.17.0` and Client Side Integration `0.31.0`,
ZAP could not find a history reference for Juice Shop's `#/search` route when
raising browser session-storage, local-storage, and JWT local-storage alerts.
Those specific observations could not be attached as alerts. Review ZAP's logs
alongside the report: successful crawling and an empty passive backlog do not
prove that every browser-storage observation was recorded as a finding.

### Deployment metadata

Helm chart `0.13.0` defaults to image `v0.13.0`. Match the chart and image
versions, and publish MCP Registry metadata only after its referenced images
are available. A merge to `main` checks and builds the project but does not
publish the versioned release image.

The extension API remains `experimental-local`, with local proof version
`0.13.0`. The release workflow does not publish it to Maven Central.

## Validation

The release preparation passed 672 automated application tests, 47 CI-helper
tests, the standalone extension build, Helm validation, and both documentation
site builds. A local Linux ARM64 container built from the project Dockerfile
reported MCP version `0.13.0` without an environment override and passed 20
authenticated direct-crawl checks and 19 PostgreSQL-backed queue checks against
Juice Shop `19.1.1`. Both modes produced fresh successful authenticated responses
and product API traffic. These checks establish the tested workflows; the
findings limitation above remains.
