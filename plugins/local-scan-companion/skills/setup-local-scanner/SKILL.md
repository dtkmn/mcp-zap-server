---
name: setup-local-scanner
description: Set up or reconnect local MCP ZAP Server in Codex using the project's Docker Compose release setup. Use for local scanner installation, startup, connection troubleshooting, or stopping its containers. Requires Docker and Compose already installed and running.
---

# Set up the local scanner

Help the user reach an authenticated local MCP connection using the existing
Docker Compose setup. This skills-only plugin supplies instructions; installing
it does not install Docker, start containers or register MCP tools. Explicit user
instructions take precedence.

## Check prerequisites and reuse the project

- Commands must execute on the user's machine, where Codex can reach the
  scanner's loopback endpoint. A remote or web-only execution host does not
  establish access to the user's local scanner.
- Check Docker is installed and its engine is running, Compose supports `--wait`
  and `--wait-timeout`, and Git and a compatible POSIX shell are available.
  Explain a missing prerequisite and stop; do not install Docker, Java or another
  runtime as a substitute. Java and ZAP run inside the containers.
- Reuse a suitable existing `mcp-zap-server` checkout. Otherwise clone
  `https://github.com/dtkmn/mcp-zap-server.git` into a new user-owned directory;
  never overwrite a directory or reset existing changes. Read
  `docs/getting-started/SELF_SERVE_FIRST_RUN.md` before executing setup.
- Use the documented published release image. Do not build from source, switch
  to `latest`, add an installer or create a second Compose configuration.
  Preserve existing `.env`, API keys, workspace files and target restrictions.
  Never print credentials or put them in command arguments, prompts or reports.
- Before startup, check the effective Compose project and the working-directory
  and configuration labels of any containers already belonging to that project.
  Different checkouts can share a project name. If another installation owns it,
  stop and identify that conflict; do not recreate its containers from this copy.
- Check whether inherited environment settings override this checkout's image or
  authentication configuration. Conflicting API keys or authentication modes
  require the user to choose the intended configuration before startup. Stop
  and explain the conflict without printing values. Do not automatically unset,
  replace or ignore inherited credentials, or configure a client from `.env`
  when the containers use a different credential.
- Before running bootstrap, check the host ports published by the Compose file.
  Listeners belonging to this installation are expected on a restart. If any
  other application occupies a required port, stop before bootstrap or client
  configuration; identify the conflict and preserve that application.

## Start and connect

The user's request to set up the scanner authorizes ordinary local setup within
its host's permissions. Ask only for missing information or an action outside
that request. Installing this plugin alone does not authorize execution.

1. From the checkout, run `./bin/bootstrap-local.sh --start`. It prepares private
   settings, starts the versioned release containers, waits for health and runs
   the authenticated MCP doctor. After success, do not repeat doctor checks.
   Continue to client configuration only after successful authenticated readiness.

   On failure, leave Codex settings unchanged. Make one focused diagnostic pass
   using the reported error, container state and private logs. Report setup
   incomplete with the cause and next step. Empty logs or an unexplained exit do
   not establish a cause; keep an unknown cause explicitly unknown. Retry only
   after an identified problem has been corrected within the authorized scope.

   The standard Compose setup uses fixed host ports, documented in its Compose
   file. For a conflict, identify the occupied port and stop setup. Do not stop
   unrelated applications, invent port settings or weaken target restrictions.
   Let the user choose whether to free the port or use a separately configured
   deployment. Do not edit client settings to work around failed readiness.
2. Configure a separate private Codex MCP connection to
   `http://127.0.0.1:7456/mcp`, with `http_headers` containing `X-API-Key` from
   this checkout's `.env` `MCP_API_KEY`. Read and transform secrets inside a local
   operation without printing them. Do not source an existing `.env` as shell
   code. Do not use `codex mcp login`; this connection uses a header, not OAuth.
   If a configured deployment differs from the documented API-key setup, use its
   existing authentication instructions rather than silently changing them.
3. Merge the entry into the user's private Codex configuration, preserving
   unrelated settings and formatting. Reuse the alias of an existing scanner
   connection to the same endpoint, including `localhost`/`127.0.0.1`
   equivalents. Keep its enabled state and tool restrictions. Update only the
   URL and authentication fields; remove stale authentication fields that would
   conflict with the chosen header, including an `env_http_headers` mapping for
   `X-API-Key` and stale bearer/Authorization credentials. If a scanner entry uses a different
   endpoint, clarify which to keep before replacing it.

   Before changing an existing file, make a private backup. Reject linked
   configuration paths, restrict secret files to the current user, and validate
   the resulting TOML using an available local tool before replacement. Preserve
   all unrelated credentials. Remove temporary secret files when finished.
   Do not install another runtime just to edit settings.
4. Written configuration is not proof that this running Codex client loaded it.
   Check the tools available to the current client. If absent, explain the
   required reconnection or restart and leave client verification pending. Do
   not invent a hot-reload operation or restart the user's app without instruction.
5. Once tools are loaded, make a harmless passive-status call through the scanner
   connection just configured, not another preexisting connection. The launcher's
   doctor proves server readiness, not this client's connection. Finish setup
   without scanning a target.

## Stop, restart and report

Containers should remain usable after the startup command returns. If the command
host cannot keep them running, report that limitation and leave setup incomplete.

When asked to stop, run `docker compose stop` from this checkout, after confirming
its containers belong to this installation. Restart with the existing bootstrap
command. Preserve reports and settings. Do not use `--force` for recovery: it
rotates API keys. Do not remove volumes or prune unrelated Docker resources.

Do not print raw `docker compose config` or `config --environment`; they can
contain credentials. Inspect only the fields needed for diagnosis privately.

Report the release and client versions, server readiness, configuration changes
and actual client connection separately. State any remaining user step. Keep
private diagnostics local; share only redacted errors and version information.
Local installation evidence does not establish public-directory approval.
Scans require the user's target and intended workflow; active scanning is never
a setup check.
