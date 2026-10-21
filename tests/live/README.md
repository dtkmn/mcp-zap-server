# Client Spider live regression checks

`client_spider_juice_shop.py` uses only Python 3's standard library. It drives the
real MCP Streamable HTTP endpoint, real ZAP Client Spider, and the supplied OWASP
Juice Shop target. It does not start an active attack scan.

Run against a dedicated local test stack. Existing ZAP traffic makes coverage
attribution ambiguous, and browser crawls can interact with the target. The
script checks that both management endpoints use localhost; the target URL must
be reachable from **ZAP's container/browser**, usually `http://juice-shop:3000`.

## Lab prerequisites

- Build the source version that includes Client Spider; release v0.12.0 does not.
- ZAP with Client Side Integration 0.27.0+, Selenium, Firefox and its driver.
- A dedicated ZAP session and local Juice Shop on the same Docker network.
- MCP API credentials with crawl, read, stop and queue permissions.
- `ZAP_MAX_SPIDER_SCAN_DURATION=1` for bounded completion within the default
  180-second lifecycle timeout. The harness never changes ZAP duration settings.
- `ZAP_MAX_CONCURRENT_SPIDER_SCANS=1` for the expert queue phase: it verifies
  cancellation of a job waiting behind a running crawl. Direct crawl admission
  must allow at least two concurrent operations for the isolation phase.
- Allow the local Juice Shop origin in the server's URL validation configuration.
- Configure the intended tool surface before starting the MCP server.

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
  --evidence /tmp/client-spider-expert.json
```

The default expert phases run sequentially:

1. MCP initialize/session negotiation and tool discovery, including input schemas.
2. Rejection of negative depth, unpaired context/user names, blank scan IDs and
   unknown scan IDs for both status and stop.
3. Two distinct direct crawl IDs; stopping the first leaves the second running;
   each scan can be stopped independently.
4. Queued dispatch and native ID tracking; idempotent replay and conflicting
   replay rejection; pending-job cancellation; running-job cancellation verified
   at the native ZAP API.
5. Bounded crawl termination without the harness requesting a stop; passive
   backlog drain; new successful Juice Shop product-search API traffic and
   multiple discovered resources.

Select phases when investigating a failure or when the lab has different queue
concurrency settings:

```sh
python3 tests/live/client_spider_juice_shop.py --phases validation
python3 tests/live/client_spider_juice_shop.py --phases direct,completion \
  --timeout 240 --evidence /tmp/client-spider-direct.json
python3 tests/live/client_spider_juice_shop.py --phases queue \
  --evidence /tmp/client-spider-queue.json
```

For a server using the guided surface:

```sh
python3 tests/live/client_spider_juice_shop.py \
  --mcp-url http://127.0.0.1:17457/mcp --mode guided \
  --expected-execution-mode direct \
  --evidence /tmp/client-spider-guided-direct.json
```

Use `--expected-execution-mode queue` against a guided server configured for
queued execution. Guided mode checks expert-tool hiding, invalid operation IDs,
Client Spider selection, status/stop, bounded completion, passive waiting and
actual JavaScript API requests. Guided completion may end by the configured
native duration limit.

For authenticated guided crawling, first configure an operator-managed browser
profile and a disposable Juice Shop account. Pass the profile ID, its verification
URL, and the expected account email together:

```sh
python3 tests/live/client_spider_juice_shop.py \
  --mcp-url http://127.0.0.1:17457/mcp --mode guided \
  --expected-execution-mode direct \
  --auth-profile juice-browser-live \
  --auth-verification-url http://juice-shop:3000/rest/user/whoami \
  --auth-expected-user client-spider-test@example.test \
  --evidence /tmp/client-spider-authenticated-direct.json
```

The harness prepares a session against the verification URL, requires `Valid: true`
from session validation, and binds the returned `authSessionId` to every guided
crawl. The crawl starts at `--target` on the same authorized origin; verification
uses the protected endpoint rather than the public SPA HTML. Completion snapshots the message baseline
**after** validation, then requires a fresh HTTP 2xx verification response
containing the expected identity and a request bearing an Authorization header
or a token/session cookie. Validation traffic alone cannot pass this assertion.
Use the same options with a guided queue server to check queued authentication.

## Evidence and interpretation

The script exits nonzero on the first failed assertion and saves JSON evidence
even after failure. Evidence includes tool schemas, every MCP request/response,
installed add-ons, native IDs, lifecycle assertions and fresh API message
summaries. API keys and session headers are not recorded. Juice Shop response
body previews are recorded for anonymous tests, so use only the disposable lab.
Authenticated runs omit API body previews and record only credential-presence
booleans, never Authorization or cookie values.

ZAP reports 100% for both stopped and completed Client Spider crawls. A 100%
status therefore passes **only a lifecycle assertion**. The separate discovery
check requires fresh message IDs from the bounded crawl, successful HTTP 2xx
responses from `/rest/products/search`, and at least five distinct resource paths
in those fresh requests. Earlier crawl URLs and repeated query variations do not
count toward that resource threshold.
It does not claim exhaustive coverage, successful
interaction with every page, or absence of crawl errors. A duration-limited crawl
can pass these checks while leaving application routes undiscovered.
Anonymous runs do not prove authentication. Authenticated runs prove the stated
identity and credential-bearing verification request during the crawl, rather
than coverage of every protected route or successful reauthentication on expiry.

The harness attempts to stop/cancel its own tracked operations on failure and
records cleanup errors; inspect the dedicated ZAP instance before reusing it.
It does not reset sessions or cancel unrelated work. Tests for authentication
expiry, server restart/recovery, multiple replicas, missing add-ons and browser failures
require separate scenarios.

Parser checks can run without any servers:

```sh
python3 -m unittest discover -s tests/live -p 'test_*.py'
```
