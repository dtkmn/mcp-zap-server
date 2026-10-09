---
name: setup-local-scanner
description: Set up or reconnect the self-contained Apple Silicon macOS MCP ZAP Server preview in local Codex, using a maintainer-supplied package. Use for installation, startup or local connection troubleshooting.
---

# Set up the local scanner

Help the user reach an authenticated local MCP connection. This is a skills-only
setup prototype: it supplies instructions, not an MCP registration, installer,
running scanner or public download. Explicit user instructions take precedence.

## Obtain the package

- Confirm commands execute on the user's Apple Silicon Mac. A web-only or remote
  execution host does not have access to that Mac's loopback services.
- Use a package or archive the user supplied. A public Mac release asset is not
  available yet; do not invent a URL, build from source or install Docker/Java as
  a substitute. If no package was supplied, explain this specific prerequisite.
- For an archive, obtain its matching checksum from the trusted supplier and
  verify SHA-256 before extracting into a new writable directory. Preserve any
  existing installation. Do not remove quarantine or bypass macOS security.
- Read the supplied package's `MACOS_PACKAGE.md` for its version, supported
  capabilities, settings, stop/upgrade procedure and installation limitations.

## Start and connect

The user's request to set up the package authorizes the ordinary local setup
steps. Follow the host's permissions; ask only for missing information or an
action outside that request. Installing this skill alone does not authorize
running the scanner.

1. Run `<package>/bin/mcp-zap start`. It uses bundled Java, creates private keys,
   waits for readiness and already checks authenticated MCP discovery. Do not
   repeat `status` and `doctor` after a successful start. Use them when diagnosing
   a stopped or existing installation. Do not stop unrelated processes or change
   target permissions to make startup succeed.
2. Run `<package>/bin/mcp-zap client-config codex` with its output redirected
   directly to an owner-only temporary file. **It contains an API key:** never
   return the output in a tool result, prompt, log or final answer. Keep keys in
   local private settings. Do not use `codex mcp login`; this is header-based
   authentication, not OAuth.
3. Merge the generated MCP entry into the user's private Codex configuration,
   preserving unrelated settings and formatting. Read/transform secret contents
   inside a local operation, without printing them. Reuse an existing scanner
   connection to the same loopback endpoint, including `localhost`/`127.0.0.1`
   equivalents. Keep its alias and tool restrictions; update only URL/authentication
   fields and remove stale authentication fields that conflict with the generated
   header. If the existing scanner entry uses a different endpoint, clarify which
   to keep before replacing it; unrelated MCP entries need no such clarification.
   Preserve a private backup when changing an existing file, reject
   linked configuration paths, restrict files to the current user, validate the
   resulting TOML using an available local tool before replacement, and remove
   the temporary secret file. Do not install another runtime just to edit settings.
4. Configuration being written is not proof that this running Codex client has
   loaded it. Check tools available to the current client. If absent, explain the
   required client restart/reconnection and leave that step pending. Do not
   invent a hot-reload operation or restart the user's app without instruction.
5. Once tools are loaded, make a harmless passive-status call through the scanner
   connection just configured, not another preexisting scanner connection.
   A launcher doctor result alone proves server readiness,
   not this client's connection. Finish setup without scanning a target.

Startup must remain usable after the launch command returns. If the command host
ends the background services, report that limitation and leave setup incomplete.
A command session held open for diagnosis is not proof of a persistent install.

For a port conflict, use the documented distinct unprivileged ports; leave other
applications running. Diagnose private logs locally and summarize relevant errors
without exposing keys or raw logs. Use the existing launcher's `stop` command
when the user asks to stop; preserve settings and reports.

Report the package/client versions, readiness, configuration and actual client
connection separately. State any remaining user step. Keep this prototype's
local rehearsal separate from public download, directory approval and fresh-Mac
installation evidence. Scans require the user's target and intended workflow;
active scanning is not a setup check.
