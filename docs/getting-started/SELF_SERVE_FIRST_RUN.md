# Self-Serve First Run

Use this path when you want the fastest honest route from clone to a real
findings summary with a supported API-key MCP client.

The supported self-serve contract for this cycle is:

- API-key auth
- a client that can send custom headers to a remote MCP server
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

Default local services:

- MCP server: `http://localhost:7456/mcp`
- Open WebUI: `http://localhost:3000`
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
- Validated local user-wide config path: `~/.cursor/mcp.json`.
- If Cursor inherits your shell environment, `${env:MCP_API_KEY}` is fine.
- If GUI-launched Cursor does not inherit that environment, paste the actual
  `MCP_API_KEY` value from `.env` into the `X-API-Key` header and restart
  Cursor.
- Point Cursor at `http://localhost:7456/mcp`.

### Open WebUI

- Start the stack with `./dev.sh`.
- Open `http://localhost:3000`.
- The default Compose stack wires Open WebUI to the local MCP server with the
  generated API key.

## 5. Use The Guided Happy Path

For the full target-to-evidence workflow, use the
[MCP Client Scan-To-Evidence Guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/).

Use prompts like these:

1. `List the available ZAP tools and explain the safest first scan path.`
2. `Start a spider scan on http://juice-shop:3000.`
3. `Wait for passive analysis to finish and give me a findings summary for http://juice-shop:3000.`
4. `Generate an HTML report for http://juice-shop:3000 and read it back through MCP.`
5. `Create release evidence and a customer-safe handoff for http://juice-shop:3000.`

That is enough to prove the end-to-end self-serve path without dragging a new
user through every advanced feature in the repo.

## 6. If It Breaks

Use this order:

1. `./bin/self-serve-doctor.sh`
2. `docker compose logs -f mcp-server`
3. `docker compose logs -f zap`

If you are still stuck after that, the failure is probably real and worth
fixing in the product surface, not just papering over in docs.
