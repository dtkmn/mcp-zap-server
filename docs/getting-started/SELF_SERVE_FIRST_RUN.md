# Self-Serve First Run

Use this path to start the server, connect your own MCP client, and produce a
findings summary and report from a bundled demo target.

This setup uses:

- API-key auth
- an MCP client with Streamable HTTP and custom-header support
- local Docker Compose for the default quick-start runtime

## 1. Bootstrap Local Settings

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server
./bin/bootstrap-local.sh
```

This creates `.env`, generates `MCP_API_KEY`, prepares the ZAP workspace, and
enables localhost/private-network scanning so the bundled demo targets work.

## 2. Start The Stack

```bash
./dev.sh
```

The local stack provides the server and demo targets. Connect your own MCP
client in step 4; no browser chat UI is bundled.

Default local services:

- MCP server: `http://localhost:7456/mcp`
- host preview for Juice Shop: `http://localhost:3001`
- host preview for Petstore: `http://localhost:3002`

Important: when ZAP scans the bundled demo targets from inside Compose, use the
container-reachable URLs:

- `http://juice-shop:3000`
- `http://petstore:8080`

Do not tell the MCP tools to scan `http://localhost:3001` or
`http://localhost:3002`. Those host mappings are for your browser, not for the
scanner running inside Docker.

## 3. Run The Doctor

```bash
./bin/self-serve-doctor.sh
```

This checks Docker, the local stack, API-key auth, MCP initialize,
`tools/list`, the guided scan/report/evidence tool surface, and one harmless
tool call.

## 4. Connect A Client

### Cursor

- Start from [examples/cursor/mcp.json](../../examples/cursor/mcp.json).
- Add the example server entry to `mcpServers` in `~/.cursor/mcp.json`.
  Preserve any existing server entries.
- If Cursor inherits your shell environment, `${env:MCP_API_KEY}` is fine.
- If GUI-launched Cursor does not inherit that environment, paste the actual
  `MCP_API_KEY` value from `.env` into the `X-API-Key` header and restart
  Cursor.
- Point Cursor at `http://localhost:7456/mcp`.

### Other MCP Clients

For client compatibility, setup details, and troubleshooting, use the
[MCP Client Setup guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/).
Other clients need Streamable HTTP support and the ability to send `X-API-Key`
to `http://localhost:7456/mcp`.

### Confirm The Connection

Ask your client:

```text
List the available ZAP tools and explain the guided crawl-to-report workflow.
```

Expected result: the client can discover tools including `zap_crawl_start`,
`zap_passive_scan_wait`, `zap_findings_summary`, and `zap_report_generate`.
Resolve connection or authentication errors before starting a scan.

## 5. Decide Whether The Target Needs Login

Most first runs should skip target authentication. The bundled Juice Shop and
Petstore examples can be scanned without an auth profile.

If your authorized target requires a traditional username/password HTML form,
use the
[Form-Login Target Authentication guide](https://danieltse.org/mcp-zap-server/getting-started/form-login-target-authentication/).
That optional setup is separate from the API key or JWT used by Cursor. Never
put the website password in Cursor or an MCP prompt.

## 6. Use The Guided Happy Path

For the full target-to-evidence workflow, use the
[MCP Client Scan-To-Evidence Guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/).

For your first scan, ask:

```text
Run only a guided crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish, summarize findings for this target,
generate an HTML report, and read the report back through MCP.
Do not start an active scan.
```

Expected result: a completed crawl, drained passive analysis, a target-scoped
findings summary, and a generated HTML report that the client can read through
MCP. Finding counts vary; a successful setup is demonstrated by completed tool
calls and report readback, not a specific number of alerts.

The full scan-to-evidence guide also covers optional active scanning, release
evidence, and customer handoff after this first report works.

## 7. If It Breaks

Use this order:

1. `./bin/self-serve-doctor.sh`
2. `docker compose logs -f mcp-server`
3. `docker compose logs -f zap`

If you are still stuck, include the failing step, client version, and relevant
error output in a GitHub issue. Remove API keys and other secrets before sharing.
