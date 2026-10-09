# Connect the local Mac preview to ChatGPT

This guide connects the packaged MCP ZAP Server to a private ChatGPT custom MCP
plugin through OpenAI Secure MCP Tunnel. The server and ZAP stay on your Mac;
the tunnel client opens an outbound HTTPS connection to OpenAI. The package
keeps API-key authentication enabled and supplies the local `X-API-Key` header
through an environment reference.

This is a private connection path for the Apple Silicon Mac preview. On
2026-10-08, the packaged server completed a real private ChatGPT HTTP crawl,
passive analysis, HTML report generation, and full report read-back through
released tunnel client `0.0.16`. The connection needs internet access, an OpenAI
Platform tunnel, and compatible account permissions. It does not provide a public plugin listing or
an offline ChatGPT experience.

Local checks with released client `0.0.16` covered profile creation and
preservation, private file permissions, rejection of non-loopback endpoints,
and secret references without saved key values. A synthetic MCP server behind
the vendor's local test proxy received authenticated startup/discovery probes,
tool discovery, and a tool call with response read-back. Those checks do not
establish hosted tunnel access or ChatGPT behavior by themselves.

## Verified boundary on 2026-10-08

| Component | Tested configuration |
| --- | --- |
| Mac | Apple Silicon, macOS 27.0.1 |
| Package | MCP ZAP Server 0.15.0 local preview, ZAP 2.17.0, Temurin JRE 25.0.4.1+1 |
| Tunnel client | Full official client 0.0.16 |
| ChatGPT | Web interface, developer mode, GPT-6 with Pro power |

A dedicated non-admin runtime key and the explicit associated Platform
organization reached hosted readiness. One private ChatGPT connection used
**Tunnel** and **No authentication**, while the packaged local MCP endpoint
continued to enforce `X-API-Key`. The conversation exposed 21 guided tools.
An HTTP crawl of an owned loopback fixture completed, passive processing
drained, and ChatGPT generated and read its HTML report through MCP. The full
chunk read reported EOF and a SHA-256 matching the independently inspected
37,239-byte local report. The default read's truncated preview was followed
by a complete chunk read; a preview alone is insufficient evidence.

The ChatGPT interface's expanded raw tool viewer exposed a progress envelope,
not the underlying MCP arguments and response body. The completed conversation
was therefore corroborated with independent local crawler state and report
file evidence. Local requests with missing or incorrect MCP keys returned
`401`.

An outside-scope reserved hostname was refused before any tool call. This
demonstrates the conversation's refusal, not a server-side target-policy test.
ChatGPT also started an HTTP crawl against a deliberately slow owned fixture,
requested its stop and checked status. Independent engine and request evidence
confirmed interruption before natural completion. The status response reports
completion but does not expose a separate cancellation reason; do not infer a
successful full crawl from that completion flag alone.
With the tunnel client stopped, one harmless passive-status call returned
`UNAVAILABLE` and a connection timeout. ChatGPT reported the failure rather
than inventing a scan result. This was a transport failure, not an authentication
rejection.
A clean package restart and tunnel reconnection preserved the original report.
One subsequent full read in the same ChatGPT conversation reached EOF with
the same artifact checksum, without regenerating the report or starting a scan.
Report transport and complete file retrieval were verified; report finding
count parity was not. Root-URL filtering excludes some findings from the HTML
report and is tracked in [issue #294](https://github.com/dtkmn/mcp-zap-server/issues/294).
This test does not establish browser/AJAX crawling, active scanning, public
distribution, unattended operation, or clean-machine installation.

## Prerequisites

- Start the intact Mac package with `./bin/mcp-zap start`, then pass
  `./bin/mcp-zap doctor`. Keep the bundled services running.
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
It does not verify OpenAI tunnel
permissions or prove a ChatGPT connection. In client `0.0.16`, its HTTP/OAuth
diagnostic probes do not apply configured MCP headers, so an API-key-protected
server can produce an OAuth metadata failure even with the correct runtime
configuration. Keep local API-key authentication enabled. Use the package
doctor for authenticated local MCP checks, then use the running tunnel's
readiness and actual ChatGPT calls to resolve the remaining boundary.

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

## Prove the connection

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

Record the package and tunnel-client versions, ChatGPT connection mode, selected
tool names, actual tool inputs/results, completion state, and report read-back.
Remove credentials and private target details before sharing evidence. Finding
counts vary. Discovery alone does not prove the crawl, passive drain, report,
or report read-back succeeded.

The native preview's target protections may reject localhost or private-network
demo targets by default. Use its local settings to allow only your intended
authorized target, then restart the bundled services. This HTTP preview does
not establish browser or AJAX crawling support.

Also verify failures: a request to the local `/mcp` endpoint without a valid API
key must be rejected; an invalid or unauthorized target must not become a scan;
and ChatGPT calls must fail while the tunnel client is stopped. Do not change
the server to unauthenticated mode to make these checks pass. Never test key
failure by revoking a shared key that other applications still use.

## Errors, data, and disconnecting

- Missing client: install the full official `tunnel-client`; this package does
  not bundle it or install it automatically.
- Missing key: provide the runtime key privately in the launching environment.
  A GUI or another terminal may not inherit the shell variable.
- `401`/`403` from OpenAI: verify the runtime key, its organization, and Tunnels
  **Read + Use** for the selected tunnel. A successful vendor doctor does not
  rule out this error. Set `CONTROL_PLANE_ORGANIZATION_ID` to the intended
  associated organization. A `401` alone does not identify an invalid key,
  organization mismatch, or other access restriction; changing the organization
  setting is not proof that it caused an earlier failure.
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

This path tests a private custom MCP connection. Public plugin submission needs
its own stable public HTTPS endpoint, authentication, operating controls, and
submission approval.

## Configuration sources

- [OpenAI Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)
- [Connect and test a plugin](https://developers.openai.com/plugins/deploy/connect-chatgpt)
- [Vendor macOS installation](https://github.com/openai/tunnel-client#install-with-homebrew)
- [Vendor profile and header configuration](https://github.com/openai/tunnel-client/blob/master/docs/configuration.md)
- [Vendor tunnel permissions](https://github.com/openai/tunnel-client/blob/master/docs/permissions.md)
- [Client 0.0.16 doctor implementation](https://github.com/openai/tunnel-client/blob/v0.0.16/cmd/client/doctor_command.go)
- [Client 0.0.16 organization header handling](https://github.com/openai/tunnel-client/blob/v0.0.16/pkg/controlplane/internal/roundtripper.go#L102)
