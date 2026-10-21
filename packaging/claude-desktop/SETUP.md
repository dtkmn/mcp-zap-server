# MCP ZAP Connect: Claude Desktop setup and first scan

Use Claude Desktop Chat on a Mac with a Free account. This preview connects
Claude to a local Docker scanner and can guide its setup. You do not need
Claude Code, Node.js, npm, Git, or a repository checkout.

## 1. Get the extension

Open the [GitHub Releases page](https://github.com/dtkmn/mcp-zap-server/releases)
and choose a published normal scanner release (`vX.Y.Z`) with the Claude Desktop
downloads under **Assets**. The extension and this guide ship with the scanner
release in the same repository; there is no separate Desktop release.

Under **Assets**, download:

- `mcp-zap-connect-claude-desktop-0.1.0.mcpb` — the extension to install.
- `mcp-zap-connect-claude-desktop-0.1.0-setup.md` — this matching guide.

Use the connector version listed in that release if it differs from `0.1.0`;
it can differ from the scanner release version. Download the matching guide
and `.mcpb` together. The adjacent `.sha256` files are checksums for these
downloads. Choose the `.mcpb`, rather than GitHub's automatic **Source code**
archives. If no release lists these assets, developers can use the
[local build instructions](https://github.com/dtkmn/mcp-zap-server/blob/main/packaging/claude-desktop/README.md#build-and-check-the-bundle).

This is a macOS preview for Claude Desktop Chat, including Free accounts.
Claude usage limits apply. It is a custom extension, not an Anthropic directory
listing or endorsement.

## 2. Install the required apps

1. Install [Claude Desktop](https://claude.com/download) and sign in to your
   Free account. Use **Chat** for this guide.
2. Install [Docker Desktop for Mac](https://docs.docker.com/desktop/setup/install/mac-install/),
   choosing Apple Silicon or Intel to match your Mac. Open Docker Desktop and
   wait until its engine is running. Reuse an existing working installation.

Complete the installers, vendor terms and macOS permission prompts yourself.
Check Docker's terms for your organization. Leave Docker running while using
the scanner.

## 3. Install MCP ZAP Connect

1. In Claude Desktop, open **Settings → Extensions → Advanced settings →
   Install Extension**.
2. Select the downloaded `.mcpb`, review the extension, and approve installation
   if you trust the release. This preview is unsigned and runs with your local
   user permissions; its tools use Docker and read the scanner's credential
   privately.
3. Leave **Scanner directory** and **Scanner API key override** blank. Ensure
   **MCP ZAP Connect** is enabled.
4. Start a fresh chat. Under **+ → Connectors**, check that **MCP ZAP Connect**
   is enabled for the chat.

You do not need to edit JSON settings or paste an API key into chat. Installing
the extension starts the connector; scanner setup and scanning are separate
requests. See [Claude's extension instructions](https://support.claude.com/en/articles/10949351-getting-started-with-local-mcp-servers-on-claude-desktop)
if the installation menu has changed.

If the extension is enabled but its tools are missing or a call times out,
fully quit Claude with **⌘Q**, reopen it, and try a new chat with the connector
enabled. This is a troubleshooting step, not a required restart after every
installation. Keep the existing tool-approval settings.

## 4. Ask Claude to set up and verify the scanner

Paste this message:

```text
Help me set up MCP ZAP Connect.
Check prerequisites first, explain the required changes, and ask for
approval before setup.

For a new installation, use the bundled standard scanner setup.
Reuse any healthy existing scanner without starting, restarting or
recreating its containers. Preserve its credentials, reports, target
restrictions, and all Codex and Cursor settings.

After I approve setup, use scanner_setup with confirm=true as needed.
Verify the connection with zap_passive_scan_status once, then stop.
Do not crawl, scan, wait for passive analysis, or generate reports yet.
```

Review Claude's proposed changes and approve setup when ready. A fresh setup
uses `~/.mcp-zap-server`, downloads the standard published scanner images, and
starts the scanner and bundled demo applications. Initial image downloads may
take a while. A healthy existing scanner is reused without replacing its keys
or client settings.

A new installation generates its own credentials. Other clients' settings are
left unchanged; this does not connect Codex or Cursor to a replacement scanner
with a different key. Reuse the existing scanner when you want to keep those
connections working without reconfiguration.

The extension bundles a snapshot of the scanner setup files and defaults.
Connector `0.1.0` currently defaults a new installation to scanner `v0.16.0`;
downloading it from a newer scanner release does not itself change that default.
An existing scanner keeps its selected version and settings.

If a prerequisite is missing, resolve it and ask Claude to check again. If it
reports conflicting ports, credentials, ownership, or customized startup files,
ask it to explain the conflict before making changes. Do not delete an existing
Docker stack merely to get through setup.

A successful `zap_passive_scan_status` response confirms the connection. It
does not start a scan or establish that any website is secure.

## 5. Run the first demo scan

The standard fresh setup includes OWASP Juice Shop, a deliberately vulnerable
local demo. Send a separate message:

```text
Check scanner_check_readiness first, then verify this connection with one
zap_passive_scan_status call. If recovery is needed, explain it and wait for
my approval before starting Docker or containers. Preserve all client settings.
Run only a guided HTTP crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish and summarize findings for this target.
Generate an HTML report scoped to this target and read the complete report
through MCP, including all chunks if paginated.
Do not start an active scan or change target restrictions.
```

Approve the relevant tool requests. **Allow once** is sufficient. This starts
an HTTP crawl and passive vulnerability analysis; active attack testing is a
separate workflow.

Use `http://juice-shop:3000` as the scan target. The scanner reaches that name
inside Docker. You can preview the demo in your Mac's browser at
`http://localhost:3001`; that host URL is not the scanner's target URL.

An existing scanner may not have the demo running. If the target is unavailable,
ask Claude to diagnose it and explain what is needed before changing containers.

### If Docker Or The Scanner Has Changed

The connector checks readiness before forwarding a new crawl or scan start.
When blocked, it returns recovery guidance instead of starting that workflow.
It does not open Docker, restart containers or repair settings automatically.

- If Docker is stopped, open your existing Docker Desktop and wait for its
  engine. Ask Claude to check readiness again. Your Docker startup-at-login
  preferences stay unchanged.
- If the known scanner's containers are stopped or removed and its private
  settings remain, ask Claude to explain the recovery and approve
  `scanner_setup` with `confirm=true` to start that installation. Keep its keys,
  reports and target restrictions. Do not remove the installation or request
  fresh credentials.
- If settings are missing, ownership or ports conflict, or credentials differ,
  stop and resolve the reported issue. Starting a different installation or
  replacing client credentials requires a separate, specific decision.
- If a customized installation needs manual startup, use its own documented
  procedure, then ask Claude to check readiness again.
- After a connector update, an older or modified startup template can return
  `SCRIPT_MISMATCH` or `START_MANUALLY`. Start that installation using its
  existing instructions, preserving its settings and keys. A healthy scanner
  can be reused when its doctor script still matches; an unused older bootstrap
  script does not block readiness checks. Do not replace templates or reset
  credentials just to bypass the check.

After recovery, verify this Claude connection with one passive-status call
before resuming the same target and requested workflow. If a crawl or scan
start loses its connection, do not immediately repeat it: the first request
may have been accepted. Ask Claude to check the current state first.

## 6. Read the result and scan your own application

Expect a completed crawl, finished passive analysis, a target-scoped findings
summary, and the complete HTML report returned through MCP. Finding counts vary;
an empty result alone does not demonstrate complete application coverage.

For a site you own or are authorized to test, ask Claude to confirm that the
scanner can reach it and that it is within the configured target restrictions.
Then repeat the prompt with that exact URL. Keep active testing as a separate,
explicit request. The [scan-to-evidence guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/)
covers additional workflows.

## Support and removal

For help, open a [GitHub issue](https://github.com/dtkmn/mcp-zap-server/issues)
with your extension version, Claude Desktop version, operating system and the
step that failed. Remove secrets from any error details. Do not upload `.env`,
API keys or private reports.

Remove the extension through **Settings → Extensions**. Removal does not stop
or delete the scanner or remove other clients' connections.
