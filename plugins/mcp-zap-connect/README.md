# MCP ZAP Connect

A skills-only plugin by [Daniel Tse](https://github.com/dtkmn) that guides local
MCP ZAP Server setup in Codex using the project's Docker Compose release images.
This workflow has been tested locally. It starts or reconnects the scanner when
requested, configures your private Codex MCP connection and verifies status without
starting a scan. Installing the plugin alone does not start Docker or register
MCP tools.

## Requirements

- Local Codex with permission to execute commands on your machine.
- Docker with its engine running and Compose supporting `--wait` and
  `--wait-timeout`.
- A compatible POSIX shell; Git when the project needs to be cloned.

Java and ZAP run in containers, so a separate Java installation is unnecessary.
Web-only chats cannot reach the local scanner.

## Install

Install the plugin locally in Codex using the steps below.
After installation, ask its setup skill to check prerequisites, start or reuse
the Docker stack, and configure your private client connection. You normally do
not need to edit the client's configuration yourself. Review any permissions
requested by the client; scans require a separate request.

### Codex Local Installation

Clone or reuse the public MCP ZAP Server checkout. With the Codex CLI available,
run these commands from its repository root:

```bash
codex plugin marketplace add "$PWD"
codex plugin add mcp-zap-connect@mcp-zap-local-preview
```

`mcp-zap-local-preview` is the repository's existing local marketplace identifier;
the installed plugin is **MCP ZAP Connect**. Keep the checkout in place for that
marketplace. Enable the plugin in a local Codex conversation and select its
`setup-local-scanner` skill. If it is absent, start a new local conversation with
the plugin enabled. See OpenAI's [local plugin instructions](https://developers.openai.com/plugins/build/plugins#install-a-local-plugin-manually).
Codex setup and status verification have been tested locally; installation does
not establish a public directory listing.

## Use In Codex

Select `setup-local-scanner` and ask:

```text
Set up MCP ZAP Server in Codex using Docker Compose.
Reuse any existing installation and preserve my other MCP connections.
Verify with a status call only; do not run a scan.
```

The skill preserves unrelated settings and keeps keys in private local
configuration. Codex may need reconnection before its tools load. Scanning
requires a separate request identifying your authorized target and intended
workflow. See the [canonical setup guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#guided-setup-in-codex)
for the first demo scan after setup.

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
shared name, version and metadata aligned when updating either manifest.

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
with or endorsed by ZAP, OpenAI or Cursor.
