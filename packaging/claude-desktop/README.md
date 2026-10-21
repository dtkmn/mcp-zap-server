# MCP ZAP Connect for Claude Desktop

This is the **macOS local preview, version 0.1.0**, of a Claude Desktop MCP
Bundle (`.mcpb`). It gives Claude local prerequisite, setup and readiness tools,
then connects to the existing MCP ZAP Server tools over authenticated local HTTP.
The setup tools remain available when the scanner is offline.

New users should follow the [download, setup and first-scan guide](./SETUP.md).
It covers Claude Desktop Free, Docker Desktop, extension installation, two chat
prompts, and the first demo report. No repository checkout or host Node/npm is
required to use a published bundle.

This bundle is separate from the [Claude Code skills-only plugin](https://github.com/dtkmn/mcp-zap-server/blob/main/plugins/mcp-zap-connect/README.md#claude-code-local-preview),
version `0.5.0`. Install the `.mcpb` in Claude Desktop; Claude Code plugin commands
do not install this extension. This preview does not establish an Anthropic
directory listing or approval.

## Install the local preview

1. Open [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and
   choose a published normal scanner release (`vX.Y.Z`) whose **Assets** include
   the Claude Desktop downloads. Download
   `mcp-zap-connect-claude-desktop-0.1.0.mcpb` and the matching
   `mcp-zap-connect-claude-desktop-0.1.0-setup.md` guide, or the connector version
   listed by that release. Both have adjacent `.sha256` files. These assets are
   distributed with normal scanner releases in this repository; there is no
   separate Desktop release or repository. If no published release has them,
   developers can build the local preview below.
2. In Claude Desktop, open **Settings → Extensions → Advanced settings**, choose
   **Install Extension**, and select the `.mcpb` file. Review the extension and
   complete the installation prompts.
3. Leave **Scanner directory** and **Scanner API key override** blank for the
   normal setup. The connector detects an existing Docker installation and reads
   its effective credential privately. You do not need to copy an API key into a
   chat, terminal command or another client's configuration.
4. Start a local Claude Desktop chat with the extension enabled. Use one of the
   prompts below.

An explicit scanner directory, when needed, must be an absolute path owned by
you. The optional API-key override must match the selected scanner; sensitive
extension configuration is handled by Claude Desktop. Do not enter target website
passwords or unrelated credentials in either field.

Installing the extension starts its local connector, but does not authorize
scanner setup, Docker startup or a scan. Users do not need to install Node.js or
npm: the Node extension uses Claude Desktop's bundled runtime. The bundle
requires Node.js `>=20.18.1`.

If the extension is installed but a tool call stalls or its approval prompt does
not appear, fully quit and reopen Claude Desktop, then retry one status-only
request in a new chat. Restarting is a troubleshooting step, not a required
installation step.

## Ask Claude to set up the scanner

Send this as one message, so the preservation and verification constraints apply
from the beginning:

```text
Help me set up or reconnect my local MCP ZAP scanner using this extension.
Check prerequisites first and explain any required changes before asking for approval.
Reuse any healthy existing scanner without starting, restarting or recreating its containers.
Preserve its keys, reports, target restrictions, and all Codex and Cursor settings.
Only call scanner_setup with confirm=true after I have requested and approved setup.
Verify this Claude Desktop connection with zap_passive_scan_status once only, then stop.
Do not crawl, scan, wait for passive analysis, or generate reports.
```

Claude should use these local tools:

| Tool | Purpose |
| --- | --- |
| `scanner_check_prerequisites` | Check required software, Docker access and existing scanner ownership without changing the installation. |
| `scanner_setup` | After your explicit setup request and approval, call with `{"confirm":true}` to reuse a healthy scanner or prepare and start the standard release stack. |
| `scanner_check_readiness` | Run the existing authenticated doctor with its tool-call probe disabled. It does not start containers or call a scanner tool. |

For a new installation, setup uses the private `~/.mcp-zap-server` directory. It
copies the packaged Compose file, environment template and existing bootstrap
and doctor scripts there, then uses those scripts and the published release
images. Keys and scanner workspace data belong to that installation rather than
the extension's install directory. An existing healthy installation is reused;
setup must not replace its settings or rotate its keys.

A fresh installation generates new scanner credentials. Preserving Codex/Cursor
settings does not reconnect those clients if they still hold another
installation's key. Reuse or restore their original scanner to retain those
connections without editing their settings; handle any credential migration
separately and explicitly.

Docker and the required host utilities still need to be available. If Docker is
missing, follow the [official Docker Desktop installation instructions](https://docs.docker.com/desktop/setup/install/mac-install/).
This preview uses the local Docker CLI to establish installation ownership and
read its effective key when connecting. The optional key field does not bypass
that requirement. After connecting, tool calls reuse the established MCP session.
Complete downloads, installation, license acceptance, sign-in and operating-system
permission prompts yourself as required. The extension does not silently install
software, approve OS prompts or bypass security controls. After resolving a
prerequisite, ask Claude to check it again before continuing.

Approved startup uses only the packaged scripts and Compose configuration.
For a customized installation, the connector may return `START_MANUALLY` instead
of changing or starting it. Follow that installation's own startup procedure,
then ask Claude to check readiness and verify status. Conflicting ports,
credentials or ownership require resolution; they do not authorize replacing an
existing scanner.

Readiness checks and healthy reuse verify the doctor script they execute; an
older unused bootstrap script does not prevent reuse. Startup still verifies
both setup scripts and the Compose template. If an older or modified template
returns `SCRIPT_MISMATCH` or `START_MANUALLY`, start that installation through
its existing instructions, preserve its settings and keys, then check readiness
again. The connector does not replace templates automatically.

## Readiness Before A Crawl Or Scan

The connector checks readiness before forwarding a new crawl or scan start. If
Docker or the scanner is unavailable, it returns the blocker and recovery
guidance without forwarding that start request. It does not start Docker,
recover containers, generate credentials or change any client settings
automatically. A successful installation or earlier scan does not replace this
current-state check.

Ask Claude to run `scanner_check_readiness` and verify this connection with
`zap_passive_scan_status` before each new workflow. If Docker is stopped, open
the existing Docker application and wait for its engine, then recheck. If the
standard installation's containers are stopped or removed but its private
settings remain intact, review and approve `scanner_setup` with `confirm=true`
to start that same installation. The existing bootstrap may obtain missing
published images; it preserves keys and reports. Do not use recovery to create
a replacement installation or rotate credentials.

Ownership, port, Docker-context, configuration or credential conflicts require
diagnosis before recovery. Customized installations may need their documented
manual startup. After the identified repair, recheck readiness and the native
status, then resume only the original target and requested workflow. Preserve
Docker login-startup preferences, scanner restrictions and all client settings.

If a mutating start request fails after forwarding, do not automatically retry
it: the scanner may have accepted it before the connection failed. Establish
its current state before another start. A pure status-only request remains a
direct status call and does not trigger setup or recovery.

## Check an existing connection only

If your scanner is already running, send:

```text
Call zap_passive_scan_status through MCP ZAP Connect once with empty arguments, then stop.
This is a status-only request. Do not call scanner_setup, start or restart containers,
crawl, scan, wait for passive analysis, or generate reports.
Preserve my scanner and all client settings.
```

The connector forwards the scanner's available tools after connecting, including
`zap_passive_scan_status`. Those additional tools are for separately requested
work; exposing them is not permission to use them. A successful passive-status
response through Claude Desktop verifies that connection without starting a
scan. Missing credentials, unavailable tools or an authentication error leave
verification incomplete.

## Build and check the bundle

These commands are for developers with Node.js `>=20.18.1` and npm, run from
`packaging/claude-desktop`:

```bash
npm ci --ignore-scripts --no-audit --no-fund
npm test
npm run build
```

The build stages an explicit set of connector files, production dependencies and
scanner resources. It includes the public `.env.example` template, not a real
`.env`, credentials, scanner workspaces or reports. The scanner resources come
from this checkout; source hashes are included in the bundle. Building this
preview does not publish a release or rebuild the scanner images.

Connector and scanner versions are independent. For example, a future scanner
release `v0.17.0` can include connector `0.1.0`; the asset filenames use the
connector version. After publication, bump the connector version when its packaged
code, dependencies, scanner resources or setup guide change. Ordinary scanner
changes outside the bundle do not require a connector version bump.

Bundled Compose files, scripts and defaults are snapshots of the selected
source, not a promise to install that release's newest scanner. The current
packaged `.env.example` defaults to scanner `v0.16.0`. Update and validate those
resources deliberately when changing the fresh-install scanner version; preserve
the selected settings when reusing an existing scanner.

The output is:

```text
../../build/claude-desktop/mcp-zap-connect-claude-desktop-0.1.0.mcpb
../../build/claude-desktop/mcp-zap-connect-claude-desktop-0.1.0.mcpb.sha256
../../build/claude-desktop/mcp-zap-connect-claude-desktop-0.1.0-setup.md
../../build/claude-desktop/mcp-zap-connect-claude-desktop-0.1.0-setup.md.sha256
```

The setup guide is also inside the MCPB. The build checks package, manifest and
lockfile versions before packaging, then verifies the actual archive contains
the matching guide and unchanged shared scanner resources.

Zod is pinned to `4.6.7` across the runtime SDK and build dependency graph to
address `SNYK-JS-ZOD-20510278`. The explicit override is needed because MCPB
`2.1.2` still declares Zod 3. Tests cover the SDK's bounded-array parser and
the builder's manifest/record validation, packing and unpacking with Zod 4.
The build rejects affected Zod resolutions and checks the actual archive's
runtime version. Keep the override until the build tool supports a patched
dependency without it.

The build-only `tmp` dependency is overridden to `0.2.7` to address the path
traversal and symbolic-link advisories in older releases, including the
non-string path bypass in `0.2.6`. MCPB `2.1.2` still pulls an old `tmp` through
its editor prompt dependency. Tests check the resolved editor dependency,
hostile path options, and normal temporary-file read/write/cleanup behavior.
The build rejects affected root or nested lock resolutions and checks the
embedded archive lockfile. `tmp` and the builder CLI are excluded from the
production runtime.

## Prepare downloads for a normal scanner release

Use the **Claude Desktop MCPB** workflow. It builds preview assets automatically
only on relevant pushes to `main`, and can also be dispatched manually for an
explicit build or draft-release preparation. Ordinary CI runs connector and
security regression tests on dev pushes and pull requests without building
distribution assets. With no draft tag, the packaging workflow produces
maintainer-only Actions artifacts; those expire and are not the public download
route.

Release packaging happens before publication through the manual draft-release
flow below. Publishing an immutable release is not a packaging trigger; all
assets must already be attached when the user publishes it.

Maintainers prepare the assets before publishing the normal scanner release:

1. Include the reviewed connector source and guide in the next normal scanner
   release commit on `main`. Set the scanner `build.gradle` version for that
   release. The existing immutable `v0.16.0` release stays untouched.
2. Create the normal scanner tag, such as `v0.17.0`, and a **draft** GitHub
   release for that existing tag in this repository. The usual scanner
   prerelease tags are also supported; a stable release does not need to be
   marked as a prerelease merely because its connector is a macOS preview.
3. Dispatch **Claude Desktop MCPB** from `main`, setting `draft_release_tag` to
   that scanner tag. The workflow checks out the tag's source, requires the
   commit to be reachable from `main`, and validates that the scanner
   `build.gradle` version matches the tag. It tests and builds the MCPB from
   that source; it does not require the connector version to match the tag.
4. Verify that the existing draft contains all four matching assets:

   ```text
   mcp-zap-connect-claude-desktop-0.1.0.mcpb
   mcp-zap-connect-claude-desktop-0.1.0.mcpb.sha256
   mcp-zap-connect-claude-desktop-0.1.0-setup.md
   mcp-zap-connect-claude-desktop-0.1.0-setup.md.sha256
   ```

   Substitute the connector version if it changes. Review the guide, check
   both downloaded checksums, and document user-relevant compatibility
   requirements and limitations in the normal scanner release notes. Keep
   internal validation records private. The workflow requires an existing mutable
   draft and refuses to overwrite assets; it does not create or publish a
   release. Resolve missing or conflicting assets while the release is still
   a draft before proceeding.
   A failed run can leave some files attached. Inspect the draft and remove
   conflicting files yourself before retrying; the workflow does no automatic
   cleanup or replacement.
5. Publish the normal scanner release only after reviewing all four assets.
   Publication then triggers the existing scanner Docker release workflow.
   That workflow requires all four expected assets to be nonempty and uploaded
   before building images; this check does not validate downloaded bytes and
   cannot repair an incomplete release after immutable publication.
   Verify that workflow's image publication separately before claiming that
   the scanner images are available.

All four assets must be attached **before publication** because published
immutable releases cannot receive new assets. Use the normal scanner tag;
do not create `claude-desktop-v*` tags, separate plugin releases or another
repository for these downloads.

After publication, link users to that specific normal release and its asset
URLs. Do not promise `/releases/latest/download` until the published latest
release actually contains the expected assets; a prerelease does not become
the repository's latest stable release. Confirm the public downloaded checksums
before claiming distribution is complete. Local package tests do not establish
that GitHub attached the files or that public downloads work.

## Remove the extension

Remove MCP ZAP Connect through Claude Desktop's **Settings → Extensions**.
Removing the extension does not stop or delete the scanner, its private settings,
workspace or reports, and does not remove Codex or Cursor connections. Manage the
scanner separately through its existing installation when you want to stop or
remove it.

For support, share the extension and Claude Desktop versions and a redacted
description of the failing step in [GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues).
Do not upload keys, `.env`, client configuration, raw private logs or sensitive
reports. The project is licensed under [Apache-2.0](https://github.com/dtkmn/mcp-zap-server/blob/main/LICENSE) and is not
affiliated with or endorsed by Anthropic, OpenAI, Cursor or ZAP.
