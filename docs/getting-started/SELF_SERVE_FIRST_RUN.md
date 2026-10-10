# Self-Serve First Run

Start the local Docker stack, connect your MCP client, and generate a report
from a bundled demo target. This is the complete local installation workflow.

You need Docker 20.10 or newer, Docker Compose with `--wait` and
`--wait-timeout` support, and an MCP client supporting Streamable HTTP with
custom headers. Start Docker before continuing.

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

The default MCP image is `dtkmn/mcp-zap-server:v0.15.0`, available for AMD64 and
ARM64. Choose another published version with `IMAGE_TAG` in `.env`. An existing
`latest` or development tag must be changed deliberately to a released version;
startup will not overwrite that choice. Keep `.env` out of version control.

<a id="4-connect-a-client"></a>

## 2. Connect Your MCP Client

Use the [MCP Client Setup guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/)
for Codex, Cursor, and other clients. Preserve any existing client connections.
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

Ask your connected client:

```text
Run only a guided HTTP crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish, summarize findings for this target,
generate an HTML report scoped to that target, and read it back through MCP.
Do not start an active scan.
```

Expect a completed crawl, drained passive analysis, a target-scoped findings
summary, and an HTML report returned through MCP. Finding counts vary; completed
operations and report readback demonstrate a working setup.

Use container URLs for scans: `http://juice-shop:3000` or
`http://petstore:8080`. The host ports `http://localhost:3001` and
`http://localhost:3002` are browser previews; ZAP cannot use them to reach these
demo containers.

The [Scan-To-Evidence Guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/)
covers optional active scanning, release evidence, and customer handoff.

## Stop And Restart

From the repository directory:

```bash
docker compose stop
./bin/bootstrap-local.sh --start
```

Stopping retains containers and workspace files; restarting reuses settings,
keys, and downloaded images without forcing a registry refresh. ZAP add-on
installation and scans can still require network access. To change releases,
set `IMAGE_TAG` to the published version you want, then rerun `--start`. Use
`docker compose pull` when you deliberately want to refresh cached images.
Do not use `--force` to restart or recover: it replaces `.env` and rotates
both API keys, invalidating existing client credentials.

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
