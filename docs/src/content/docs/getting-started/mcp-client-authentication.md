---
title: "MCP Client Authentication"
editUrl: false
description: "Configure MCP clients for API-key or bearer-token access to the streamable HTTP endpoint."
---
Use your own MCP client to connect to MCP ZAP Server. The repository provides
the server and client configuration examples; it does not bundle Open WebUI or
another chat interface.

This page authenticates the client to MCP ZAP Server. It does not authenticate
ZAP to the website being scanned. If the target itself requires a
username/password form, finish the client connection here first, then use
[Form-Login Target Authentication](../form-login-target-authentication/).

This server exposes a streamable HTTP MCP endpoint at:

```text
http://localhost:7456/mcp
```

The server supports:

- API key authentication via `X-API-Key`
- bearer tokens via `Authorization: Bearer ...`
- JWT minting and refresh on the server side

The practical truth: API-key mode is still the easiest self-serve client setup. Not every MCP desktop client handles remote HTTP transport, custom auth headers, or JWT refresh the same way.

If you are starting from a fresh clone, use [Self-Serve First Run](../self-serve-first-run/) before tuning client-specific details here.

## Client Compatibility

Local API-key setup is documented below for Codex and Cursor. The statuses
describe this repository's onboarding coverage, not every capability a client
may support.

| Client / connection | Status | Scope |
| --- | --- | --- |
| Local Codex app or CLI with `X-API-Key` | Documented setup, end-to-end unverified here | Uses `~/.codex/config.toml` and Codex's documented Streamable HTTP header support. Verify discovery and the first scan below. |
| Local Cursor with `X-API-Key` | Previously validated setup | Previously documented local API-key setup at `~/.cursor/mcp.json`; client version and test date were not recorded. |
| Other streamable HTTP clients with custom headers | Expected to work, conditional | Must reach `/mcp`, send the configured auth header, and manage MCP sessions. Use the client's own configuration format and verify tool calls. |
| Independently installed Open WebUI | Expected to work, unverified here | Its documented native MCP integration supports streamable HTTP and custom headers. Install and configure it separately. |
| Claude Desktop with this guide's local `localhost` setup | Unsupported onboarding path | Remote connectors run from Anthropic's infrastructure. The repository does not supply a local desktop bridge or a tested Claude connector setup. |
| Clients limited to stdio, legacy SSE, or no compatible authentication | Unsupported direct connection | This server's documented endpoint uses streamable HTTP with API-key or JWT authentication. No first-party transport/auth proxy is included. |

For a compatibility report, include the client version, operating system,
connection mode, and whether both tool discovery and the first scan below
succeeded. Remove credentials before sharing configuration or logs.

## Codex

First complete [Self-Serve First Run](../self-serve-first-run/), including
`./bin/self-serve-doctor.sh`, with the server in API-key mode. This recipe uses
a local Codex app or CLI on the same machine as the Docker stack. For a Codex
host on another machine, replace `localhost` with an address reachable from
that host.

Codex supports Streamable HTTP and custom headers. Its
[official MCP guide](https://learn.chatgpt.com/docs/extend/mcp#streamable-http-servers)
documents the shared configuration and authentication options.

Merge this entry into `~/.codex/config.toml`, preserving existing settings and
server entries. If `mcp-zap-server` already exists, update that entry instead
of adding a duplicate:

```toml
[mcp_servers.mcp-zap-server]
url = "http://localhost:7456/mcp"
env_http_headers = { "X-API-Key" = "MCP_API_KEY" }
```

`MCP_API_KEY` here is the environment variable name. Its value must match the
key in the server's `.env`; Codex does not automatically load that file.
The key authenticates Codex to MCP ZAP Server, not ZAP to the target website.

For the CLI, enter the key at a hidden prompt in the same terminal before
starting Codex (Bash or Zsh on macOS/Linux):

```bash
printf 'MCP API key: ' >&2
IFS= read -r -s MCP_API_KEY
printf '\n' >&2
export MCP_API_KEY
codex
```

For the desktop app, the variable must be available when the app starts.
Exporting it in a terminal inside an already-running app does not update the
app's environment. If the app does not inherit the variable, remove
`env_http_headers` from the server entry above and use this line in the same
table instead:

```toml
http_headers = { "X-API-Key" = "REPLACE_WITH_YOUR_MCP_API_KEY" }
```

Replace the placeholder only in your private user configuration, then restart
the app. Do not commit a file containing the key or paste it into a prompt.
This API-key setup does not use `codex mcp login`, which starts an OAuth flow.

After restarting the client, ask in a local task:

```text
List the available ZAP tools from mcp-zap-server.
```

Expected result: tools such as `zap_crawl_start`, `zap_crawl_status`,
`zap_findings_summary`, and `zap_report_generate` are available without an
authentication error. Then run the [first scan below](#first-scan-and-expected-result).
If you receive `401`, check the key and whether the Codex process received
the environment variable. If the connection is refused, check the stack and
the `/mcp` address. This configuration follows Codex's documented support;
a complete Codex-to-ZAP scan has not yet been validated for this repository.

## Cursor

Cursor supports streamable HTTP connections with custom headers. Its
[MCP documentation](https://cursor.com/docs/mcp) describes configuration
locations and environment-variable interpolation.

First complete [Self-Serve First Run](../self-serve-first-run/), including
`./bin/self-serve-doctor.sh`. Then add the following server entry to
`~/.cursor/mcp.json`, merging it with any existing `mcpServers` entries.
The repository also provides a
[Cursor example](https://github.com/dtkmn/mcp-zap-server/blob/main/examples/cursor/mcp.json).

Use API-key mode for the first connection:

```json
{
  "mcpServers": {
    "mcp-zap-server": {
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "${env:MCP_API_KEY}"
      }
    }
  }
}
```

Make the generated `MCP_API_KEY` from the server's `.env` available to the
Cursor process. Cursor does not automatically load that file for an HTTP
server. If GUI-launched Cursor does not inherit the variable, replace
`${env:MCP_API_KEY}` with the actual key in your private user-wide config,
then restart Cursor. Do not commit a config containing a key.

For project-specific configuration, Cursor also supports `.cursor/mcp.json`.
Keep environment references in any shared file.

Enable `mcp-zap-server` in Cursor's MCP settings and start an Agent chat. Ask:

```text
List the available ZAP tools from mcp-zap-server.
```

Expected result: Cursor discovers tools including `zap_crawl_start`,
`zap_crawl_status`, `zap_findings_summary`, and `zap_report_generate`, without
an authentication error. Then run the [first scan below](#first-scan-and-expected-result).

If you deliberately configure JWT mode on the server, replace the API-key
header with a pre-issued token:

```json
{
  "mcpServers": {
    "mcp-zap-server": {
      "url": "http://localhost:7456/mcp",
      "headers": {
        "Authorization": "Bearer ${env:MCP_BEARER_TOKEN}"
      }
    }
  }
}
```

Token issuance and refresh must be managed separately; this example is not an
OAuth login flow. The recorded Cursor baseline covers API-key setup.

The API key or bearer token in `mcp.json` is only the MCP access credential.
Never add the target website username or password to this file.

## First Scan And Expected Result

With the local Compose demo stack running, ask your connected client:

```text
Run only a guided crawl against http://juice-shop:3000.
Poll until the crawl completes, wait for passive analysis, and summarize
findings for that target. Generate an HTML report scoped to that target and
read it back through MCP. Follow the tools' Next Actions. Do not start an
active scan.
```

Expected result: a completed crawl, passive analysis finished, a target-scoped
findings summary, and an HTML report path with report content returned through
MCP. Finding counts vary; a tool list alone does not prove the scan worked.
If any operation fails or times out, resolve it before treating the report as
complete.

ZAP runs inside Docker, so `http://juice-shop:3000` is the scan target.
`http://localhost:3001` is only the browser preview on your host.
For your own authorized target, substitute a URL reachable from ZAP.

Continue with the [MCP Client Scan-To-Evidence Guide](../../scanning/mcp-client-scan-to-evidence/)
for optional active scanning, release evidence, and handoff.

## Generic Streamable HTTP Clients

Use your client's native configuration format with these connection values;
there is no universal MCP client JSON schema:

| Setting | Value |
| --- | --- |
| Transport | Streamable HTTP |
| Endpoint | `http://localhost:7456/mcp` when the client runs on the same host |
| API-key header | `X-API-Key: <MCP_API_KEY from the server configuration>` |
| JWT alternative | `Authorization: Bearer <pre-issued access token>` when the server uses JWT mode |

The client must support MCP initialization, session handling, and sending the
auth header on subsequent requests. If it runs in a container or on another
machine, use an MCP server address reachable from there instead of `localhost`.
Verify tool discovery, then use the first-scan prompt above before treating
the integration as working.

## Open WebUI

Open WebUI is an optional, separately installed client. MCP ZAP Server does
not start it, publish a UI at port 3000, or configure its connections.

Open WebUI's [native MCP guide](https://docs.openwebui.com/features/extensibility/mcp/)
documents an administrator-managed **MCP (Streamable HTTP)** connection with
custom headers. In your own installation, add the reachable `/mcp` endpoint
and an `X-API-Key` header using the values above. This integration has not
been verified here.

If Open WebUI runs in Docker, `localhost` refers to its own container. Use a
host address or shared-network service name reachable from that container;
`mcp-server` only resolves when both services share the appropriate Docker
network. Validate with tool discovery and the first-scan prompt above.

## Claude Desktop

The local setup in this guide is not a supported Claude Desktop onboarding
path. Claude's remote connectors connect from Anthropic's infrastructure, so
they cannot reach this server at your machine's `localhost`. Local desktop
servers use a separate mechanism; this repository does not provide a desktop
extension or bridge. See Anthropic's
[remote connector guidance](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

This does not mean Claude can never connect. Anthropic documents
[organization-admin request-header authentication in beta](https://claude.com/docs/connectors/building/authentication),
including API keys and bearer tokens. A suitably reachable deployment and
compatible account configuration may work, but that path has not been
validated for this repository. The server's JWT support alone does not
provide an OAuth connector flow. Use Cursor for the documented local path.

## JWT Guidance

JWT is supported by the server, but desktop-client ergonomics depend on the client:

- a streamable HTTP client can use a pre-issued bearer token if it can send the required header and manage MCP sessions
- if the client cannot mint or refresh tokens, you still need another system to manage token lifecycle
- this repository does not currently ship token-refresh helper scripts for desktop clients

API-key mode remains the recommended self-serve option for local Compose and
clients that support custom headers.

For server-side JWT setup, see [JWT Setup](../../security-modes/jwt-authentication/).

## Test The Endpoint Manually

API-key example:

```bash
SESSION_ID=$(curl -si \
  -H "X-API-Key: your-mcp-api-key" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}' \
  | awk -F': ' '/Mcp-Session-Id/ {print $2}' | tr -d '\r')

curl -H "X-API-Key: your-mcp-api-key" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

JWT example:

```bash
TOKEN_RESPONSE=$(curl -s -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-mcp-api-key","clientId":"default-client"}')

ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.accessToken')

SESSION_ID=$(curl -si \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}' \
  | awk -F': ' '/Mcp-Session-Id/ {print $2}' | tr -d '\r')

curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## Troubleshooting

### 401 Unauthorized

- verify the server is in the auth mode you expect
- verify the client is sending `X-API-Key` or `Authorization`
- verify the MCP server URL includes `/mcp`

### Client Connects But Tools Fail

- initialize the MCP session first if you are testing manually
- reuse the returned `Mcp-Session-Id` header on later requests

### Cursor Connects But Target Login Fails

That is a separate ZAP-to-target authentication problem. Keep the Cursor API
key or JWT unchanged and follow
[Form-Login Target Authentication](../form-login-target-authentication/).

### Claude Desktop Setup Feels Inconsistent

Use the [Claude Desktop compatibility notes](#claude-desktop) above to check
network reachability and authentication. Copying Cursor's JSON into Claude's
local desktop configuration does not establish a supported connection.
