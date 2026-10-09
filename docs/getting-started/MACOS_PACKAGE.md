# Headless macOS package

The macOS package is an **Apple Silicon local preview**. It bundles MCP ZAP
Server, ZAP and a private Java runtime. Its launcher needs no Docker, system
Java, Git, Gradle, Python or source compilation. Connect an existing MCP client;
the package does not include a chat UI or model.

Runtime verification covers Apple Silicon with macOS 27.0.1. Other macOS
versions still require runtime verification.

Public Mac downloads are not yet available. This preview is unsigned and has
not completed clean browser-download installation verification. Follow macOS's
normal security controls when opening a supplied preview.

## Install and run a preview supplied by a maintainer

In the download folder, verify the supplied archive using its matching checksum
file before unpacking it. Replace `VERSION` with the supplied version:

```bash
shasum -a 256 -c mcp-zap-server-VERSION-macos-arm64-preview.tar.gz.sha256
```

Unpack the archive into a writable location and open a terminal in its extracted
directory:

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

The preview supports HTTP crawling, passive analysis and standard report
creation/readback. Browser/AJAX crawling and browser-login workflows are
unavailable. Inspect `COMPONENTS.json` for the included component versions.
A tool appearing in discovery does not establish that its engine feature is
installed in this package.

Known report limitation: root-URL filtering can omit findings from HTML reports;
see [issue #294](https://github.com/dtkmn/mcp-zap-server/issues/294).
Complete report retrieval does not establish finding-count parity.

HAR, packet-capture and ModSecurity import/export are also unavailable. OpenAPI,
automation and active-scanning workflows require separate package verification.

Automatic ZAP/add-on downloads are disabled. Update through a new verified
package rather than enabling additional add-ons independently.

## Stop, upgrade and remove

```bash
./bin/mcp-zap stop
```

Stop before replacing or moving the extracted package. Stop removes the registered
services as well as their owned processes. Unpack the replacement separately,
keep the same data directory and start it. Back up
settings and reports before upgrades; compatibility must be checked when ZAP
or application versions change.

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

## Build and release instructions

Contributors can use the [macOS maintainer guide](https://github.com/dtkmn/mcp-zap-server/blob/main/packaging/macos/README.md)
for local builds, GitHub Actions downloads and draft-release preparation.
The package's source-delivery materials and dependency notices remain included.
