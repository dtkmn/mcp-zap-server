---
title: "Client Spider"
editUrl: false
description: "Crawl JavaScript applications with Client Spider, verify browser login, and collect findings in direct or queued workflows."
---

> **Version `v0.13.0`:** In this version, Client Spider and browser authentication profiles support direct and queued crawls. They are not included in `v0.12.0`. Before installing, check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and confirm that the corresponding release workflow published the image to your registry. Release preparation and a merge to `main` do not publish a release or image.

Client Spider explores a website using headless Firefox and ZAP's Client Side Integration browser extension. It can observe rendered page content, JavaScript navigation, and browser storage that ordinary HTTP link discovery does not see. Crawling generates traffic and observations for ZAP's checks; it does not itself run an active vulnerability scan.

## Choose a Crawler

| Guided strategy | Crawler | Use |
| --- | --- | --- |
| `http` | Traditional Spider | Discover links in server-rendered content. |
| `browser` | AJAX Spider | Use the existing browser-driven crawl workflow. |
| `client` | Client Spider | Explore JavaScript applications with the browser extension and optional guided browser authentication. |
| `auto` | Server-selected crawler | Use the default selection; this does not select Client Spider. |

The strategy `browser` means AJAX Spider. The authentication profile kind `browser` is a separate setting and currently works only with `strategy: client`.

ZAP describes Client Spider's direct access to page content in its [Client Spider documentation](https://www.zaproxy.org/docs/desktop/addons/client-side-integration/spider/). Coverage depends on the application, login state, and crawl limits. Do not assume it finds more issues than another crawler without comparing equivalent runs.

## Prerequisites and Limits

- ZAP Client Side Integration (`client`) add-on version **0.27.0 or newer**, Selenium, Firefox, and a compatible WebDriver. The supplied Compose and Helm configurations install `client`; the default ZAP image includes the browser and WebDriver.
- For browser login: Authentication Helper and ZAP **2.16.1 or newer** for API configuration. Set `ZAP_API_READ_TIMEOUT_MS=60000` (`mcp.zapClient.readTimeoutMs: 60000` in Helm) so browser login has time to finish.
- `ZAP_SPIDER_MAX_DEPTH` sets the default depth (**10**). Expert start and queue calls can override it with `maxDepth`. **0** means unlimited.
- `ZAP_MAX_SPIDER_SCAN_DURATION` sets the duration in minutes (**15** by default, **0** means unlimited) for both direct and queued crawls. The server sets ZAP's native duration option before starting; if that fails, it does not launch the crawl. Keep the setting consistent across replicas sharing ZAP.

Each crawl uses one headless Firefox browser. ZAP copies the duration setting into the crawl and enforces it when processing browser events; this is not a hard deadline for a hung browser. Queue and API timeouts remain separate. Upgrade all workers sharing a queue before submitting Client Spider jobs. Older workers cannot read stored `CLIENT_SPIDER` records, including completed jobs; account for the stored queue state before rolling workers back.

## Guided Crawl

On the default `guided` surface, call `zap_crawl_start` with these arguments:

```json
{
  "targetUrl": "https://shop.example.com/",
  "strategy": "client"
}
```

Use an authorized target reachable from the ZAP browser. For the supplied Juice Shop lab, use its address on ZAP's network rather than assuming the browser shares your machine's `localhost`.

The server selects direct or queued execution from the deployment configuration. An optional `idempotencyKey` deduplicates queued admission and is ignored in direct mode. Use the returned **Operation ID** as `operationId` for `zap_crawl_status` and `zap_crawl_stop`.

## Crawl Behind a Login

Configure an operator-managed [`kind: browser` profile](../authenticated-scanning-best-practices/#browser-authentication-for-client-spider), with a credential reference, login URL, and logged-in indicator.

1. Call `zap_auth_session_prepare` with that `profileId` and a protected `targetUrl` whose response proves login.
2. Call `zap_auth_session_validate`, passing the returned session ID as `sessionId`. Continue only after `Valid: true`.
3. Call `zap_crawl_start` with the site's crawl URL, `strategy: client`, and the same session ID as `authSessionId`.
4. Check that fresh requests reached protected content as the expected user.

Browser profiles enable automatic session detection, allowing ZAP to replay cookies or header tokens, including bearer tokens, during verification. This addresses applications such as Juice Shop where the browser login succeeds but a separate verification request would otherwise omit the token. A public page returning HTTP `200` is not proof of login.

This profile supports automatic username/password login in direct and queued Client Spider crawls. It does not configure custom login steps, MFA, or CAPTCHA. Browser sessions are rejected by `auto`, HTTP, AJAX, and guided active-scan paths; keep a separate form profile for supported HTTP and active-scan workflows.

## Expert Direct and Queue Tools

Set `MCP_SERVER_TOOLS_SURFACE=expert` for raw lifecycle control.

| Tool | Arguments and returned identifier |
| --- | --- |
| `zap_client_spider_start` | Required `targetUrl`; optional `maxDepth`, `contextName`, `userName`. Returns a ZAP scan ID. |
| `zap_client_spider_status` | `scanId` from the direct start. |
| `zap_client_spider_stop` | `scanId` from the direct start. |
| `zap_queue_client_spider_scan` | The same start arguments, plus optional `idempotencyKey`. Returns a queue job ID. |

For queued work, pass `jobId` to `zap_scan_job_status` or `zap_scan_job_cancel`. Queue jobs share the spider concurrency limit, retry policy, and cancellation handling. Native ZAP scan IDs keep status and stopping specific to each crawl.

For expert authenticated crawling, supply both `contextName` and `userName` from an existing ZAP browser-authentication configuration. These are names, not numeric IDs or credentials. Expert configuration remains operator-managed; the automatic session setup above belongs to guided browser profiles. See [Scan Execution Modes](../scan-execution-modes/) for context setup.

Guided starts use `zap:scan:crawl:run`; expert direct and queued starts use `zap:scan:spider:run`. Status uses `zap:scan:read`, and stopping or cancellation uses `zap:scan:stop`.

## Findings and Reports

After the crawl stops:

1. Check actual discovered traffic and authenticated responses. `100%` or a queue job marked `SUCCEEDED` does not prove complete coverage.
2. Run `zap_passive_scan_wait` to let passive analysis finish.
3. Call `zap_findings_summary` with `baseUrl` set to the target, then `zap_findings_details` for evidence.
4. Call `zap_report_generate` with the same `baseUrl` and `format: html` or `format: json`.
5. Read the returned artifact using `zap_report_read` with its `reportPath`. These report tools are available on both surfaces.

Findings and reports use the **shared ZAP session**. Filtering by `baseUrl` narrows the target; it does not isolate one Client Spider scan ID. Repeated crawls and other scans can contribute to the same report. Alert instances are recorded examples, not necessarily distinct confirmed vulnerabilities.

There is currently no dedicated Client Spider results-list or Client Map export tool in this MCP server. AJAX Spider's `zap_ajax_spider_results` does not return Client Spider results. For controlled comparisons, use separate ZAP sessions or instances with the same target, authentication, and crawl settings. See [Findings and Reports](../findings-and-reports/).

In packaged Juice Shop validation with ZAP `2.17.0` and Client Side Integration `0.31.0`, ZAP logged that it could not find a history reference for the `#/search` route while raising browser session-storage, local-storage, and JWT local-storage alerts. Those observations could not be attached as alerts. Review ZAP logs as well as the findings report; a finished crawl and an empty passive backlog do not prove that all browser-storage observations became findings.
