# MCP ZAP Connect

A skills-only plugin by [Daniel Tse](https://github.com/dtkmn) that guides local
MCP ZAP Server setup in Codex, Cursor or Claude Code using the project's Docker
Compose release images. It starts or reconnects the same scanner when requested,
configures the selected client's private MCP connection and verifies status
without starting a scan. Installing the plugin alone does not start Docker or
register MCP tools.

Claude Desktop Chat uses the separate [MCPB preview](../../packaging/claude-desktop/README.md)
with local agent-callable setup tools. It reuses the same Docker scripts and
scanner. Install the `.mcpb` in Desktop Chat; use the plugin below for Claude Code.

## Versions and Release Pins

Normal GitHub installations select a published repository tag, not `main`.
Starting with the unreleased `0.18.0` source, the plugin uses the repository
release number. Its supported scanner version is checked separately:

| Distribution | Plugin version | Scanner setup files and image |
| --- | --- | --- |
| Published repository tag `v0.17.1` | `0.5.0` | Use the `v0.17.1` checkout and scanner image |
| Current source `0.18.0`, unreleased | `0.18.0` | Pins fresh setup to the published `v0.17.1` checkout and image; adds a read-only compatibility check |

The commands below install the published `0.5.0` plugin. They do not deliver
the unreleased `0.18.0` changes. A later release must publish those files before
its tag can be used for normal installation. Keep `main` and local plugin
loading for deliberate development use.

The published `0.5.0` setup skill does not itself pin a newly cloned checkout.
Until upgrading to a release containing the new check, follow
[Prepare a Pinned Scanner Checkout](#prepare-a-pinned-scanner-checkout) before
asking that skill to set up a fresh scanner. Pinning only the plugin does not
pin separately downloaded setup scripts.

## Requirements

- A local Codex, Cursor or Claude Code session with permission to execute
  commands on the scanner's machine.
- The selected client's installation tools. Codex commands below require the
  Codex CLI; its desktop app's bundled CLI can be used without another copy.
  Claude Code commands require `claude`.
- Docker with its engine running and Compose supporting `--wait` and
  `--wait-timeout`.
- Bash for the Docker setup scripts and Git for installation and release-checkout verification.

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

### Codex Local Installation

First run `codex --version`. If the command is missing, follow
[Check The Codex CLI](#check-the-codex-cli)
before continuing. It checks the macOS desktop app's bundled CLI before directing
you to OpenAI's installation instructions. You can ask your desktop agent to
help with this prerequisite before the plugin is installed. Keep the same
terminal open if you use the temporary PATH adjustment. If your CLI does not
recognize the plugin commands below, update the app or CLI as described there.

Install directly from GitHub using your normal Codex installation. Run these
commands from your home directory:

```bash
cd "$HOME"
codex plugin marketplace add dtkmn/mcp-zap-server --ref v0.17.1
codex plugin add mcp-zap-connect@mcp-zap-local-preview
```

Codex downloads the repository and installs the plugin in its managed cache;
you do not need to clone the repository manually. `mcp-zap-local-preview` is the
existing marketplace identifier; the installed plugin is **MCP ZAP Connect**.
See OpenAI's [Git-backed marketplace instructions](https://developers.openai.com/plugins/build/plugins#add-a-marketplace-from-the-cli).

This pins the marketplace and its relative plugin files to the release.
Existing marketplace users should follow [Updates](#updates) instead of
removing a marketplace or overwriting client settings. Installing the plugin
alone does not start the scanner. This GitHub installation does not require a
public-directory listing.

### Prepare a Pinned Scanner Checkout

For a fresh installation with released plugin `0.5.0`, ask your local agent to
obtain the release checkout below, or run the commands yourself. Use a **new**
directory; do not run them over an existing installation:

```bash
git clone --branch v0.17.1 --single-branch \
  https://github.com/dtkmn/mcp-zap-server.git \
  "$HOME/mcp-zap-server-v0.17.1"
git -C "$HOME/mcp-zap-server-v0.17.1" rev-parse HEAD
```

Require commit `02161be8f30cd3d3b18e7eb0a4d3dcd77c0bec7a` before running its
setup scripts. Tell the skill to use that exact directory and its `v0.17.1`
image; do not let it obtain another checkout from `main`. The fresh release's
configuration selects that image by default.

For an existing installation, retain its checkout, `.env`, selected image,
credentials, reports, Compose overrides and client settings. Inspect its
checkout/image combination before any startup or recovery changes. A mismatch
requires a deliberate migration decision; do not reset the checkout, rewrite
`IMAGE_TAG` or create a replacement installation to bypass it. Pure native MCP
status calls can still inspect an existing connection without upgrading it.

The unreleased `0.18.0` skill performs the pinned checkout selection and bundled
read-only release check before setup helpers. Its check does not pull images,
start containers, edit settings or certify that a scanner is ready. Readiness
and the selected client's authenticated connection are still checked separately.

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
Use my existing scanner installation if present; otherwise use the verified
v0.17.1 checkout prepared above. Do not obtain setup files from main.
Preserve my settings and other MCP connections.
Verify with a status call only; do not run a scan.
```

The skill preserves unrelated settings and keeps keys in private local
configuration. Codex may need reconnection before its tools load. Scanning
requires a separate request identifying your authorized target and intended
workflow. The packaged local client uses `*` for the curated guided tools,
including active attacks, authentication setup and imports. Run only the
requested workflow. Existing settings and scopes are preserved; installing or
reconnecting this plugin does not change permissions.
See [Quick-Start Permissions](https://danieltse.org/mcp-zap-server/getting-started/tool-scope-authorization/#quick-start-permissions)
for optional per-client restrictions. Crawling still sends target traffic.
See the [first-report guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#3-generate-your-first-report)
for the first demo scan after setup.

## Check Readiness Before A Crawl Or Scan

For each new scan workflow, select **scan-local-target** from Codex's `/skills`,
invoke `/scan-local-target` in Cursor, or invoke
`/mcp-zap-connect:scan-local-target` in Claude Code. Supply your authorized target
and intended workflow. For example:

```text
Check my existing local scanner and this client's connection before starting.
If recovery is needed, explain the required action and wait for my approval.
Run only a guided HTTP crawl against http://juice-shop:3000, wait for passive
analysis, summarize this target's findings, and return its complete HTML report.
Do not start an active attack or change client settings or target restrictions.
```

The skill runs `./bin/scanner-readiness.sh` from the selected scanner checkout,
then verifies the selected connection with one native passive-status call. If
Docker is unavailable, the scanner is stopped, ownership conflicts or
authentication fails, it stops before starting the requested workflow and
explains recovery. Starting Docker or the scanner requires your approval;
credentials and client settings are preserved. Recovery does not change Docker's
startup-at-login settings. After a successful approved recovery, the skill
rechecks readiness and resumes only your original request.

These checks are an agent-guided workflow. The skills-only plugin cannot
intercept calls sent directly to a separately configured MCP server. Invoke
the scan skill to use this flow. A pure status-only request goes directly to
the selected client's status tool without setup or recovery.

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

For a normal GitHub installation, pin the published release:

```bash
claude plugin marketplace add 'dtkmn/mcp-zap-server#v0.17.1'
claude plugin install mcp-zap-connect@mcp-zap-connect --scope user
```

This installs plugin `0.5.0`. Follow
[Prepare a Pinned Scanner Checkout](#prepare-a-pinned-scanner-checkout) before
asking it to set up a fresh scanner. See the
[Claude Code ref syntax](https://code.claude.com/docs/en/plugins/cli-reference#plugin-marketplace-add).

For deliberate development from a local checkout instead, register that
checkout's root and install its plugin:

```bash
claude plugin marketplace add /absolute/path/to/mcp-zap-server
claude plugin install mcp-zap-connect@mcp-zap-connect --scope user
```

The local path selects that checkout, including its unreleased changes. It is
not a release-pinned GitHub installation.

The marketplace index at repository-root `.claude-plugin/marketplace.json`
points to this existing plugin folder. These commands describe distribution
through the project's own marketplace; they do not imply an Anthropic directory
listing.

## Updates

Plugin updates and scanner upgrades are separate decisions. Keep the existing
marketplace identity, selected scanner version, credentials, reports and client
settings. Do not uninstall a marketplace as an update shortcut: clients can
remove its installed plugins and saved plugin data.

1. Choose a published release and read its plugin/scanner compatibility notes.
   A pinned tag does not automatically advance to the next release when refreshed.
2. Change only that marketplace's source ref as described below, then refresh the named marketplace and update
   its plugin. Do not refresh or replace unrelated marketplaces. For Codex,
   `codex plugin marketplace upgrade mcp-zap-local-preview` refreshes the
   configured ref; for Claude Code, use
   `claude plugin marketplace update mcp-zap-connect` followed by
   `claude plugin update mcp-zap-connect@mcp-zap-connect --scope user`.
   These refresh commands do not themselves select a new release tag.
3. Reload the client when required and verify the selected marketplace ref and
   installed plugin version. A successful catalog refresh alone does not prove
   the installed files changed.
4. Reconnect to the existing scanner and check status. If setup compatibility
   differs, report it and plan the scanner upgrade separately; do not change
   its image, checkout or credentials automatically.

All three client manifests use the repository release version from
`build.gradle`, without the tag's leading `v`. Each repository release advances
the plugin version too, including server-only releases. A plugin-only fix ships
in a new repository release. Claude Code uses the manifest version for updates,
so changed files must not reuse a published version. See [Claude Code version management](https://code.claude.com/docs/en/plugins/host-marketplace#release-a-new-version).

The compatibility notes identify the shared repository/plugin version and the
supported scanner release. Matching release numbers do not upgrade an existing
scanner or prove compatibility with another scanner version. The current setup
pin remains `v0.17.1`. Follow
[the publishing procedure](https://danieltse.org/mcp-zap-server/releases/publishing/) when preparing a
new release; the current source version is not a publication announcement.

### Select a Different Release for an Existing Marketplace

Back up the client's private configuration first. Use a verified published tag;
`v0.17.1` below shows how to select the current stable release, not how to obtain
unreleased plugin `0.18.0`. Preserve unrelated entries, permissions and plugin
enabled states. Managed or project settings can override user configuration;
resolve those through the deployment's existing administration process.

For **Codex**, update only `ref` in the existing marketplace table in its
effective user `config.toml` (normally `~/.codex/config.toml`, or the configured
Codex home). Retain its existing `source`, `source_type` and any other fields:

```toml
[marketplaces.mcp-zap-local-preview]
source_type = "git"
source = "https://github.com/dtkmn/mcp-zap-server.git"
ref = "v0.17.1"
```

Merge this into the existing table; do not append a duplicate table or replace
the entire file. These are the documented
[Codex marketplace configuration fields](https://learn.chatgpt.com/docs/config-file/config-reference).

For **Claude Code**, declare the selected ref in its private user settings
(normally `~/.claude/settings.json`, respecting any `CLAUDE_CONFIG_DIR`). This
is plugin configuration, not the scanner's MCP credential entry. Merge only
the named marketplace into `extraKnownMarketplaces`:

```json
{
  "extraKnownMarketplaces": {
    "mcp-zap-connect": {
      "source": {
        "source": "github",
        "repo": "dtkmn/mcp-zap-server",
        "ref": "v0.17.1"
      }
    }
  }
}
```

If it already has an equivalent `git` source, preserve that source object and
change only its `ref`. Keep other marketplace fields and all unrelated
settings. See [Claude Code marketplace source settings](https://code.claude.com/docs/en/plugins/marketplace-reference#source-objects-in-settings).
After selecting the ref, use the named refresh/update steps above, reload when
required, and verify the installed version before requesting scanner setup.

<a id="cursor-preview-reference"></a>

## Cursor Desktop Free

Use a local Cursor Agent chat on the scanner's machine. Free accounts require
available Agent allowance; MCP access does not bypass model usage limits.

### Cursor Local Preview

Cursor's [local development instructions](https://cursor.com/docs/plugins#test-plugins-locally)
describe copying the complete `plugins/mcp-zap-connect` folder, including hidden
files, into `~/.cursor/plugins/local/mcp-zap-connect`. Preserve any existing copy.
Use **Developer: Reload Window**, check **Customize → Skills**, then invoke
`/setup-local-scanner` in a local Agent chat. Local imports must be allowed by
your policy; an installed marketplace plugin with the same name takes precedence.

After setup, follow the
[first-report guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#3-generate-your-first-report)
for the demo crawl and complete report.

### Cursor Import Limitations

Cursor repository imports use the repository-root
`.cursor-plugin/marketplace.json` to locate this folder and its
`.cursor-plugin/plugin.json` for Cursor metadata. The portable `plugin.json`
retains Codex metadata; both clients use the same `skills/` directory. Keep the
shared name, version and description aligned across all three client manifests.

If **From Local Repository**, **Import from Disk**, or **From GitHub Repository**
shows an empty catalogue, use the [local preview folder](#cursor-local-preview)
route above. Check **Customize → Skills** for `setup-local-scanner` and
`scan-local-target` before requesting setup or a new scan workflow.

### Cursor Connection Checks

For the Cursor preview, invoke `/setup-local-scanner` with:

```text
Set up MCP ZAP Server in Cursor using Docker Compose.
Reuse any existing installation and preserve my Codex and Claude settings.
Verify with a status call only; do not run a scan.
```

After a Docker restart in Cursor, open the scanner under **Customize → MCPs**,
select **Reload** if reconnection is needed, then verify with the status call.
Invoke `/scan-local-target` for each separately requested crawl or scan after
setup succeeds, following the
[first-report guide](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#3-generate-your-first-report).

## Privacy, terms, support and license

Settings, keys and reports remain in your installation; prompts and MCP results
are processed by your AI provider. Read the
[privacy policy](https://danieltse.org/mcp-zap-server/getting-started/mcp-zap-connect-privacy/).
The [terms of service](https://danieltse.org/mcp-zap-server/getting-started/mcp-zap-connect-terms/)
explain local use, authorized scanning, third-party services and community support.
Report problems through [GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues)
with versions and a redacted error. Do not include keys, private configuration
or sensitive reports.

Licensed under [Apache-2.0](./LICENSE). This independent project is not affiliated
with or endorsed by ZAP, OpenAI, Cursor or Anthropic.
