# Self-Serve First Run

Start the local Docker stack, connect your MCP client, and generate a report
from a bundled demo target. This is the complete local installation workflow.

You need Docker 20.10 or newer, Docker Compose with `--wait` and
`--wait-timeout` support, and an MCP client supporting Streamable HTTP with
custom headers. Start Docker before continuing.

The defaults use published `v0.16.0`. Review the
[release and upgrade guide](https://danieltse.org/mcp-zap-server/releases/0.16.0/)
when upgrading an existing installation.

Prefer agent-guided setup? [Install MCP ZAP Connect locally in Codex](#guided-setup-in-codex),
then ask Codex to start and connect the scanner. A
[local Claude Code preview](#guided-setup-in-claude-code) is also available for
testing from your checkout. The manual Docker steps below are available
without the plugin.

Claude Desktop Free users can follow the separate
[MCPB download, setup and first-scan guide](https://github.com/dtkmn/mcp-zap-server/blob/main/packaging/claude-desktop/SETUP.md).
Published Desktop previews will provide the `.mcpb` and matching guide as
[GitHub Release assets](https://github.com/dtkmn/mcp-zap-server/releases);
public distribution is pending.
It exposes local setup tools that reuse these Docker scripts, so Claude can
check prerequisites and guide setup after an explicit request. Installing the
extension alone does not start containers. Installation and an existing-scanner
status call on Claude Desktop Free passed on 16 October 2026; fresh scanner
startup has fixture coverage rather than live verification. See the
[client guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/#claude-desktop)
for the existing-scanner status-only flow. The MCPB guide then walks through a
separately requested demo crawl, passive analysis and complete HTML report.

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
<a id="guided-setup-in-codex-and-cursor"></a>

## Guided Setup In Codex

MCP ZAP Connect `0.3.0` lets you ask Codex to handle the local Docker Compose
setup above, including prerequisites, startup, private client configuration and
a status check. You normally do not need to edit the client's configuration
yourself. Docker and Compose must be ready before scanner startup; the skill can
help install missing prerequisites with your approval, recheck them and continue.
Review any permissions requested by your client. Installing this skills-only
plugin does not start the scanner or register MCP tools until you request setup.

GitHub installation, Desktop skill activation and status verification have been
tested locally in Codex on Apple Silicon macOS with an existing Docker scanner.
Install the plugin locally using the steps below. This route requires Git and
Bash for the project's Docker setup scripts.

### Check The Codex CLI

The installation commands use the Codex CLI (`codex`). On macOS, run this in
your terminal, or ask your desktop agent to run it before installing the plugin.
It keeps an existing `codex` command and checks common desktop-app locations
only when the command is missing:

```bash
if ! command -v codex >/dev/null 2>&1; then
  for codex_dir in \
    "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS" \
    "/Applications/Codex.app/Contents/Resources" \
    "$HOME/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS" \
    "$HOME/Applications/Codex.app/Contents/Resources"
  do
    if [ -x "$codex_dir/codex" ]; then
      export PATH="$codex_dir:$PATH"
      break
    fi
  done
fi
codex --version
command -v codex
```

The PATH change lasts only for this terminal session; use the same terminal
for the installation commands below. The block prints the version and CLI path.
An agent using separate command sessions should use that executable's full path
for subsequent commands, since a PATH change does not carry between shells.
It does not edit shell startup files, Codex connections or Docker settings.
For an app installed elsewhere, use its bundled CLI directory instead. OpenAI's
[bundled CLI documentation](https://learn.chatgpt.com/docs/reference/troubleshooting#feature-is-working-in-the-codex-cli-but-not-in-the-chatgpt-desktop-app)
also describes the older `Codex.app` location.

If `codex` is still missing, follow OpenAI's
[CLI installation instructions](https://learn.chatgpt.com/docs/codex/cli#getting-started)
to install it, then open a new terminal and run `codex --version` again.
Continue only after it prints a version. If your CLI does not recognize the
plugin commands below, update the desktop app or standalone CLI using OpenAI's
instructions and retry. This prerequisite comes before plugin installation;
the plugin's setup skill is not available to help until it is installed.

### Install In Codex

Install directly from GitHub using your normal Codex installation. Run these
commands from your home directory:

```bash
cd "$HOME"
codex plugin marketplace add dtkmn/mcp-zap-server --ref main
codex plugin add mcp-zap-connect@mcp-zap-local-preview
```

Codex downloads the repository and installs the plugin in its managed cache;
no manual repository checkout is required. `mcp-zap-local-preview` is the
existing marketplace identifier; the plugin installs as **MCP ZAP Connect**.
When you request scanner setup, the skill reuses an existing project checkout
or obtains one for the Docker configuration and bootstrap scripts, then uses
published release images. Installing the plugin alone does not start the
scanner. This GitHub installation does not require a public-directory listing.
See OpenAI's [Git-backed marketplace instructions](https://developers.openai.com/plugins/build/plugins#add-a-marketplace-from-the-cli).

### Use In The Desktop App

1. Open **Plugins**, find **MCP ZAP Connect** among your installed or personal
   plugins, and open its page.
2. Confirm it is enabled, then select **Try now** to invoke the setup skill in
   a local conversation on this machine.
3. Send the setup request below.

If the plugin or skill is missing, restart the desktop app and open a new local
conversation with the plugin enabled. For CLI use, run `codex`, use `/plugins`
to confirm the plugin is enabled, then `/skills` to select `setup-local-scanner`.

Ask:

```text
Set up MCP ZAP Server in Codex using Docker Compose.
Reuse any existing installation and preserve my other MCP connections.
Verify with a status call only; do not run a scan.
```

The skill checks server readiness before changing client settings and verifies
the connection with a harmless status call when the client has loaded it. A
client reconnection or restart may be needed. Scanning is a separate request;
after verification, use the [first-report prompt above](#3-generate-your-first-report)
to try the bundled demo through your agent.

For support, use the project's
[GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues). Include versions
and a redacted description of the failing step. Do not upload `.env`, client
configuration, credentials, raw private logs or sensitive scan reports.

## Guided Setup In Claude Code

Claude Code support in MCP ZAP Connect `0.4.0` is a local, unreleased preview.
It uses the existing setup skill and Docker Compose workflow. On 16 October
2026, the plugin and marketplace manifests passed native validation with Claude
Code `2.1.292`. Fresh Claude setup, skill activation and a native Claude status
call remain pending. The
earlier Codex and Cursor results do not verify this new client.

Use **local Claude Code on the same machine as the scanner**. A web or remote
session cannot reach this machine's loopback endpoint. This route does not
configure Claude chat remote connectors or add a stdio bridge. See Anthropic's
[plugin overview](https://code.claude.com/docs/en/plugins) and
[MCP connection documentation](https://code.claude.com/docs/en/mcp).

### Test The Local Preview

Use an existing Claude Code CLI. If it is missing, follow Anthropic's
[Claude Code setup instructions](https://code.claude.com/docs/en/setup) before
continuing; installing this plugin does not install Claude Code. With your
checkout containing the `0.4.0` preview, replace the absolute path below:

```bash
claude --version
claude plugin validate /absolute/path/to/mcp-zap-server/plugins/mcp-zap-connect
claude plugin validate /absolute/path/to/mcp-zap-server
claude --plugin-dir /absolute/path/to/mcp-zap-server/plugins/mcp-zap-connect
```

The validation commands check packaging. `--plugin-dir` loads the local plugin
for that Claude Code session without registering a marketplace. Inside that
session, invoke:

```text
/mcp-zap-connect:setup-local-scanner
```

Then request:

```text
Set up MCP ZAP Server in local Claude Code using Docker Compose.
Reuse my existing scanner and preserve my Codex, Cursor and other MCP connections.
Verify with zap_passive_scan_status only; do not run a scan.
```

The skill must prove authenticated server readiness before merging a separate
HTTP connection into Claude Code's private user configuration. Reuse the existing
scanner alias and preserve disabled states, tool restrictions and approval
settings. If a project or local connection shadows the user connection, resolve
which one to use before changing it. Never write the key into a shared project
file or a command argument. The
[manual Claude Code recipe](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/#claude-code)
describes the header and configuration scope.

After the connection is configured, inspect `/mcp` in Claude Code and make the
status call there. A successful server doctor or a call from Codex proves only
that other connection. If Claude has not loaded the tools, leave native client
verification pending; do not treat written settings as a working connection.

### Install From GitHub After Publication

The following route applies only after the Claude marketplace files and plugin
version have been published to GitHub. The local `0.4.0` preview does not establish
that those files are available remotely:

```bash
claude plugin marketplace add dtkmn/mcp-zap-server
claude plugin install mcp-zap-connect@mcp-zap-connect --scope user
```

Review the installation result and invoke the same namespaced skill above in a
local session. See Anthropic's
[marketplace guide](https://code.claude.com/docs/en/plugin-marketplaces) and
[installation scopes](https://code.claude.com/docs/en/discover-plugins#choose-an-install-scope).
Installing the skills-only plugin does not start containers, register an MCP
connection or authorize a scan.

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
