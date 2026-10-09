# Headless macOS package

The macOS package is an **Apple Silicon local preview**. It bundles MCP ZAP
Server, ZAP and a private Java runtime. Its launcher needs no Docker, system
Java, Git, Gradle, Python or source compilation. Connect an existing MCP client;
the package does not include a chat UI or model.

This preview is not yet a public signed/notarized download. A direct run on a
development Mac does not prove a quarantined installation on a fresh Mac.
Do not bypass Gatekeeper to distribute an unverified artifact.

## Install and run a preview supplied by a maintainer

Unpack the matching `mcp-zap-server-VERSION-macos-arm64-preview.tar.gz` archive
into a writable location and open a terminal in its extracted directory:

```bash
./bin/mcp-zap start
```

The launcher starts only its bundled Java, binds the MCP and ZAP listeners to
`127.0.0.1`, waits for readiness, and checks authenticated MCP initialization,
tool discovery and a harmless passive-status call. A failed start stops its
owned processes and returns an error. Other applications using the requested
ports are not stopped. Do not interpret a running process as a ready server.

Run `status` to check whether the services are running, or `doctor` to diagnose
an existing connection. A successful `start` already performs the authenticated
readiness checks.

Start from a logged-in macOS desktop session. The launcher uses macOS's built-in
service manager for both processes, so they keep running when the terminal or
agent setup command finishes. They stop when you run `stop` or log out. The
package does not install login items or automatically restart a crashed service.
Use `status` and the private logs to diagnose a crash, then `stop` and `start`
to retry.

Default endpoint: `http://127.0.0.1:7456/mcp`. ZAP's private API uses port `8090`;
its OAST callback, if started by ZAP, uses an ephemeral loopback port.

Both API keys are generated independently at first start. To configure a
client with custom HTTP headers:

```bash
./bin/mcp-zap client-config
```

**This command displays the MCP key.** Copy it only into private client settings.
Do not paste it into a conversation or share its output. See
[MCP Client Authentication](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/)
for client-specific configuration. A client must support Streamable HTTP,
the `X-API-Key` header and MCP sessions.

For Codex on the same Mac, generate its configuration directly:

```bash
./bin/mcp-zap client-config codex
```

Merge the printed TOML entry into your private `~/.codex/config.toml`, preserving
other entries. Replace an existing `mcp-zap-server` entry rather than adding a
duplicate. **This output includes the MCP key.** Keep it out of prompts and
public files, restrict the configuration file to your user, and restart Codex.
No OpenAI tunnel or separate tunnel runtime credential is required for this
direct local connection. Approve only tool calls for your authorized target
and intended workflow; client tool approvals are separate from MCP authentication.

On 2026-10-08, Codex CLI 0.161.0 connected directly to this preview on Apple
Silicon macOS 27.0.1. Seven allowed workflow tools, with automatic client approval
review, completed an owned local HTTP crawl, passive processing, HTML generation
and full chunk retrieval. The returned checksum matched the independently
inspected local file. This verifies the CLI route. Complete file transport does not prove report
finding-count parity: root-URL filtering is tracked in
[issue #294](https://github.com/dtkmn/mcp-zap-server/issues/294).

On 2026-10-09, a maintainer reported the desktop HTTP workflow working on a
second Mac (M4 Pro, macOS 27.0.1). Screenshots show the connected Codex desktop
client and a rendered ZAP HTML report. Codex reported a completed HTTP crawl,
passive analysis, complete MCP report retrieval and SHA-256 verification; no
active scan was reported. The underlying tool trace and checksum were not
independently reviewed here, and the Codex desktop version was not recorded.
The archive arrived through AirDrop, and Java and Docker were already installed
on that Mac. This records a maintainer-reported second-Mac desktop workflow;
clean-environment and browser-downloaded installation checks remain separate.

ChatGPT has separate connection prerequisites. See
[Private ChatGPT Connection](CHATGPT_LOCAL_TUNNEL.md); a local HTTP endpoint
alone is not reachable by ChatGPT's cloud service.

## Settings, reports and resources

Private state is stored in `~/Library/Application Support/MCP ZAP Server/`:

| Location | Purpose |
| --- | --- |
| `settings.conf` | Ports, heap limits, startup deadline and allowed-target settings |
| `credentials.env` | Two generated keys; never source it as shell code |
| `reports/` | Report workspaces and automation storage |
| `zap-home/` | ZAP configuration and local state |
| `logs/` | Private process diagnostics; treat them as potentially sensitive |
| `run/` | Launcher ownership, private service definitions and temporary configuration |
| `tunnel/` | Optional private ChatGPT connection profile |

To move all state, set `MCP_ZAP_DATA_DIR` to an absolute directory before each
command. Do not point it at a shared or linked directory. Keep the same state
directory during ordinary upgrades. Credentials, settings and reports survive
stop and replacement of the package. Completed report files are persistent;
the default scan queue/history remain in memory and are not promised to survive
an MCP restart.

After moving or replacing the package, regenerate the private Codex entry if
you changed ports or rotated the MCP key. Its URL/header configuration does not
depend on the package's extracted folder location.

Stop before changing `settings.conf`, then start again. The defaults allocate
up to 512 MiB to MCP and 2 GiB to ZAP's Java heaps, plus native memory. Leave
additional free memory for macOS and the chosen client. These are preview
defaults, not a measured concurrency or enterprise capacity recommendation.
The default startup deadline is 180 seconds.

Local/private-network targets are blocked by default. For an authorized local
test, explicitly change the two `ZAP_ALLOW_*` settings and use
`ZAP_URL_WHITELIST` to restrict permitted hosts. An allowlist does not establish
target ownership or restrict the underlying engine's network access. Use only
targets you are authorized to test.

## Capabilities and limitations

The initial package targets HTTP crawling, passive analysis and standard
report generation/readback. Browser/Selenium/client-spider add-ons and their
mandatory dependents are excluded: they can create browser profiles or start
additional callbacks during initialization. Inspect `COMPONENTS.json` for the
exact included/excluded versions. Do not request browser or browser-login
workflows with this package. The server's general tool list is not a promise
that every engine feature is installed in every distribution.

The optional Import/Export (`exim`) add-on is also excluded from this preview.
Its historical dependency source mapping requires a separate review before
redistribution. HAR, packet-capture and ModSecurity import/export features
provided by that add-on are unavailable. The OpenAPI add-on is retained;
its presence does not establish a verified OpenAPI workflow for this package.

Automatic ZAP release/add-on downloads are disabled. The required HTTP runtime
is included in the artifact. Engine/add-on updates should arrive through a new
verified package; do not enable arbitrary add-ons in this preview and assume
the same listener, startup or browser guarantees still hold. Automation,
OpenAPI content import, active scanning and other workflows require their own
verification before advertising feature parity with Compose.

## Stop, upgrade and remove

```bash
./bin/mcp-zap stop
```

Stop before replacing or moving the extracted package. Stop removes the registered
services as well as their owned processes. Unpack the replacement separately,
keep the same data directory and start it. Back up
settings and reports before upgrades; compatibility must be checked when ZAP
or application versions change.

On 2026-10-08, replacing the 0.15.0 preview with a separately extracted copy
at a different location preserved credentials, settings and an HTML report
byte-for-byte. Authenticated full report retrieval worked after replacement.
Removing both stopped package copies retained the separate user data, which
was deleted only in a separate explicit removal step. This verifies
same-version replacement and removal on the tested Mac; compatibility across
application or engine versions still needs its own check.

Removal means stopping the services and deleting the extracted package. User
state is retained. Removing the state directory is a separate, destructive
choice that deletes keys, reports and ZAP state; it is never part of ordinary
stop or upgrade. Stop an optional foreground tunnel client before removal.

## Troubleshooting

If startup fails, read private logs locally rather than posting them unredacted.
For a port conflict, choose two distinct unprivileged ports in `settings.conf`.
If one owned service remains after interruption, use stop and retry. Doctor
does not bypass authentication or repair target permissions. Changing the MCP
key also requires updating every client that uses it.

If the service manager cannot register a process, confirm you are logged into
the Mac's desktop and the extracted package is still at its original location.
An SSH session without a logged-in desktop is not supported by this preview.

## Maintainer build and public distribution gate

On an Apple Silicon Mac with Python 3 and the project's build toolchain:

```bash
./gradlew bootJar
python3 packaging/macos/build-package.py --server-jar build/libs/mcp-zap-server-VERSION.jar
```

The builder downloads pinned binary and source inputs, verifies SHA-256,
preserves the full vendor JRE bundle and component notices, and records
input/add-on versions and checksums. It creates four files under `build/macos/`:

- `mcp-zap-server-VERSION-macos-arm64-preview.tar.gz`: the runnable package;
- `mcp-zap-server-VERSION-macos-arm64-preview-sources.tar.gz`: matching source
  and build materials, not required to run the package;
- a `.sha256` checksum file for each archive.

Publish all four files together in the same release. The binary package includes
`SOURCE_DISTRIBUTION.md`, `SOURCE_MANIFEST.json`, `DISTRIBUTION_NOTICES.md` and
`SERVER_THIRD_PARTY_NOTICES.md`. The companion records the binary archive's
checksum and the delivered source checksums. Its upstream source archives
include source/build scripts and notices; selected archives omit unrelated
vendored binaries and the obsolete coverage-tool bundle. Source/build materials
and notices for the shipped libraries remain, and every omission is recorded
explicitly.
See [source delivery and library replacement](https://github.com/dtkmn/mcp-zap-server/blob/dev/packaging/macos/SOURCE_DISTRIBUTION.md)
for the contents and maintainer review requirements.

The builder rejects changes to the reviewed binary inputs, retained ZAP archive
inventory, source-bearing server library hashes or complete server dependency
inventory. Review matching sources and notices before changing those pins.
`--cache-dir` can reuse verified downloads; `--output-dir` selects a new output
location. These are build-time requirements only.

Before publishing an unsigned preview: verify corresponding-source/redistribution
compliance, clearly label the package unsigned, document its tested capabilities
and installation limitations, and verify the actual browser-downloaded artifact
in a clean environment without Docker or system Java. Retest runtime functionality
and preservation with the final artifact. Signing and notarization are a separate
distribution milestone, not a requirement simply to make an unsigned preview
publicly downloadable.

For a signed/notarized edition, sign the final distributable with the appropriate
Developer ID workflow, notarize it and verify the actual downloaded/quarantined
artifact. Do not treat the bundled vendor Java signature as signing/notarization
of the whole product.

Native libraries are also nested inside server dependencies and ZAP add-ons.
Their signatures and applicable hardened-runtime requirements must be handled
before submitting the complete distributable. A successful signature check of
the JRE alone does not establish that the payload can be notarized. The eventual
signed/notarized distribution container also needs a supported notarization/stapling workflow and
verified corresponding-source delivery for components whose licenses require it.

- [Temurin release and source assets](https://github.com/adoptium/temurin25-binaries/releases)
- [ZAP releases](https://github.com/zaproxy/zaproxy/releases)
- [Apple Developer ID distribution](https://developer.apple.com/developer-id/)
