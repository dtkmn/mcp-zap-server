---
name: setup-local-scanner
description: Set up or reconnect local MCP ZAP Server in Codex or Cursor using the project's Docker Compose release setup. Use for local scanner installation, startup, connection troubleshooting, or stopping its containers. Requires Docker and Compose already installed and running.
---

# Set up the local scanner

Help the user reach an authenticated local MCP connection using the existing
Docker Compose setup. This skills-only plugin supplies instructions; installing
it does not install Docker, start containers or register MCP tools. Explicit user
instructions take precedence.

Configure only the client the user requested. A Cursor setup must leave Codex
settings unchanged, and a Codex setup must leave Cursor settings unchanged.
For a pure status-only request through an existing connection, go directly to
that client's passive-status call; setup prerequisites must not block it.

## Check prerequisites and reuse the project

- Commands must execute on the user's machine, where the requested client can reach the
  scanner's loopback endpoint. A remote or web-only execution host does not
  establish access to the user's local scanner.
- For setup or restart, check Docker is installed and its engine is running,
  Compose supports `--wait` and `--wait-timeout`, and a compatible POSIX shell
  is available. Check Git when cloning the project is needed.
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
  Map Docker-managed listeners to the confirmed project's published ports;
  a Docker process name alone does not establish installation ownership.

## Start and connect

The user's request to set up the scanner authorizes ordinary local setup within
its host's permissions. Ask only for missing information or an action outside
that request. Installing this plugin alone does not authorize execution.

1. Reuse a healthy running installation without restarting its containers.
   For status-only verification of an existing connection, skip startup and
   configuration changes and proceed to the client status call.
   For setup against a running installation, use the existing
   `./bin/self-serve-doctor.sh` once to establish authenticated readiness before
   editing client settings, unless authenticated readiness for the current
   endpoint and effective credential has already been proved during this setup.
   Historical success or container health alone is insufficient.

   For first setup or a requested restart, run `./bin/bootstrap-local.sh --start`
   from the checkout. It prepares private
   settings, starts the versioned release containers, waits for health and runs
   the authenticated MCP doctor. After success, do not repeat doctor checks.
   Continue to client configuration only after successful authenticated readiness.

   On failure, leave client settings unchanged. Make one focused diagnostic pass
   using the reported error, container state and private logs. Report setup
   incomplete with the cause and next step. Empty logs or an unexplained exit do
   not establish a cause; keep an unknown cause explicitly unknown. Retry only
   after an identified problem has been corrected within the authorized scope.

   The standard Compose setup uses fixed host ports, documented in its Compose
   file. For a conflict, identify the occupied port and stop setup. Do not stop
   unrelated applications, invent port settings or weaken target restrictions.
   Let the user choose whether to free the port or use a separately configured
   deployment. Do not edit client settings to work around failed readiness.
2. Use a private MCP connection to `http://127.0.0.1:7456/mcp` with `X-API-Key`
   from this checkout's `.env` `MCP_API_KEY`. Read and transform secrets inside a local
   operation without printing them. Do not source an existing `.env` as shell
   code. Do not use `codex mcp login`; this connection uses a header, not OAuth.
   If a configured deployment differs from the documented API-key setup, use its
   existing authentication instructions rather than silently changing them.
3. Merge the entry into the requested client's private user configuration, preserving
   unrelated settings and formatting. Reuse the alias of an existing scanner
   connection to the same endpoint, including `localhost`/`127.0.0.1`
   equivalents. Keep its enabled state and tool restrictions. Update only the
   URL and authentication fields. If a scanner entry uses a different endpoint,
   clarify which to keep before replacing it.

   - **Codex:** use its existing user configuration (`~/.codex/config.toml`, or
     its configured `CODEX_HOME`) and `mcp_servers.<alias>.url` with
     `http_headers` containing `X-API-Key`. Remove a stale `env_http_headers`
     mapping for that header and conflicting bearer/Authorization credentials
     only from this scanner entry.
   - **Cursor:** use `~/.cursor/mcp.json`, with `mcpServers.<alias>.url` and
     `headers` containing `X-API-Key`. Preserve unrelated servers, headers and
     client approval settings; remove conflicting Authorization credentials only
     from this scanner entry. If a project `.cursor/mcp.json` entry shadows or
     duplicates it, stop and clarify which connection to use before editing
     configuration; preserve the project file. Never
     write a literal key into a shared project file. HTTP connections do not
     load `.env` via `envFile`; `${env:MCP_API_KEY}` works only when Cursor
     inherits that variable. Use the private user header when that environment
     has not been established. Do not configure OAuth login for API-key mode.
     See [Cursor MCP configuration](https://cursor.com/docs/mcp#using-mcpjson).

   Before changing an existing file, make a private backup. Reject linked
   configuration paths, restrict secret files to the current user, and validate
   the resulting TOML or JSON using an available local tool before replacement. Preserve
   all unrelated credentials. Remove temporary secret files when finished.
   Do not install another runtime just to edit settings.
4. Written configuration is not proof that the requested client loaded it.
   Check the tools available in that client's own session. A call from the
   command host's different client is not proof of the requested connection.
   If absent, explain the
   required reconnection or restart and leave client verification pending. Do
   not invent a hot-reload operation or restart the user's app without instruction.
   In Cursor, check Customize → MCPs for the selected server. After a Docker
   restart, an existing session may show a connection error; open that server's
   configuration and use its Reload control when available, then repeat only
   the passive-status call. If that does not reconnect it, explain the remaining
   client step. Invoke the skill explicitly with `/setup-local-scanner`;
   OpenAI's plugin onboarding metadata does not establish Cursor skill activation.
5. Once tools are loaded, make a harmless passive-status call through the scanner
   connection just configured, not another preexisting connection. The launcher's
   doctor proves server readiness, not this client's connection. Finish setup
   without scanning a target.

## Stop, restart and report

Containers should remain usable after the startup command returns. If the command
host cannot keep them running, report that limitation and leave setup incomplete.

When asked to stop, use the same project directory, `.env` and explicit
`docker-compose.yml` selection as the bootstrap, after confirming the selected
containers belong to this installation. From the checkout, run
`docker compose --project-directory "$PWD" --env-file "$PWD/.env" -f "$PWD/docker-compose.yml" stop`.
Do not let an inherited `COMPOSE_FILE` select another configuration.
Restart with the existing bootstrap
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

## Offer the first demo workflow

After a setup request finishes with a verified client connection, offer one
ready-to-copy prompt for the bundled local Juice Shop demo. Run it only after a
separate user request. Omit this offer for status-only verification,
troubleshooting or stop requests; leave it pending if client verification is
incomplete.

```text
Run only a guided HTTP crawl against http://juice-shop:3000.
Follow the server's Next Actions and poll until the crawl is complete.
Wait for passive analysis to finish, summarize findings for this target,
generate an HTML report scoped to that target, and retrieve the complete
report through MCP.
Do not start an active scan.
```
