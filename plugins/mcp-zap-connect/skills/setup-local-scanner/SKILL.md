---
name: setup-local-scanner
description: Set up or reconnect local MCP ZAP Server in Codex, Cursor or Claude Code using the project's Docker Compose release setup. Use for local scanner installation, startup, connection troubleshooting, or stopping its containers. Help install missing prerequisites with approval, then recheck and continue setup.
---

# Set up the local scanner

Help the user reach an authenticated local MCP connection using the existing
Docker Compose setup. This skills-only plugin supplies instructions; installing
it does not install Docker, start containers or register MCP tools. Explicit user
instructions take precedence.

Configure only the client the user requested. Leave the other clients' settings
unchanged. Claude Code means a local session on the scanner's machine; a Claude
web chat or cloud coding session cannot reach that machine's loopback endpoint.
For a pure status-only request through an existing connection, go directly to
that client's passive-status call; setup prerequisites must not block it.

## Check prerequisites and reuse the project

- Commands must execute on the user's machine, where the requested client can reach the
  scanner's loopback endpoint. A remote or web-only execution host does not
  establish access to the user's local scanner.
- For setup or restart, identify the host OS and architecture. Check Bash,
  Docker with a reachable engine, and Compose supporting `--wait` and
  `--wait-timeout`. Check Git when cloning is needed, OpenSSL when creating new
  settings, and the bootstrap/doctor utilities documented in the project.
  Java and ZAP run inside containers; no host Java installation is required.
- If a prerequisite check fails, follow the recovery steps below before
  continuing. Preserve existing installations and the selected Docker context.

### Resolve missing prerequisites

- Distinguish missing software from a missing PATH entry, a stopped engine or
  an access error. Locate an installed executable or Docker Desktop first;
  use its full path or a session-only PATH adjustment before proposing another
  installation. Do not change shell startup files or Docker contexts as a shortcut.
- Explain the missing tool and propose the specific download, installation or
  update, including any administrator access or Docker restart it requires.
  Obtain approval before downloading or installing prerequisites, updating
  software or restarting an existing engine. Reuse explicit approval already
  given for those steps; a scanner setup request alone does not authorize
  software installation. If approval is declined, leave setup incomplete and
  provide the manual next step.
- On macOS, follow the official [Docker Desktop installation guide](https://docs.docker.com/desktop/setup/install/mac-install/),
  matching the host's chip and supported OS. Docker Desktop includes Compose;
  do not install a competing engine to repair its CLI or Compose. For missing
  Git, use the [official macOS installation options](https://git-scm.com/install/mac),
  such as Apple's Command Line Tools or an existing package manager. Do not
  install another package manager just to complete setup. For other systems,
  use the vendor's platform-specific [Docker/Compose](https://docs.docker.com/compose/install/)
  and [Git](https://git-scm.com/install/) instructions; state that this project's
  verified onboarding is on Apple Silicon macOS.
- Carry out only approved steps within the host's permissions. Leave license
  acceptance, account sign-in, password entry and OS security prompts to the
  user. If a step requires their interaction or an app restart, explain it and
  wait for completion. Preserve macOS quarantine and security checks. A
  download or installer exit is not proof that the tool is usable.
- If Docker is installed but stopped, start the existing application as part
  of the requested setup and wait a bounded time for its engine. For an access
  error, diagnose the selected endpoint and permissions before proposing a
  repair; do not run setup as root or change permissions automatically.
- After approved changes or the user's manual step, rediscover executable
  paths and recheck Bash, required utilities, Git when needed, `docker info`,
  `docker compose version`, and both flags in `docker compose up --help`.
  Continue the original setup below only when the needed checks pass. If they
  still fail, explain the remaining blocker; retry after an identified fix,
  rather than repeatedly installing software or changing client settings.
- A loaded Desktop skill does not require a separate Codex CLI installation
  for scanner setup. The direct GitHub plugin installation commands need the
  CLI before this skill is available. For that prerequisite, follow
  [Check The Codex CLI](https://danieltse.org/mcp-zap-server/getting-started/self-serve-first-run/#check-the-codex-cli):
  check the desktop app's bundled CLI first, then guide an approved installation
  only if needed.

### Reuse the project

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
   code. Do not use OAuth login commands; this connection uses an API-key header.
   If a configured deployment differs from the documented API-key setup, use its
   existing authentication instructions rather than silently changing them.
3. Merge the entry into the requested client's private user configuration, preserving
   unrelated settings and formatting. Reuse the alias of an existing scanner
   connection to the same endpoint, including `localhost`/`127.0.0.1`
   equivalents. Keep its enabled state and tool restrictions. Update only the
   transport, URL and authentication fields. If a scanner entry uses a different
   endpoint, clarify which to keep before replacing it.

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
   - **Claude Code:** use its private user MCP configuration (normally
     `~/.claude.json`), with `mcpServers.<alias>.type` set to `"http"`, `url`
     set to the endpoint above and `headers` containing `X-API-Key`. An HTTP
     entry requires the explicit `type`; a URL alone is treated as stdio.
     Resolve any `CLAUDE_CONFIG_DIR` override using the installed client's
     documented configuration location before editing; do not create a parallel
     default configuration. Inspect the active Claude project, which may differ
     from the scanner checkout: its shared `.mcp.json` and private local-scope
     entries in `projects.<project-path>.mcpServers` can shadow a user entry.
     Reuse a matching private local-scope connection in its existing scope;
     otherwise add a user-scope entry. If a shared or managed definition
     conflicts or duplicates the scanner, stop and clarify which connection to
     use. Preserve project files, disabled-server choices and tool permissions.
     Never enable a disabled connection or widen approvals automatically.
     Read and merge the key inside a local operation; do not pass it in
     `claude mcp add --header` or `add-json` arguments. `${MCP_API_KEY}` in
     `headers` works only if the Claude process inherits that variable; the
     scanner's `.env` is not loaded by an HTTP entry. When inheritance has not
     been established, store the header only in the private configuration.
     See [Claude Code MCP configuration](https://code.claude.com/docs/en/mcp).

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
   In Claude Code, invoke `/mcp-zap-connect:setup-local-scanner` and use `/mcp`
   to inspect the selected server. If the panel offers a reconnect action, use
   it for this connection; otherwise ask the user to start a new local Claude
   Code session. Do not treat `claude mcp list`, a doctor response or another
   client's status call as native Claude tool verification.
5. Once tools are loaded, call `zap_passive_scan_status` through the scanner
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
