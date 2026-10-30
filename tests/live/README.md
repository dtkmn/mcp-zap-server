# Client Spider live regression checks

`client_spider_juice_shop.py` uses only Python 3's standard library. It drives the
real guided MCP Streamable HTTP tools, ZAP Client Spider, and the supplied OWASP
Juice Shop target. It does not start an active attack scan. Retired raw tools and
expert/direct execution options are not supported.

Run against a dedicated local test stack. Existing ZAP traffic makes coverage
attribution ambiguous, and browser crawls can interact with the target. The
script checks that both management endpoints use localhost; the target URL must
be reachable from **ZAP's container/browser**, usually `http://juice-shop:3000`.

## Lab prerequisites

- A server build with guided `zap_crawl_start`, `zap_crawl_status` and
  `zap_crawl_stop` supporting `strategy=client` through the protected queue.
- ZAP with Client Side Integration 0.27.0+, Selenium, Firefox and its driver.
- A dedicated ZAP session and local Juice Shop on the same Docker network.
- MCP credentials with `mcp:tools:list`, `zap:scan:crawl:run`, `zap:scan:read`
  and `zap:scan:stop`. Authenticated runs also need `zap:auth:session:write`
  and `zap:auth:test`.
- Set `ZAP_MAX_CONCURRENT_SPIDER_SCANS=2`. Admission quotas/backpressure must allow
  at least three jobs for the caller. Keep other crawls out of this lab.
- Set `ZAP_MAX_SPIDER_SCAN_DURATION=2` for bounded completion within the default
  240-second lifecycle timeout. The first two crawls must stay running long
  enough to test isolation and a third pending job; early completion fails the
  check. The harness never changes duration, depth or concurrency settings.
- Allow the local Juice Shop origin in the server's URL validation configuration.

This single configuration tests both concurrent independence and queued
cancellation: two Client Spider jobs must be running before the third is queued.
A limit of one cannot establish two running crawls. Required assertions fail
instead of being skipped if the expected job states are not observed.

## Run

Keep keys in environment variables so they are not exposed in command arguments.
Adjust ports for your isolated stack. `ZAP_HOST_HEADER=zap` is useful when a Docker
port mapping causes ZAP to interpret a request to localhost as proxy traffic.

```sh
export MCP_URL=http://127.0.0.1:17456/mcp
export MCP_API_KEY='<local-test-key>'
export ZAP_URL=http://127.0.0.1:18090
export ZAP_HOST_HEADER=zap
# Set ZAP_API_KEY too if the local ZAP API requires authentication.
python3 tests/live/client_spider_juice_shop.py \
  --evidence /tmp/client-spider-guided.json
```

The default phases run sequentially:

1. MCP initialize/session negotiation and discovery: guided schemas, required
   operation handles, and absence of retired raw Client Spider/job tools.
2. Supported guided validation: blank targets, invalid strategies, unknown auth
   sessions, and blank or malformed operation handles.
3. Two running queue jobs with distinct native IDs; idempotent replay returns the
   same job even when its newly signed Operation ID differs; a changed request
   with the same key is rejected.
4. A third job remains queued with no native ID. Cancelling it leaves both running
   crawls unaffected. Stopping the first then leaves the second running, and
   each running cancellation is checked against its own native ZAP status.
5. A separate crawl ends without the harness requesting a stop; passive analysis
   drains; new successful Juice Shop product-search traffic and multiple fresh
   resources establish bounded discovery evidence.

Every accepted test crawl must report `Execution Mode: queue` and `Strategy: client`.
Job IDs, Operation IDs and native ZAP scan IDs are checked separately. An unexpected
failure or uncertain start stops the run without resubmission. A missing operation handle is
recorded for investigation; the harness does not guess a native ID to clean up.

Guided tools do not expose per-call `maxDepth`, `contextName`, `userName` or raw
`scanId` arguments. Their former raw-tool validation calls are not live checks
in this harness. Native depth and context/user pairing guards remain covered by
service/queue unit tests; the live harness checks the supported guided schema,
prepared authentication sessions and signed-operation boundary instead. Crawl
depth uses the server's `ZAP_SPIDER_MAX_DEPTH` setting.

For an explicit partial investigation:

```sh
python3 tests/live/client_spider_juice_shop.py --phases validation
python3 tests/live/client_spider_juice_shop.py --phases lifecycle \
  --evidence /tmp/client-spider-lifecycle.json
python3 tests/live/client_spider_juice_shop.py --phases completion \
  --timeout 300 --evidence /tmp/client-spider-completion.json
```

Only selected phases are checked; a partial run does not establish the full
regression result. Removed `--mode`, `--expected-execution-mode` and old phase
names are rejected instead of selecting another interface.

## Authenticated crawling

First configure an operator-managed browser profile and a disposable Juice Shop
account. Pass the profile ID, verification URL and expected account email together:

```sh
python3 tests/live/client_spider_juice_shop.py \
  --auth-profile juice-browser-live \
  --auth-verification-url http://juice-shop:3000/rest/user/whoami \
  --auth-expected-user client-spider-test@example.test \
  --evidence /tmp/client-spider-authenticated.json
```

The harness prepares a session against the verification URL, requires `Valid: true`
from session validation, and binds the returned `authSessionId` to every crawl.
The crawl starts at `--target` on the same authorized origin; verification uses
its protected endpoint rather than public SPA HTML. Completion snapshots the
message baseline **after** validation, then requires a fresh HTTP 2xx verification
response containing the expected identity and a request bearing an Authorization
header or token/session cookie. Validation traffic alone cannot pass this assertion.

## Evidence and interpretation

The script exits nonzero on a failed assertion and saves JSON evidence even after
failure. Evidence includes tool schemas, MCP requests/responses, installed add-ons,
native IDs, lifecycle assertions and fresh API message summaries. API keys and
session headers are not recorded. Anonymous Juice Shop response previews are
recorded, so use only the disposable lab. Authenticated runs omit response-body
previews and record credential-presence booleans, never Authorization or cookie values.

ZAP reports 100% for both stopped and completed Client Spider crawls. Progress
alone cannot pass a lifecycle wait. Natural termination requires queue status
`SUCCEEDED`; requested cancellation requires `CANCELLED` and, for a started job,
native status 100. Unknown starts, failed jobs, unconfirmed cancellation or an
unexpected terminal outcome fail the relevant check. Client Spider's termination
still does not prove successful or exhaustive crawl coverage.

The separate discovery check requires fresh message IDs from the bounded crawl,
successful HTTP 2xx responses from `/rest/products/search`, and at least five
distinct resource paths in those fresh requests. Earlier crawl URLs and repeated
query variations do not count toward that threshold. A duration-limited crawl can
pass while leaving application routes undiscovered. Anonymous runs do not prove
authentication. Authenticated runs prove the stated identity and credential-bearing
verification request during the crawl, rather than coverage of every protected
route or successful reauthentication on expiry.

The harness attempts guided cancellation only for its own returned operation
handles and records cleanup errors. Replay handles for the same backend job are
cleaned up together. Inspect the dedicated ZAP instance before reusing it after
unconfirmed cleanup. The harness does not reset sessions, stop arbitrary native
IDs or cancel unrelated work. Authentication expiry, server restart/recovery,
multiple replicas, missing add-ons and browser failures require separate scenarios.

Offline parser and behavioral fixtures need no servers:

```sh
python3 -m unittest discover -s tests/live -p 'test_*.py'
```
