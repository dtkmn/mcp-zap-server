# MCP ZAP Connect

A skills-only plugin by [Daniel Tse](https://github.com/dtkmn) that guides local
MCP ZAP Server setup in Codex or Cursor using the project's Docker Compose
release images. It starts or reconnects the scanner when requested, configures
the requested client's private MCP connection and verifies status without
starting a scan. Installing the plugin alone does not start Docker or register
MCP tools.

## Requirements

- Local Codex or Cursor with permission to execute commands on your machine.
- Docker with its engine running and Compose supporting `--wait` and
  `--wait-timeout`.
- A compatible POSIX shell; Git when the project needs to be cloned.

Java and ZAP run in containers, so a separate Java installation is unnecessary.
The recorded Cursor setup covers Apple Silicon macOS 27.0.1 and Cursor 3.24.12.
Other platforms need validation. Web-only chats cannot reach the local scanner.

## Install

For local Cursor testing, use **Customize → Plugins → Add → From Local
Repository** and select the MCP ZAP Server checkout. The repository index
discovers this existing folder; select the plugin and add it for yourself.
Find `setup-local-scanner` in **Customize → Skills**.

Cursor repository imports use the repository-root
`.cursor-plugin/marketplace.json` to locate this folder and its
`.cursor-plugin/plugin.json` for Cursor metadata. The portable `plugin.json`
retains Codex metadata; both clients use the same `skills/` directory. Keep the
shared name, version and metadata aligned when updating either manifest.
Once the index and Cursor manifest are
published on the repository's default branch, use **Customize → Plugins → Add →
From GitHub Repository** with `https://github.com/dtkmn/mcp-zap-server`, then
select this plugin. Public marketplace submission and approval are separate;
no public listing is claimed here.

See the [canonical setup guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#guided-setup-in-codex-and-cursor)
for the shared workflow and client configuration details.

## Use

In Cursor, invoke `/setup-local-scanner` with:

```text
Set up MCP ZAP Server in Cursor using Docker Compose.
Reuse any existing installation and preserve my Codex connection.
Verify with a status call only; do not run a scan.
```

In Codex, request the same setup for Codex. The skill preserves the other
client's settings and keeps keys in private local configuration. A client may
need reconnection before its tools load. After a Docker restart in Cursor, open
the scanner under **Customize → MCPs**, select **Reload** and repeat the status
call. Scanning requires a separate request identifying your authorized target
and intended workflow.

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
with or endorsed by ZAP, OpenAI or Cursor.
