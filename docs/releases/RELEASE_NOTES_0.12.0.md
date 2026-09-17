# Release Notes - Version 0.12.0

These notes describe **MCP ZAP Server `v0.12.0`**. Release preparation and a merge
to `main` do not publish images. Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for publication
status and verify that the release workflow has published the versioned image
before deploying.

## Highlights

- Retry and persist queue cancellation for active scans, traditional spiders, and AJAX Spider, including cleanup of scans accepted after their dispatch timed out.
- Keep AJAX ownership and occupied capacity until stopping is confirmed, preventing delayed cleanup from stopping a newer managed crawl. Native scan types retain their own scan IDs and configured concurrency.
- Wait with bounded backoff when ZAP explicitly reports that the engine is busy, without consuming startup attempts.
- Preserve ZAP finding metadata and compare structural endpoint identities in version 2 snapshots, reducing false new/resolved findings caused by changing URL values.
- Report AJAX stopped states without claiming confirmed success or a meaningful progress percentage.
- Fix long-hostname policy-validation stack overflow and return a sanitized HTTP `500` for unexpected token-validation backend failures. Invalid-token response behavior is unchanged.

## Upgrade Notes

### PostgreSQL scan-job state

Apply these additive migrations before starting the upgraded server against an
existing PostgreSQL scan-job store:

- `V7__add_scan_job_engine_busy_wait.sql`
- `V8__add_scan_job_cancellation_retry.sql`

Both are included in the application and Helm migration bundles. Migration
execution remains disabled by default: enable the application's
`DB_MIGRATIONS_ENABLED` or Helm's `migrations.enabled`, or apply them through your
existing migration process. In-memory deployments need no database migration.

### Findings snapshots and CI gates

New server snapshots use version 2 and preserve `nodeName`, HTTP method, tags,
and recorded examples. Comparison prefers the full node name and method, with
a URL fallback when node identity is unavailable. Several examples may represent
one finding; exported example counts are not counts of every affected endpoint.

Version 1 baselines remain supported with legacy URL-based comparison and an
explicit notice. Export a reviewed version 2 baseline to adopt the new identity
rules. Update the bundled CI gate alongside the server; it accepts old and new
snapshots. External readers that only accept version 1 must be updated before
consuming new exports. See [CI Gate Contracts](../scanning/CI_GATE_CONTRACTS.md).

### Timeouts and cancellation

Calls from this server to ZAP now default to a 5-second connection timeout and a
10-second response-read inactivity timeout, configured through
`ZAP_API_CONNECT_TIMEOUT_MS` and `ZAP_API_READ_TIMEOUT_MS`. Both must be positive.
These are per-request limits, not a total scan duration. A timeout does not prove
that ZAP rejected an action.

The separate `ZAP_CONNECTION_TIMEOUT` setting now controls ZAP's connections to
scan targets at startup. Existing example and Helm values of **60 seconds now
take effect**, whereas they were previously ignored. `ZAP_INIT_CONNECTION_TIMEOUT`
takes precedence when set; the effective default remains 300 seconds when
neither variable is set. Replace direct uses of the removed
`zap.scan.limits.connectionTimeoutInSecs` property with
`zap.initialization.connectionTimeoutInSecs`.

`ZAP_SCAN_QUEUE_ENGINE_BUSY_MAX_WAIT_MS` defaults to 180000 milliseconds; set it
to `0` to fail immediately on an explicit busy response.
`ZAP_SCAN_QUEUE_CANCEL_MAX_WAIT_MS` sets one cancellation and cleanup retry window,
defaulting to 30000 milliseconds. The former AJAX-specific environment variable
and property remain fallback aliases. Expiry leaves cancellation unconfirmed and
capacity reserved until stopping is confirmed; an explicit cancellation request
can open a new retry window.

Managed AJAX starts and stops share one lifecycle lock because ZAP has a global
AJAX crawler. Replicas controlling one ZAP instance must share the job store.
Avoid unrelated AJAX starts through ZAP's API or UI while the server owns that
crawler. Active scans and traditional spiders can still run concurrently.

### Deployment and compatibility

- Compose accepts `ZAP_IMAGE`, Docker integration tests accept `ZAP_TEST_IMAGE`, and Helm accepts `zap.image.digest`. The default ZAP core version remains `2.17.0`. An image digest does not pin add-ons stored in persisted ZAP state.
- The default Helm ZAP API access expression now permits ZAP's API hostname. The AJAX add-on installation hint uses the correct name, `spiderAjax`.
- Project descriptions and the engine display name now use “ZAP”. MCP tool names and input schemas are unchanged; this release does not introduce Client Spider tools.
- Helm chart `0.12.0` defaults to image `v0.12.0`. Match chart and image versions, and publish MCP Registry metadata only after its referenced images are available.
- The extension API remains `experimental-local`, with local proof version `0.12.0`. Its public source contract is unchanged, and the release workflow does not publish it to Maven Central.

See the [scan queue retry and backoff documentation](../src/content/docs/operations/scan-queue-retry-policy.md) for configuration details.
