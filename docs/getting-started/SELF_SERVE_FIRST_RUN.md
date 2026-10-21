# Self-Serve First Run

Start a local scanner, connect your AI client and retrieve a complete HTML
report from a bundled demo target. Follow the three steps below for manual
Docker setup.

You need Docker 20.10 or newer with its engine running, Docker Compose with
`--wait` and `--wait-timeout` support, Git, and an MCP client supporting
Streamable HTTP with custom headers. The default image is `v0.16.0`; see the
[upgrade guide](https://danieltse.org/mcp-zap-server/releases/0.16.0/)
for an existing installation.

**Prefer guided setup?** Use [MCP ZAP Connect in Codex, Cursor or Claude Code](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/),
or the [Claude Desktop setup guide](https://github.com/dtkmn/mcp-zap-server/blob/main/packaging/claude-desktop/SETUP.md).
Return to step 3 after your client connects.

<a id="1-bootstrap-local-settings"></a>
<a id="2-start-the-stack"></a>

## 1. Start The Local Stack

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server
./bin/bootstrap-local.sh --start
```

The command creates private `.env` settings and random MCP/ZAP API keys when
settings are absent. It preserves existing settings, keys, and workspace data,
downloads missing images, waits for MCP and ZAP health, and verifies
the authenticated MCP connection. Continue after it reports ready; the doctor
runs automatically.

The packaged local client uses the `*` grant for the curated `guided` tools,
including active testing, authentication setup and API imports. The first-report
workflow below requests only an HTTP crawl, passive analysis and a report.
Existing `.env` files and custom client grants are preserved. See
[Quick-Start Permissions](https://danieltse.org/mcp-zap-server/getting-started/tool-scope-authorization/#quick-start-permissions)
for the default and optional per-client restrictions. Tool access does not
replace authorization to scan a target; crawling also sends traffic.

The default MCP image is `dtkmn/mcp-zap-server:v0.16.0`, published for Linux AMD64
and ARM64. Choose another
published version with `IMAGE_TAG` in `.env`. An existing
`latest` or development tag must be changed deliberately to a released version;
startup will not overwrite that choice. Keep `.env` out of version control.

<a id="4-connect-a-client"></a>

## 2. Connect Your MCP Client

Use the [MCP Client Setup guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/)
for Codex, Cursor, Claude Code, and other clients. Preserve any existing client connections.
The local connection uses:

- endpoint: `http://localhost:7456/mcp`
- transport: Streamable HTTP
- header: `X-API-Key` with the `MCP_API_KEY` value from your private `.env`

Ask your client:

```text
List the available ZAP tools and explain the guided crawl-to-report workflow.
```

It should discover tools including `zap_crawl_start`, `zap_passive_scan_wait`,
`zap_findings_summary`, and `zap_report_generate`. Resolve connection or
authentication errors before starting a scan. Client setup is separate from
server startup; no browser chat UI is bundled.

<a id="6-use-the-guided-happy-path"></a>

## 3. Generate Your First Report

Check scanner readiness before each new workflow. If you use MCP ZAP Connect,
invoke its [scan skill](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#check-readiness-before-a-crawl-or-scan)
first. The plugin cannot intercept direct MCP calls. Without that skill, run
this from your selected scanner checkout:

```bash
./bin/scanner-readiness.sh
```

Continue only when it reports `READY`. Ask your connected client:

```text
Check my existing scanner's current readiness and verify this connection with
zap_passive_scan_status once before starting. If recovery is needed, explain
the required action and wait for my approval. Preserve all client settings.
Run only a guided HTTP crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish, summarize findings for this target,
generate an HTML report scoped to that target, and read the complete report
through MCP, including every chunk if needed.
Do not start an active scan.
```

Expect a completed crawl, drained passive analysis, a target-scoped findings
summary, and the complete HTML report returned through MCP. If the report is
truncated, read its chunks until the end; a preview alone is not complete
readback. Finding counts vary; completed operations and report readback
demonstrate a working setup.

Use container URLs for scans: `http://juice-shop:3000` or
`http://petstore:8080`. The host ports `http://localhost:3001` and
`http://localhost:3002` are browser previews; ZAP cannot use them to reach these
demo containers.

The [Scan-To-Evidence Guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/)
covers optional active scanning, release evidence, and customer handoff.

## Stop And Restart

From the repository directory:

```bash
docker compose --project-directory "$PWD" --env-file "$PWD/.env" -f "$PWD/docker-compose.yml" stop
./bin/bootstrap-local.sh --start
```

Stopping retains containers and workspace files; restarting reuses settings,
keys, and downloaded images without forcing a registry refresh. ZAP add-on
installation and scans can still require network access. To change releases,
set `IMAGE_TAG` to the published version you want, then rerun `--start`. Use
`docker compose pull` when you deliberately want to refresh cached images.
Do not use `--force` to restart or recover: it replaces `.env` and rotates
both API keys, invalidating existing client credentials.

## Readiness And Recovery After Environment Changes

Users can stop Docker, change its selected context or remove containers at any
time. Check current readiness before each new crawl or scan; a previous
installation, container restart policy or successful status response does not
establish readiness now.

`./bin/scanner-readiness.sh` is read-only. It checks the local Docker endpoint,
Compose ownership, private settings, scanner state and authenticated readiness
without calling a scan tool. It does not start Docker, recreate containers,
change client settings or rotate keys. Its `READY` result establishes server
readiness. One successful `zap_passive_scan_status` through the intended client
then confirms that client's loaded connection.

| Blocker | Recovery |
| --- | --- |
| Docker is stopped | Open your existing Docker application and wait for its engine. An agent can offer to open it after your approval; your login-startup preferences stay unchanged. |
| This installation's containers are stopped or missing, and its settings remain intact | After checking ownership and conflicts, approve `./bin/bootstrap-local.sh --start` from the same checkout. It preserves keys and reports and may obtain missing published images. |
| Private settings are missing | Treat this as setup requiring separate approval. Do not generate new credentials merely to reconnect an existing client. |
| Another Docker context, installation or application owns the endpoint or ports | Resolve the reported conflict before continuing. Do not stop unrelated resources or switch contexts as a shortcut. |
| Credentials or the client connection differ | Diagnose the selected installation and alias. Approve any specific credential repair separately; leave other clients and permissions unchanged. |
| The scanner is customized or unhealthy | Follow its existing startup instructions or diagnose the reported failure before retrying. |

After an approved repair, run readiness again, reconnect the selected client if
needed, and make one native status call. Continue only the original target and
workflow. A pure status-only request goes directly to that client's status tool;
it does not authorize setup or recovery.

If a connection fails while a crawl or scan is starting, do not automatically
repeat the start call: the server may already have accepted it. Check current
state and establish what happened before starting anything again.

<a id="3-run-the-doctor"></a>
<a id="7-if-it-breaks"></a>

## Troubleshooting

Failed startup returns a failure and retains any containers it started. Check
`docker compose ps`, then run the doctor if needed:

```bash
./bin/self-serve-doctor.sh
```

The doctor checks container availability, API-key access, rejection without
authentication, MCP initialization, guided tool discovery, and a harmless tool
call. Inspect `docker compose logs mcp-server` or `docker compose logs zap`
privately if it reports a failure. Fix the reported problem and rerun `--start`.

The container health wait defaults to 300 seconds. For slower startup, use
`./bin/bootstrap-local.sh --start --wait-timeout 600`; the allowed range is
1–900 seconds. Image downloads and the doctor's bounded requests take
additional time.

For a manually configured workspace, use a literal absolute path or
`./zap-workplace` in `.env`; Compose does not execute shell expressions there.
Ensure the directories are writable by the container users. When reporting a
problem, include the failing step and client version, and remove credentials
from any shared diagnostics.

<a id="5-decide-whether-the-target-needs-login"></a>

## Targets Requiring Login

The bundled demos do not require a target-auth profile. For an authorized target
with a traditional username/password HTML form, follow the
[Form-Login Target Authentication guide](https://danieltse.org/mcp-zap-server/getting-started/form-login-target-authentication/).
This is separate from the API key or JWT used by your MCP client. Never put the
website password in client configuration or an MCP prompt.

## Source Development

Contributor builds use the existing development override. Follow
[Development Setup](https://github.com/dtkmn/mcp-zap-server/blob/main/CONTRIBUTING.md#local-stack)
to build your checkout instead of running the published image.

## Client Setup And Policies

<a id="guided-setup-in-codex"></a>
<a id="guided-setup-in-local-codex"></a>
<a id="guided-setup-in-codex-and-cursor"></a>
<a id="check-the-codex-cli"></a>
<a id="install-in-codex"></a>
<a id="use-in-the-desktop-app"></a>

- **Codex:** [Check the CLI](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#check-the-codex-cli),
  [install the plugin](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#codex-local-installation)
  and [use it in the app](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#use-in-codex).

<a id="guided-setup-in-cursor"></a>

- **Cursor:** [Install the local plugin and request setup](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#cursor-desktop-free).

<a id="guided-setup-in-claude-code"></a>
<a id="test-the-local-preview"></a>
<a id="load-the-local-preview"></a>
<a id="install-from-github-after-publication"></a>
<a id="install-from-github"></a>

- **Claude Code:** [Load the plugin](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#claude-code-local-preview)
  or [install from a marketplace](https://danieltse.org/mcp-zap-server/getting-started/agent-guided-setup/#install-from-a-marketplace).

<a id="terms-of-service-for-mcp-zap-connect"></a>

- [MCP ZAP Connect terms of service](MCP_ZAP_CONNECT_TERMS.md).

<a id="privacy-policy-for-mcp-zap-connect"></a>
<a id="privacy-policy-for-local-scan-companion"></a>

- [MCP ZAP Connect privacy policy](MCP_ZAP_CONNECT_PRIVACY.md).
