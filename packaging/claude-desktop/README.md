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
version `0.4.0`. Install the `.mcpb` in Claude Desktop; Claude Code plugin commands
do not install this extension. This preview does not establish an Anthropic
directory listing or approval.

## Install the local preview

1. Open [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and
   choose a published **Claude Desktop MCP ZAP Connect** preview, tagged
   `claude-desktop-v<version>`. Download its `.mcpb` asset and matching
   `-setup.md` guide. If no such assets are published, build the local preview
   using the developer steps below. Public distribution is not complete merely
   because local build files exist.
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
requires Node.js `>=20.18.1`; the native test below used Desktop's built-in
Node.js `24.21.0`.

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

Docker and the required host utilities still need to be available. If Docker is
missing, follow the [official Docker Desktop installation instructions](https://docs.docker.com/desktop/setup/install/mac-install/).
This preview uses the local Docker CLI to establish installation ownership and
read its effective key when connecting. The optional key field does not bypass
that requirement. After connecting, tool calls reuse the established MCP session.
Complete downloads, installation, license acceptance, sign-in and operating-system
permission prompts yourself as required. The extension does not silently install
software, approve OS prompts or bypass security controls. After resolving a
prerequisite, ask Claude to check it again before continuing.

Automatic startup is limited to the packaged scripts and Compose configuration.
For a customized installation, the connector may return `START_MANUALLY` instead
of changing or starting it. Follow that installation's own startup procedure,
then ask Claude to check readiness and verify status. Conflicting ports,
credentials or ownership require resolution; they do not authorize replacing an
existing scanner.

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
dependency without it. Rebuilt archives have new checksums; old CI artifacts
containing Zod `3.25.76` should not be published.

The build-only `tmp` dependency is overridden to `0.2.7` to address the path
traversal and symbolic-link advisories in older releases, including the
non-string path bypass in `0.2.6`. MCPB `2.1.2` still pulls an old `tmp` through
its editor prompt dependency. Tests check the resolved editor dependency,
hostile path options, and normal temporary-file read/write/cleanup behavior.
The build rejects affected root or nested lock resolutions and checks the
embedded archive lockfile. `tmp` and the builder CLI are excluded from the
production runtime. Rebuild earlier preview artifacts before publication.

## Prepare a GitHub download

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

For a public preview, first merge the reviewed source and create an existing
tag `claude-desktop-v<package-version>` on that source. Create a **draft**
prerelease titled **Claude Desktop MCP ZAP Connect <version> (macOS preview)**,
with the setup guide or a link to its tagged source in the release notes.
Dispatch the workflow from `main` with that tag in `draft_release_tag`. The tag
must match the package version, its commit must be reachable from `main`, and
the release must still be a mutable draft.

The workflow attaches the MCPB, guide and both checksums without overwriting
existing assets. Review the attached files and validation boundaries before
publishing the draft. Published immutable releases cannot receive new assets.
The Desktop tag namespace is excluded from the scanner Docker publication job;
it does not change the scanner release version or Docker images.

After publication, link users to that specific release and its asset URLs.
Do not use `/releases/latest/download` for Desktop previews: the repository's
latest release is a separately versioned scanner release. Confirm the public
downloaded checksums before claiming distribution is complete.

Package tests and manifest validation establish only their stated boundaries.
An external MCP client exercising the packaged connector is also separate from
Claude Desktop installation, account access, tool loading and a native status
response. No scan or lifecycle operation is authorized merely by building or
testing this package.

On 16 October 2026, bundle `0.1.0` was installed and enabled in Claude Desktop
`2.42304.0` on Apple Silicon macOS `27.0.1`, signed in to a Free account. Desktop
reported its built-in Node.js `24.21.0` with the built-in runtime enabled. Both
optional configuration fields were left blank. Claude discovered the connector
and called `zap_passive_scan_status` once after **Allow once** approval. The
actual tool response reported completed analysis, zero records remaining and
zero active tasks. Only tool discovery and that status call were performed.

The original native-tested artifact SHA256 is
`809c33ec4fd1e550d27c41bd9e5a64d8c1c4dd07be1548ea8d6cb112bc2a56e4`.
It reused the existing `v0.15.0` scanner without lifecycle changes. Later
documentation/packaging rebuilds have their own checksums and do not inherit
exact-artifact native verification. Before/after
checks confirmed unchanged container identities, start times, credentials,
ports and mounts, along with unchanged Codex, Cursor and existing Desktop
configuration bytes and permissions. The 33 automated tests cover the setup
branches; fresh scanner startup and prerequisite installation were not performed
live. Distribution and full scan workflows remain separate validation work.

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
