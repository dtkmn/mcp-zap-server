# MCP ZAP Connect: Claude Desktop setup and first scan

Use Claude Desktop Chat on a Mac with a Free account. This preview connects
Claude to a local Docker scanner and can guide its setup. You do not need
Claude Code, Node.js, npm, Git, or a repository checkout.

## 1. Get the extension

Open the [GitHub Releases page](https://github.com/dtkmn/mcp-zap-server/releases)
and choose a published **Claude Desktop MCP ZAP Connect** preview with a tag
starting `claude-desktop-v`.

Under **Assets**, download:

- `mcp-zap-connect-claude-desktop-<version>.mcpb` — the extension to install.
- `mcp-zap-connect-claude-desktop-<version>-setup.md` — this matching guide.

The adjacent `.sha256` files are checksums for these downloads. Choose the
`.mcpb`, rather than GitHub's automatic **Source code** archives. If no published
Desktop preview has these assets, the download has not been released yet.

This is a macOS preview. Installation and a status call have been verified on
Apple Silicon with Claude Desktop Free; Intel Macs and other operating systems
have not been verified. This is a custom extension, not an Anthropic directory
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
Run only a guided HTTP crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish and summarize findings for this target.
Generate an HTML report scoped to this target and read the complete report
through MCP, including all chunks if paginated.
Do not start an active scan or change container lifecycle or target restrictions.
```

Approve the relevant tool requests. **Allow once** is sufficient. This starts
an HTTP crawl and passive vulnerability analysis; active attack testing is a
separate workflow.

Use `http://juice-shop:3000` as the scan target. The scanner reaches that name
inside Docker. You can preview the demo in your Mac's browser at
`http://localhost:3001`; that host URL is not the scanner's target URL.

An existing scanner may not have the demo running. If the target is unavailable,
ask Claude to diagnose it and explain what is needed before changing containers.

## 6. Read the result and scan your own application

Expect a completed crawl, finished passive analysis, a target-scoped findings
summary, and the complete HTML report returned through MCP. Finding counts vary;
an empty result alone does not demonstrate complete application coverage.

For a site you own or are authorized to test, ask Claude to confirm that the
scanner can reach it and that it is within the configured target restrictions.
Then repeat the prompt with that exact URL. Keep active testing as a separate,
explicit request. The [scan-to-evidence guide](https://danieltse.org/mcp-zap-server/scanning/mcp-client-scan-to-evidence/)
covers additional workflows.

## Preview validation and support

MCPB 0.1.0 installation, tool discovery and one status-only call passed on
16 October 2026 with Claude Desktop Free 2.42304.0, Apple Silicon macOS 27.0.1,
and an existing v0.15.0 scanner. The original container state and Codex/Cursor
settings were preserved. Fresh setup has automated fixture coverage; a fresh
machine and the full scan/report flow have not been tested end to end in Claude.
That native test used the original local archive. Documentation rebuilds and
release downloads have their own checksums and require separate exact-artifact
verification; a successful package build does not establish native verification
of a new release asset. Claude Free usage limits still apply.

For help, open a [GitHub issue](https://github.com/dtkmn/mcp-zap-server/issues)
with your extension version, Claude Desktop version, operating system and the
step that failed. Remove secrets from any error details. Do not upload `.env`,
API keys or private reports.

Remove the extension through **Settings → Extensions**. Removal does not stop
or delete the scanner or remove other clients' connections.
