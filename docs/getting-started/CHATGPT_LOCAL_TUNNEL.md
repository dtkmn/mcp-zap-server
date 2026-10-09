# Connect the local Mac preview to ChatGPT

This guide connects the packaged MCP ZAP Server to a private ChatGPT custom MCP
plugin through OpenAI Secure MCP Tunnel. The server and ZAP stay on your Mac;
the tunnel client opens an outbound HTTPS connection to OpenAI. The package
keeps API-key authentication enabled and supplies the local `X-API-Key` header
through an environment reference.

This private connection requires internet access, an OpenAI Platform tunnel,
and compatible account permissions. It does not provide a public plugin listing
or offline ChatGPT use. This walkthrough covers HTTP crawling, passive analysis,
and HTML report generation and retrieval for authorized targets.

## Prerequisites

- Start the intact Mac package with `./bin/mcp-zap start`. Startup checks
  authenticated readiness; keep the bundled services running.
- Install the full official OpenAI client with
  `brew install openai/tools/tunnel-client`, then check `tunnel-client --version`
  and `tunnel-client help quickstart`. The runtime-only binaries do not include
  profile initialization or the doctor. Follow the vendor's current macOS
  installation guidance; do not bypass Gatekeeper for a downloaded archive.
- In [Platform tunnel settings](https://platform.openai.com/settings/organization/tunnels),
  select an existing tunnel associated with your Platform organization and
  target ChatGPT workspace. Obtain its complete `tunnel_` ID. This helper does
  not create tunnels, API keys, permissions, or plugins.
- The OpenAI runtime key and its principal need Tunnels **Read + Use** for that
  tunnel. Creating or managing a tunnel separately requires **Read + Manage**.
  These permissions apply to the Platform organization. An API key being
  present, or having project access, does not establish them.
- Explicitly select the tunnel's associated Platform organization in the
  launching environment with `CONTROL_PLANE_ORGANIZATION_ID`. The official
  client sends this as `OpenAI-Organization`; selecting an organization in the
  browser does not configure the running client. Check inherited organization
  settings before starting. Keep the actual identifier in private configuration.
- Make the existing `OPENAI_API_KEY` available in the shell that launches the
  helper, or provide a dedicated runtime key as `CONTROL_PLANE_API_KEY`.
  `CONTROL_PLANE_API_KEY` takes precedence. Keep key values out of commands,
  prompts, screenshots, and shared logs. The helper saves environment references,
  not key values. Do not use an admin key for the running tunnel client.
- Your ChatGPT workspace must allow adding and using custom MCP servers. The
  Mac needs outbound HTTPS to OpenAI and local access to its packaged `/mcp`
  endpoint.

OpenAI documents the [tunnel permission and workspace requirements](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels#permissions-and-access)
separately from ChatGPT access. If no tunnel is available, sign in to Platform
tunnel settings and resolve the existing tunnel's access and workspace
association with its owner before proceeding.

## Initialize and run

From the extracted package directory, replace only the example tunnel ID:

```sh
./bin/mcp-zap tunnel init tunnel_0123456789abcdef0123456789abcdef
./bin/mcp-zap tunnel run
```

The helper stores `chatgpt-local.yaml` under `tunnel/` in the application's data
directory. The default data directory is `~/Library/Application Support/MCP ZAP
Server`; `MCP_ZAP_DATA_DIR` selects another absolute directory. Existing profiles
are preserved. To deliberately replace one, stop its running client, then use:

```sh
./bin/mcp-zap tunnel init tunnel_0123456789abcdef0123456789abcdef --replace
```

`run` stays in the foreground. Keep that terminal running during discovery and
every ChatGPT tool call. Its health/admin listener uses a loopback address and an
available port; `tunnel/health-url` records the running listener's base URL.
Check `/readyz` at that URL before connecting ChatGPT. A leftover URL file does
not establish that a client is still running.

The helper always selects the package's current `http://127.0.0.1:PORT/mcp`
endpoint and injects `X-API-Key: env:MCP_API_KEY` for MCP traffic and startup
discovery probes. The launcher supplies the local key from its private
`credentials.env`. The OpenAI runtime key authenticates the outbound tunnel
client, and the local MCP key authenticates its connection to the server.

The optional `./bin/mcp-zap tunnel doctor` invokes the vendor's diagnostic check.
It does not verify OpenAI tunnel permissions or a ChatGPT connection. In client
`0.0.16`, its HTTP/OAuth diagnostic probes do not apply configured MCP headers,
so an API-key-protected
server can produce an OAuth metadata failure even with the correct runtime
configuration. Keep local API-key authentication enabled. Use the package
doctor for local MCP diagnostics, then check the running tunnel's readiness
and make a harmless status call in ChatGPT.

## Add the private connection in ChatGPT

1. Open [ChatGPT Plugins](https://chatgpt.com/plugins), select the plus button,
   then **Add custom MCP server**.
2. Enter a name and description. Choose **Tunnel** under **Connection**, then
   select or enter the complete tunnel ID.
3. Choose **No authentication** for this private tunnel setup. This selects the
   connector's login mode; the local MCP server still requires its API key,
   which the tunnel helper supplies. Do not paste either API key into the form.
4. Review the risk notice and create the private plugin when you are ready to
   grant ChatGPT access to its tools. Confirm that ChatGPT discovers the guided
   ZAP tool names and schemas, then install/select the plugin in a conversation.

The [official private-tunnel example](https://developers.openai.com/cookbook/examples/partners/aws/chatgpt_agents_sdk_aws_agentcore_cookbook/notebooks/chatgpt_agents_sdk_aws_agentcore_cookbook#7-test-the-plugin-connection-in-chatgpt-through-secure-mcp-tunnel)
uses this connector authentication choice. This setup gives allowed tunnel
callers the configured local MCP identity; it does not establish separate
customer identities or isolation.

## Check the connection

First ask ChatGPT to list the available tools from your selected plugin. Then
use a target you own or are authorized to scan and that native ZAP can reach.
The Compose-only hostnames `juice-shop` and `petstore` do not resolve merely
because the Mac package is running.

For an HTTP target, ask:

```text
Use only my MCP ZAP plugin. Run a guided crawl with strategy=http against my
authorized target.
Follow the tools' Next Actions and poll until the crawl completes. Wait for
passive analysis, summarize findings scoped to this target, generate an HTML
report for this target, and read the report back through MCP. Do not start an
active scan.
```

Follow the tool results until the crawl and passive analysis finish. A truncated
report preview is incomplete; use `zap_report_read_chunk` until EOF for the full
report. Complete retrieval does not ensure that its finding counts match the
target's full alert set: root-URL filtering can exclude findings from HTML
reports ([issue #294](https://github.com/dtkmn/mcp-zap-server/issues/294)).

The native preview's target protections may reject localhost or private-network
demo targets by default. Use its local settings to allow only your intended
authorized target, then restart the bundled services. This HTTP preview does
not establish browser/AJAX crawling or active-scan support.

## Errors, data, and disconnecting

- Missing client: install the full official `tunnel-client`; this package does
  not bundle it or install it automatically.
- Missing key: provide the runtime key privately in the launching environment.
  A GUI or another terminal may not inherit the shell variable.
- `401`/`403` from OpenAI: verify the runtime key, its organization, and Tunnels
  **Read + Use** for the selected tunnel. A successful vendor doctor does not
  rule out this error. Set `CONTROL_PLANE_ORGANIZATION_ID` to the intended
  associated organization. A `401` alone does not identify which setting failed.
- Tunnel absent in ChatGPT: check the target workspace association and the
  account's permissions. Platform access and ChatGPT access are separate.
- Connection refused or degraded readiness: run the package doctor, confirm
  the bundled services are running, and inspect the local tunnel client state.
- Local MCP authentication failure: restart through the package launcher so the
  helper receives the current local key. Do not paste keys into a prompt or
  enable raw HTTP logging.
- ChatGPT tools missing or stale: refresh the plugin after its server metadata
  changes, then inspect discovered tools and resolve any actual call error.

Stopping the foreground client with Ctrl-C closes this local transport. Stop
the package with `./bin/mcp-zap stop` when you also want ZAP and MCP stopped;
settings and reports are retained. For persistent access revocation, remove or
disable the private plugin and revoke the tunnel's workspace/user access in
Platform as appropriate. Revoke a dedicated runtime key when necessary. If
reusing a shared `OPENAI_API_KEY`, consider its other applications before
revoking or rotating it. Stopping one client does not revoke other clients
that can use the same tunnel.

Private networking does not keep MCP results exclusively on the Mac. Tool
inputs, outputs, target URLs, findings, and report content returned through MCP
travel through OpenAI and into the ChatGPT conversation. Review what a report
contains before asking ChatGPT to read it. Disconnecting does not erase an
existing conversation or its prior outputs. The helper disables raw HTTP
logging; inspect and redact any diagnostic exports before sharing them.

This guide describes a private custom MCP connection. Public **remote MCP**
submissions require a production HTTPS MCP endpoint; this tunnel does not
replace it. A **skills-only** plugin is a separate ZIP submission without a
bundled MCP connection. Its onboarding can guide local Codex users to set up
the separate Mac package without a hosted scanner endpoint. Both routes remain
subject to OpenAI review. See the [submission requirements](https://developers.openai.com/plugins/deploy/submission-errors#zip-upload-errors-and-warnings).

## Configuration sources

- [OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [Connect and test a plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Vendor macOS installation](https://github.com/openai/tunnel-client#install-with-homebrew)
- [Vendor profile and header configuration](https://github.com/openai/tunnel-client/blob/master/docs/configuration.md)
- [Vendor tunnel permissions](https://github.com/openai/tunnel-client/blob/master/docs/permissions.md)
- [Client 0.0.16 doctor implementation](https://github.com/openai/tunnel-client/blob/v0.0.16/cmd/client/doctor_command.go)
