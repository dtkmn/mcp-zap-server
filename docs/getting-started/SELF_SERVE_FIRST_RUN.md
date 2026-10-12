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

<a id="guided-setup-in-local-codex"></a>

## Guided Setup In Codex And Cursor

MCP ZAP Connect is a skills-only plugin that guides the same Docker Compose
setup above. Docker and Compose must already be installed and running. Installing
the plugin does not start the scanner or register MCP tools; ask its setup skill
to start the release containers and configure your private connection. Public
directory submission and approval are pending; local installation does not
establish a public listing in either client's marketplace.

For a local Cursor plugin test, copy the complete
[`plugins/mcp-zap-connect`](https://github.com/dtkmn/mcp-zap-server/tree/main/plugins/mcp-zap-connect)
directory into `~/.cursor/plugins/local/mcp-zap-connect`. Preserve an existing
copy rather than overwriting it. Use **Developer: Reload Window**, then check
**Customize → Skills** and invoke `/setup-local-scanner` in a local Agent chat:

```text
Set up MCP ZAP Server in Cursor using the existing Docker installation.
Preserve my Codex connection. Verify with a status call only; do not run a scan.
```

The plugin uses Cursor's private `~/.cursor/mcp.json` and Codex's private TOML
configuration only when that client is requested. Cursor's
[local plugin guide](https://cursor.com/docs/plugins#test-plugins-locally)
documents installation and local-import policy restrictions. OpenAI's automatic
onboarding entry point is specific to Codex; invoke the skill explicitly in Cursor.

The skill checks server readiness before changing client settings and verifies
the connection with a harmless status call when the client has loaded it. A
client reconnection or restart may be needed. Scanning is a separate request.

For support, use the project's
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues). Include versions
and a redacted description of the failing step. Do not upload `.env`, client
configuration, credentials, raw private logs or sensitive scan reports.

## Terms Of Service For MCP ZAP Connect

Last updated: 12 October 2026. MCP ZAP Connect is an open-source plugin
maintained by [Daniel Tse](https://github.com/dtkmn). It supplies instructions
for local MCP ZAP Server setup. The maintainer does not provide a hosted
scanner through this plugin.

**Software license.** The plugin is licensed under
[Apache-2.0](https://github.com/dtkmn/mcp-zap-server/blob/main/plugins/mcp-zap-connect/LICENSE).
That license governs use, modification and distribution, including its warranty
disclaimer and limitation of liability, subject to applicable law. This page
does not replace or restrict the rights granted by that license.

**Local setup and scanning.** Installing the plugin alone does not start a
scanner. A setup request can download project files and images, start local
containers and update the requested client's private MCP settings. Review the
actions and permissions requested by your AI client. Scanning requires a
separate request; use it only against targets you own or have permission to test.
Findings do not establish that an application is secure or replace a complete
security assessment. You are responsible for choosing authorized targets,
the scan scope and how you use the results.

**Dependencies and data.** Docker, ZAP, your AI client and download services
have their own licenses, terms and privacy policies. Read the
[privacy policy](#privacy-policy-for-local-scan-companion) before providing
sensitive targets or reports. This independent project is not affiliated with
or endorsed by ZAP, OpenAI or Cursor.

**Support.** Community support is available through
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues); response times
and resolution are not guaranteed. Share redacted diagnostics only. Use the
project's [private vulnerability reporting process](https://github.com/dtkmn/mcp-zap-server/security/policy)
for security concerns.

<a id="privacy-policy-for-local-scan-companion"></a>

## Privacy Policy For MCP ZAP Connect

Last updated: 12 October 2026. This policy covers MCP ZAP Connect, maintained
by [Daniel Tse](https://github.com/dtkmn), and the local setup it guides. The plugin
contains setup instructions. The maintainer does not operate a hosted scanner
or collect scanner telemetry through this plugin. Setup does not transmit local
credentials or reports to the maintainer. Support receives only information
you choose to submit; do not include credentials or sensitive reports.

**Local data and purpose.** Setup checks installation paths, software versions,
container state and configuration to start and connect your scanner. API keys are
generated or reused locally for authentication and stored in private local
settings; they must not be pasted into chat or support reports. Settings, local
logs, configuration backups and any subsequently generated scan reports remain
in your installation. Setup uses a harmless status call; scanning requires a
separate request. Scan data can contain URLs, HTTP traffic, application content
and findings from your chosen targets.

**Recipients and network access.** Setup downloads project files, container images
and any required ZAP add-ons from their publishers and hosting services. Those
services receive ordinary network request information, such as your IP address
and requested resource, under their own policies. An authorized scan sends
requests to your chosen targets. Your AI client/provider processes prompts,
selected command output and MCP responses under its own data policies. A report
retrieved through MCP can therefore reach that provider. Local storage does not
mean that information returned to an AI client stays on your machine. Review
your provider's settings before using sensitive targets or reports.

**Retention and controls.** The plugin has no maintainer-hosted store of scanner
data and no automatic deletion schedule for your local installation. Local data
and private backups remain until you delete them or apply your own retention
policy. Stopping containers preserves settings and reports. Uninstalling the
plugin does not remove Docker containers, local files or the separate client MCP
connection. To stop using the setup, stop its containers, disable or remove that
MCP connection, and delete the installation's files and backups when no longer
needed. Deletion of reports or settings is a separate action and requires your
instruction. Your AI provider and download services govern retention of data
they receive; their controls are separate from local deletion.

**Support information.** Information you submit in
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues), including your
GitHub identity, versions and description, is public and used to investigate the
reported problem. Its retention and deletion controls follow GitHub's policies;
deletion cannot recall copies others have made. Share only redacted diagnostics.
For security concerns, use the project's
[private vulnerability reporting process](https://github.com/dtkmn/mcp-zap-server/security/policy).
Use GitHub issues for non-sensitive questions about this policy.

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
