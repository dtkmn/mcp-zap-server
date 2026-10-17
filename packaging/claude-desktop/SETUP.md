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
archives. These downloads are planned for the next normal scanner release.
If no published release has the assets, the download has not been released yet.

This is a macOS preview. Installation, existing-scanner use and fresh scanner
setup have been verified on Apple Silicon with Claude Desktop Free. The HTTP
crawl and report workflow was verified with an existing scanner; fresh setup
was verified through a status call with Docker already installed. Intel Macs
and other operating systems have not been verified. This is a custom extension,
not an Anthropic directory listing or endorsement.

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

On 17 October 2026, MCPB `0.1.0` passed native tests with Claude Desktop Free
`2.42304.0`, Apple Silicon macOS `27.0.1` and Desktop's built-in Node `24.21.0`.
Both optional scanner settings were blank.

| Workflow | Verified result |
| --- | --- |
| Existing scanner `v0.15.0` | A status call succeeded. The separate guided HTTP crawl of Juice Shop completed, passive analysis finished, findings were summarized, and a target-scoped HTML report was generated and read back completely: 53,138 characters, untruncated. No active attack ran. |
| Fresh scanner `v0.16.0` | With Docker and the extension already installed, prerequisites found no existing installation or scanner. Approved setup created `~/.mcp-zap-server` with new credentials, started the scanner and passed readiness. One status call returned Completed yes, Records remaining 0 and Active tasks 0; no crawl, scan or report generation ran on this new stack. |

All 11 captured client/scanner configuration hashes and permissions stayed
unchanged, including Codex and Cursor. Existing-scanner checks preserved the
running stack; the fresh-setup test deliberately removed its containers first
while retaining its files. Preserved client settings do not authenticate to a
new scanner with different credentials.

Missing-prerequisite installation on a clean machine, cold image downloads,
public release downloads, Intel/other platforms and active attacks were not
verified. The HTTP/report workflow was not repeated on the fresh stack.

The exact native-tested local archive SHA256 is
`457a656a4bdf60077006ccf231c247370c92580edc64bc28eca089128ff173eb`.
All 2,379 installed archive files matched it. Documentation changes produce a
different archive when rebuilt. A new build or release download needs its own
checksum and exact-artifact verification; package tests alone do not establish
native verification of changed archive bytes. Claude Free usage limits still
apply.

For help, open a [GitHub issue](https://github.com/dtkmn/mcp-zap-server/issues)
with your extension version, Claude Desktop version, operating system and the
step that failed. Remove secrets from any error details. Do not upload `.env`,
API keys or private reports.

Remove the extension through **Settings → Extensions**. Removal does not stop
or delete the scanner or remove other clients' connections.
