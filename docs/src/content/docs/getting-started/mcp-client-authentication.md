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

For agent-assisted Docker setup, the same guide covers
[MCP ZAP Connect in Codex](../self-serve-first-run/#guided-setup-in-codex).
Install the plugin locally, then ask its setup skill to start or reuse the
Docker stack and configure your private Codex connection. It checks status
without starting a scan, so you normally do not need to edit client settings
yourself. The skill can help
install missing prerequisites with your approval, then recheck and continue setup.
The manual configuration recipes below remain available without the plugin.

Claude Code also has a [local plugin preview](../self-serve-first-run/#guided-setup-in-claude-code)
that you can load from your checkout.

## Client Compatibility

Choose the setup route for your client. Local clients must be able to reach the
scanner's `/mcp` endpoint; cloud-hosted clients need a reachable remote endpoint.

| Client / connection | Setup | Requirements |
| --- | --- | --- |
| Codex app or CLI | [Guided setup](../self-serve-first-run/#guided-setup-in-codex) or [manual HTTP configuration](#codex) | Local scanner access and `X-API-Key`; model access follows your Codex plan. |
| Cursor Desktop | [Guided setup](../self-serve-first-run/#guided-setup-in-cursor) or [manual HTTP configuration](#cursor) | Local scanner access, `X-API-Key`, and available Agent usage on Free or paid accounts. |
| Claude Code | [Local plugin preview](../self-serve-first-run/#guided-setup-in-claude-code) or [manual HTTP configuration](#claude-code) | Local scanner access, `X-API-Key`, and access to Claude Code. |
| Claude Desktop | [Local MCPB extension](#claude-desktop), macOS preview | Docker and Claude Desktop, including Free accounts; no Claude Code subscription required. |
| ChatGPT | [Secure MCP Tunnel guide](../chatgpt-local-tunnel/) | Platform tunnel permissions and ChatGPT developer-mode access. A cloud client cannot connect directly to this machine's loopback endpoint. |
| Open WebUI | [Native Streamable HTTP connection](#open-webui) | Separate Open WebUI installation with custom-header support and network access to the scanner. |
| Other Streamable HTTP clients | [Generic connection values](#generic-streamable-http-clients) | MCP initialization, session handling and the configured authentication header. |
| Claude chat remote connectors | Remote endpoint required | Cannot connect directly to this machine's `localhost` endpoint; use the local Desktop extension or Claude Code for a local scanner. |
| Clients limited to stdio or legacy SSE | Compatible adapter required | The scanner endpoint uses Streamable HTTP. The Claude Desktop MCPB provides an adapter specifically for that client. |

For a compatibility report, include the client version, operating system,
connection mode, and whether both tool discovery and the first scan below
succeeded. Remove credentials before sharing configuration or logs.

## Codex

First start either the Compose stack using [Self-Serve First Run](../self-serve-first-run/)
and `./bin/self-serve-doctor.sh`, or the [Mac preview](../macos-package/)
using `./bin/mcp-zap start` and `./bin/mcp-zap doctor`. This recipe uses
a local Codex app or CLI on the same machine as the server. For a Codex
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
key in the Compose server's `.env` or the Mac package's private `credentials.env`;
Codex does not automatically load those files.
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

The Mac preview can generate the static-header TOML entry with
`./bin/mcp-zap client-config codex`. Merge its output only into your private
configuration; it contains the key and already uses the configured local port.

After restarting the client, ask in a local task:

```text
List the available ZAP tools from mcp-zap-server.
```

Expected result: tools such as `zap_crawl_start`, `zap_crawl_status`,
`zap_findings_summary`, and `zap_report_generate` are available without an
authentication error. Then run the [first scan below](#first-scan-and-expected-result).
If you receive `401`, check the key and whether the Codex process received
the environment variable. If the connection is refused, check the stack and
the `/mcp` address. For the native Mac package, see the
[Mac guide](../macos-package/).
If Codex asks for tool approval, approve only your authorized target and workflow.
A client-side denied approval does not indicate an MCP authentication failure.

## Cursor

Cursor supports streamable HTTP connections with custom headers. Its
[MCP documentation](https://cursor.com/docs/mcp) describes configuration
locations and environment-variable interpolation.

For agent-guided setup, follow [Guided Setup in Cursor](../self-serve-first-run/#guided-setup-in-cursor).
The following manual configuration is also available.

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

Enable `mcp-zap-server` in Cursor's MCP settings and start an Agent chat. Verify
the connection without scanning:

```text
Call zap_passive_scan_status through mcp-zap-server only. Do not run a scan.
```

Expected result: the server returns passive completion, remaining records and
active tasks without an authentication error.

Cursor Free accounts need available Agent usage to call MCP tools. A connected
local scanner does not bypass model usage limits. If Cursor reports a usage
limit before calling the tool, check your account's allowance before retrying.

After a Docker restart, Cursor may retain a disconnected MCP session even when
the server doctor passes. In **Customize → MCPs**, open **mcp-zap-server** and
select **Reload**, then repeat the status call. If tools remain unavailable,
use **Developer: Reload Window** and check again. When you are ready to scan the
bundled demo, make a separate request using the
[first scan below](#first-scan-and-expected-result).

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
OAuth login flow.

The API key or bearer token in `mcp.json` is only the MCP access credential.
Never add the target website username or password to this file.

## Claude Code

Use local Claude Code on the same machine as the Docker scanner. Claude Code
supports HTTP MCP servers with custom headers; see Anthropic's
[MCP documentation](https://code.claude.com/docs/en/mcp). This is separate from a
Claude chat remote connector, whose remote execution cannot reach your local
`127.0.0.1` endpoint.

First complete [Self-Serve First Run](../self-serve-first-run/), including
authenticated readiness. For agent-guided setup, use the
[local plugin preview](../self-serve-first-run/#guided-setup-in-claude-code).
Preserve Codex, Cursor and other client settings.

For manual **user-scope** configuration, merge this entry into the top-level
`mcpServers` object in your private `~/.claude.json`. Do not replace the whole
file: it can also contain sign-in state and other server entries.
If `CLAUDE_CONFIG_DIR` selects another profile, use that profile's active global
configuration as identified by your installed Claude Code; do not create a
second default profile. Anthropic documents the override in its
[environment variable reference](https://code.claude.com/docs/en/env-vars) but
does not specify the relocated global JSON filename there.

```json
{
  "mcpServers": {
    "mcp-zap-server": {
      "type": "http",
      "url": "http://127.0.0.1:7456/mcp",
      "headers": {
        "X-API-Key": "${MCP_API_KEY}"
      }
    }
  }
}
```

Claude Code requires `"type": "http"` for this URL. `${MCP_API_KEY}` is Claude
Code's environment reference syntax; the process must inherit the effective
scanner key before it starts. An HTTP entry does not automatically load the
scanner's `.env`, and `envFile` is not part of this recipe. If that environment
has not been established, use the actual key only in your private user
configuration. An agent must read and transform it inside a local operation
without printing it, sourcing `.env` as shell code, or passing the key in command
arguments. Never put it in a prompt or a shared `.mcp.json` file. API-key mode
does not need an OAuth login.

Reuse an existing scanner alias for the same endpoint, treating `localhost` and
`127.0.0.1` as equivalent. Preserve its disabled state, tool restrictions and
approval settings; do not add broad permissions. Check for a project `.mcp.json`
or a private local-scope entry under `projects["/absolute/project/path"].mcpServers`
in the global config before editing. These can override a user-scope entry.
Resolve conflicting endpoints or shadows first; do not automatically change
project scope. User scope applies across your projects, while local scope stays
private to one project in the same global file. See
[MCP scopes and precedence](https://code.claude.com/docs/en/mcp#mcp-installation-scopes).

Before changing private settings, make a private backup, reject linked paths,
restrict files containing secrets to the current user, and validate the complete
JSON before replacement. Preserve unrelated servers, headers and credentials;
remove conflicting Authorization credentials only from this scanner entry.

Start a new local Claude Code session, inspect `/mcp`, and verify through the
scanner alias just configured:

```text
Call zap_passive_scan_status through mcp-zap-server only. Do not run a scan.
```

Expected result: passive completion, remaining records and active tasks without
an authentication error. Make the status request through Claude Code's own MCP
connection. Request a scan separately after establishing the connection.

## First Scan And Expected Result

With the local Compose demo stack running, ask your connected client:

```text
Run only a guided HTTP crawl against http://juice-shop:3000.
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
and an `X-API-Key` header using the values above.

If Open WebUI runs in Docker, `localhost` refers to its own container. Use a
host address or shared-network service name reachable from that container;
`mcp-server` only resolves when both services share the appropriate Docker
network. Validate with tool discovery and the first-scan prompt above.

## Claude Desktop

Follow the [Claude Desktop download, setup and first-scan guide](https://github.com/dtkmn/mcp-zap-server/blob/main/packaging/claude-desktop/SETUP.md)
for the macOS MCPB preview. Choose a
[scanner release](https://github.com/dtkmn/mcp-zap-server/releases) whose assets
include the `.mcpb`, matching guide and both checksums. MCPB assets belong to
scanner releases; the connector's filename version is independent of the scanner
tag. If a release has no MCPB assets, use the package README's local build
instructions.
The bundle uses a pinned scanner image for fresh installations; check the setup
guide for its default rather than assuming it follows the containing release.
The [package README](https://github.com/dtkmn/mcp-zap-server/tree/main/packaging/claude-desktop)
contains the local developer build and normal-release draft procedure.
It packages a local MCP server with agent-callable prerequisite, setup and
readiness tools. These tools reuse `bootstrap-local.sh` and
`self-serve-doctor.sh`; the official MCP SDK connects to the existing scanner's
Streamable HTTP endpoint. It does not require a Claude Code subscription.
Anthropic documents local Desktop extensions for all users, including Free
accounts. Claude's account usage limits still apply when calling tools.

For a downloaded or locally built bundle:

1. In Claude Desktop, open **Settings → Extensions → Advanced settings →
   Install Extension…** and select the `.mcpb` file.
2. Leave the optional scanner directory and key override blank. The extension
   reuses an existing scanner and reads its credential privately, or uses
   `~/.mcp-zap-server` for a new installation.
3. Ask Claude to set up the local scanner. It checks prerequisites and requests
   approval before setup changes. Missing Docker and OS prompts need your
   interaction. Installing the extension alone does not start the scanner.

For an already-running scanner, request only:

```text
Call zap_passive_scan_status through MCP ZAP Connect once with empty arguments.
Do not call setup, crawl, scan, passive-wait or report tools. Then stop.
```

The extension preserves Codex and Cursor settings and does not store scanner
state in its installed bundle. New installations use a private stable directory;
existing healthy installations are reused. Customized startup files require
manual review rather than being overwritten. See the package README for build,
verification, removal and failure handling.

A fresh installation generates new credentials. If it replaces an older
scanner, preserved Codex/Cursor settings may still contain the old key and will
not authenticate to the new scanner until separately reconnected. Update only
the intended client's private scanner entry when reconnecting it.

If tools are missing or a call times out without showing an approval prompt,
fully quit Claude Desktop, reopen it and try one status-only call in a new chat.
Restarting is a troubleshooting step, not a required installation step. Do not
widen tool permissions to bypass a missing prompt.

This is a local Desktop connection. Remote web connectors originate from
Anthropic's infrastructure and cannot reach this machine's loopback endpoint.
See the [Desktop extension instructions](https://support.claude.com/en/articles/10949351-getting-started-with-local-mcp-servers-on-claude-desktop)
and [remote connector network boundary](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp).

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
