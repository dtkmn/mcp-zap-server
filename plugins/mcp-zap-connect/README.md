# MCP ZAP Connect

A skills-only plugin by [Daniel Tse](https://github.com/dtkmn) that guides local
MCP ZAP Server setup in Codex, Cursor or Claude Code using the project's Docker
Compose release images. It starts or reconnects the same scanner when requested,
configures the selected client's private MCP connection and verifies status
without starting a scan. Installing the plugin alone does not start Docker or
register MCP tools.

This checkout contains plugin `0.4.0` with Claude Code packaging and shared setup
instructions. Earlier Codex setup has been tested locally; Cursor evidence and
remaining limitations are recorded below. Claude plugin installation and native
status verification remain pending. Local changes must be published before a
GitHub installation can fetch this version.

Claude Desktop Chat uses the separate [MCPB preview](../../packaging/claude-desktop/README.md)
with local agent-callable setup tools. It reuses the same Docker scripts and
scanner; Desktop Free-account verification is tracked separately from this
Claude Code plugin.

## Requirements

- A local Codex, Cursor or Claude Code session with permission to execute
  commands on the scanner's machine.
- The selected client's installation tools. Codex commands below require the
  Codex CLI; its desktop app's bundled CLI can be used without another copy.
  Claude Code commands require `claude`.
- Docker with its engine running and Compose supporting `--wait` and
  `--wait-timeout`.
- Bash for the Docker setup scripts and Git for the GitHub installation.

Java and ZAP run in containers, so a separate Java installation is unnecessary.
Web-only chats cannot reach the local scanner.

After plugin installation, the setup skill can help install missing scanner
prerequisites with your approval, then recheck and continue. Codex CLI and Git
must be available for the direct GitHub installation commands below; the CLI
is not an additional requirement once the Desktop skill is loaded.

## Install

Install the plugin locally in Codex using the steps below.
After installation, ask its setup skill to check prerequisites, start or reuse
the Docker stack, and configure your private client connection. You normally do
not need to edit the client's configuration yourself. Review any permissions
requested by the client; scans require a separate request.

### Codex Local Installation

First run `codex --version`. If the command is missing, follow
[Check The Codex CLI](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#check-the-codex-cli)
before continuing. It checks the macOS desktop app's bundled CLI before directing
you to OpenAI's installation instructions. You can ask your desktop agent to
help with this prerequisite before the plugin is installed. Keep the same
terminal open if you use the temporary PATH adjustment. If your CLI does not
recognize the plugin commands below, update the app or CLI as described there.

Install directly from GitHub using your normal Codex installation. Run these
commands from your home directory:

```bash
cd "$HOME"
codex plugin marketplace add dtkmn/mcp-zap-server --ref main
codex plugin add mcp-zap-connect@mcp-zap-local-preview
```

Codex downloads the repository and installs the plugin in its managed cache;
you do not need to clone the repository manually. `mcp-zap-local-preview` is the
existing marketplace identifier; the installed plugin is **MCP ZAP Connect**.
See OpenAI's [Git-backed marketplace instructions](https://developers.openai.com/plugins/build/plugins#add-a-marketplace-from-the-cli).

When you request scanner setup, the skill reuses an existing project checkout
or obtains one for the Docker configuration and bootstrap scripts. The server
runs from published Docker images. Installing the plugin alone does not start
the scanner. This GitHub installation does not require a public-directory listing.

## Use In Codex

In the desktop app:

1. Open **Plugins**, find **MCP ZAP Connect** among your installed or personal
   plugins, and open its page.
2. Confirm it is enabled, then select **Try now** to invoke its setup skill in
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

GitHub installation, Desktop skill activation and status verification have been
tested locally on Apple Silicon macOS with an existing Docker scanner.

The skill preserves unrelated settings and keeps keys in private local
configuration. Codex may need reconnection before its tools load. Scanning
requires a separate request identifying your authorized target and intended
workflow. See the [canonical setup guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#guided-setup-in-codex)
for the first demo scan after setup.

## Claude Code Local Preview

Claude Code uses `.claude-plugin/plugin.json` in this plugin folder and the
same `skills/setup-local-scanner/SKILL.md` as Codex and Cursor. No MCP server is
bundled: the skill first checks the existing scanner's readiness, then configures
Claude's separate authenticated connection. Read Anthropic's
[plugin documentation](https://code.claude.com/docs/en/plugins) for supported
versions and client policy requirements. This flow requires local Claude Code;
Claude web chat and cloud coding sessions cannot reach your Mac's loopback server.

### Load From The Local Checkout

Check `claude --version` first. If it is unavailable, follow the official
[Claude Code setup instructions](https://code.claude.com/docs/en/setup) before
continuing. From the repository root, validate the plugin and marketplace, then
load the plugin for a local session:

```bash
claude plugin validate ./plugins/mcp-zap-connect
claude plugin validate .
claude --plugin-dir ./plugins/mcp-zap-connect
```

For a session in another project, pass the absolute path to the plugin folder
instead. Session loading does not register a marketplace or install the plugin
permanently. Inside Claude Code, invoke:

```text
/mcp-zap-connect:setup-local-scanner
```

Then ask:

```text
Set up MCP ZAP Server in Claude Code using Docker Compose.
Reuse any existing scanner installation and preserve my Codex and Cursor settings.
Verify with zap_passive_scan_status only; do not run a scan.
```

The skill reuses healthy containers and existing API keys, checks for conflicting
Claude definitions and preserves unrelated settings and permissions. It uses an
HTTP entry with `type: "http"` in private Claude configuration, normally
`~/.claude.json`. It does not put credentials into shared `.mcp.json` files or
command arguments. If `CLAUDE_CONFIG_DIR` is set, use that active profile's global
configuration rather than creating a second default profile.

Use `/mcp` to inspect the scanner connection. If tools have not loaded, reconnect
that server when the panel offers it, or start a new local Claude Code session.
Complete setup with `zap_passive_scan_status` through that Claude connection.
Server readiness and another client's status call do not verify Claude Code.
See [Claude Code MCP scopes and configuration](https://code.claude.com/docs/en/mcp).

### Install From A Marketplace

To install from this checkout instead of loading it for one session, register
the repository root and install the plugin:

```bash
claude plugin marketplace add /absolute/path/to/mcp-zap-server
claude plugin install mcp-zap-connect@mcp-zap-connect --scope user
```

Replace the example path with your checkout. Once these files are published on
the repository's default branch, the GitHub alternative is:

```bash
claude plugin marketplace add dtkmn/mcp-zap-server
claude plugin install mcp-zap-connect@mcp-zap-connect --scope user
```

The marketplace index at repository-root `.claude-plugin/marketplace.json`
points to this existing plugin folder. These commands describe distribution
through the project's own marketplace; they do not imply an Anthropic directory
listing. Plugin and marketplace manifests passed native validation with Claude
Code `2.1.292` on 16 October 2026. First plugin load and a Claude status call
remain pending. No Claude scan workflow has been verified.

## Cursor Preview Reference

The following technical reference records unfinished Cursor validation; it is
separate from the verified Codex setup above. Recorded Cursor checks used
Apple Silicon macOS 27.0.1 and Cursor 3.24.12. Other platforms need validation.

### Cursor Local Preview

Cursor's [local development instructions](https://cursor.com/docs/plugins#test-plugins-locally)
describe copying the complete `plugins/mcp-zap-connect` folder, including hidden
files, into `~/.cursor/plugins/local/mcp-zap-connect`. Preserve any existing copy.
Use **Developer: Reload Window**, check **Customize → Skills**, then invoke
`/setup-local-scanner` in a local Agent chat. Local imports must be allowed by
your policy; an installed marketplace plugin with the same name takes precedence.

This folder-loading route is distinct from repository and disk catalogue imports.
It is a documented alternative, not a newly verified installation: the latest
catalogue imports failed and a fresh end-to-end Cursor connection check remains
pending. Review the results below before choosing this preview.

### Cursor Import Limitations

On 12 October 2026, **Customize → Plugins → Add → From Local Repository**
discovered and installed MCP ZAP Connect 0.3.0 with its single setup skill in
Cursor 3.24.12. Setup and recovery against an existing Docker stack passed.
The 13 October retest did not reproduce that import success.

Cursor repository imports use the repository-root
`.cursor-plugin/marketplace.json` to locate this folder and its
`.cursor-plugin/plugin.json` for Cursor metadata. The portable `plugin.json`
retains Codex metadata; both clients use the same `skills/` directory. Keep the
shared name, version and description aligned across all three client manifests.

**Disk import limitation:** on 13 October 2026, fresh imports in Cursor 3.24.12
through **From Local Repository** and marketplace **Import from Disk** left the
catalogue empty, including repository-root and direct plugin-folder attempts.
The original local plugin remained installed; a fresh indexed installation was
not confirmed. Fresh native Cursor status verification also remains pending:
agent requests were blocked by **Service Unavailable** and a usage limit before
any observed MCP tool call. The cause of the empty catalogue is unconfirmed;
the usage limit has not been established as the cause of the import failures.

**GitHub import limitation:** on 12 October 2026, **From GitHub Repository**
returned an empty plugin catalogue in Cursor 3.24.12 even after the index and
manifests were published on the default branch. The `/add-plugin` command also
returned **No result**. [Cursor issue #452](https://github.com/cursor/plugins/issues/452)
reports a matching symptom; the cause of our result is unconfirmed.

Public marketplace approval and installation are separate checks; no public
listing or verified marketplace installation is claimed here.

### Cursor Connection Checks

For the Cursor preview, invoke `/setup-local-scanner` with:

```text
Set up MCP ZAP Server in Cursor using Docker Compose.
Reuse any existing installation and preserve my Codex connection.
Verify with a status call only; do not run a scan.
```

After a Docker restart in Cursor, open the scanner under **Customize → MCPs**,
select **Reload** and repeat the status call. A fresh native connection check
remains pending as described above.

## Privacy, terms, support and license

Settings, keys and reports remain in your installation; prompts and MCP results
are processed by your AI provider. Read the
[privacy policy](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#privacy-policy-for-local-scan-companion).
The [terms of service](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#terms-of-service-for-mcp-zap-connect)
explain local use, authorized scanning, third-party services and community support.
Report problems through [GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues)
with versions and a redacted error. Do not include keys, private configuration
or sensitive reports.

Licensed under [Apache-2.0](./LICENSE). This independent project is not affiliated
with or endorsed by ZAP, OpenAI, Cursor or Anthropic.
