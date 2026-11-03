---
name: scan-local-target
description: Check local MCP ZAP scanner readiness before a requested crawl or scan in Codex, Cursor or Claude Code. Use for scanning a local Docker scanner target, including requests to crawl a site and generate a report and recovery after Docker or scanner changes. Preserve the selected client settings and require approval for recovery actions.
---

# Check readiness and run the requested local workflow

Use this skill before every new local MCP ZAP crawl or scan workflow, including
the bundled demo. Work on the scanner's machine through the client the user
requested. A successful installation or earlier scan is not proof of current
readiness. This is an agent workflow; it does not intercept calls sent directly
to a separately configured MCP server.

For a pure status-only request, call `zap_passive_scan_status` once through the
requested client's selected scanner connection and stop. Do not add setup,
preflight commands, recovery or another client's status call to that request.

## Identify the installation and request

- Retain the exact authorized target, crawl strategy, requested operations and
  restrictions. Ask for a target or intended workflow when missing. An HTTP
  crawl and passive analysis do not authorize an active attack.
- Identify the existing scanner checkout, its private `.env`, and the requested
  client's scanner alias and endpoint. Reuse the selected installation; do not
  search for another working scanner as a fallback. If several installations
  or aliases conflict, stop and ask which one to use.
- This skill supports the standard local Docker Compose installation described
  in the verified release's `docs/getting-started/SELF_SERVE_FIRST_RUN.md`; read
  it only after the compatibility check below succeeds. For a native
  package, remote engine or customized deployment, explain the difference and
  follow that deployment's documented readiness procedure. Do not apply Docker
  recovery to a different installation type.
- Preserve every client's settings, disabled states, tool restrictions and
  approvals. A scan request does not authorize configuration edits, key
  replacement, image updates, Docker context changes or starting containers.
  Do not print credentials, source `.env` as shell code or include secrets in
  command arguments.

## Read-only preflight

1. First run the installed plugin's bundled
   `scripts/check-scanner-release.sh --directory <absolute-scanner-directory>`
   with Bash and a bounded command deadline. Resolve the script relative to this
   skill's installed plugin directory, not inside the scanner checkout. It
   checks the supported `v0.17.1` checkout/commit and effective scanner image
   without updating anything; see the setup skill for the exact compatibility
   target. Require exit 0 and `COMPATIBLE`. If it blocks or times out, preserve
   the selected installation and use its own deployment procedure; do not fetch,
   switch versions, replace files or change client settings to make it pass.

   From that compatible scanner checkout, run `./bin/scanner-readiness.sh` with a
   bounded command deadline. Docker requests have a 15-second timeout each;
   an overall command timeout also blocks the workflow.
   This check must precede any new crawl or scan tool call. It checks the
   current local Docker endpoint, Compose ownership, settings, container state
   and authenticated readiness using the doctor with its tool-call probe
   disabled. It does not create settings, pull images, start containers or scan
   a target. If the helper is unavailable, stop and explain that this checkout
   needs the readiness-capable release; do not download or replace it silently.
2. Continue only after the helper returns `READY`. A nonzero exit, timeout or
   missing result blocks the workflow. Explain the reported category and next
   step without exposing private logs or key values. Follow the recovery rules
   below instead of trying a scan to see whether it works.
3. Prove the current client's connection by calling `zap_passive_scan_status`
   once with `{}` through its selected scanner alias. A doctor response, tool
   list, terminal HTTP request, or status from another client is not this
   proof. Check that the native call returns successfully; completion or backlog
   values describe existing passive work and are not permission to start more.
4. If tools are absent, authentication fails or the client connection is stale,
   stop before any crawl or scan. Offer reconnection of this server, preserving
   settings and approvals. Codex may need a new session or app restart. In
   Cursor, use the selected server's Reload control under **Customize → MCPs**
   when available. In Claude Code inspect `/mcp` and use its reconnect action
   when offered, otherwise request a new local session. Do not restart the
   user's app automatically. After the identified connection fix, recheck
   preflight and make one new native status call; do not retry in a loop.

## Recover only the identified problem

- **Docker missing or stopped:** explain the cause. Ask the user to install or
  open their Docker application, or offer to open the existing application with
  their explicit approval. Follow the setup skill's prerequisite instructions
  for approved installation work. Do not install a competing engine, change
  startup-at-login settings, change Docker contexts or weaken permissions. Wait
  a bounded time after an approved start, then run preflight again. If it still
  fails, report the remaining blocker and stop.
- **Known scanner containers stopped or removed, with intact private settings:**
  offer to run the existing `./bin/bootstrap-local.sh --start` from the confirmed
  checkout after approval. Confirm the helper found no ownership, port,
  environment, context or configuration conflict. Use the existing `.env` and
  Compose file. A stopped/missing diagnosis does not replace startup checks:
  follow the setup skill's current port, project ownership and inherited-setting
  checks before bootstrap. Run the bundled release compatibility check again
  immediately before bootstrap. If it does not report `COMPATIBLE`, explain the
  manual recovery path instead of executing checkout helpers. Never use
  `--force`, delete settings, remove volumes or prune
  resources. Explain that the existing bootstrap may obtain missing published
  images and start bundled demo services. Recheck preflight after recovery and
  verify the selected client's status before proceeding.
- **Missing settings:** stop the scan and offer the setup skill as a separate
  approved setup action. Do not create a replacement installation or new keys
  as connection recovery.
- **Unexpected Docker context/endpoint, another installation's containers,
  occupied ports, inherited configuration conflicts or customized startup:**
  stop and identify the conflict. Do not switch contexts, stop unrelated
  applications, recreate containers or overwrite configuration. Ask the user
  which installation or manual repair they intend.
- **Authentication or client-key mismatch:** stop and explain that credentials
  or the client connection need diagnosis. Never rotate a key or silently
  update client settings. Any credential repair requires a separate explicit
  approval identifying the one client and field to change; preserve all other
  clients and settings. Re-run preflight and native status after that repair.
- **Unknown or unhealthy state:** report the uncertainty and the next focused
  diagnostic step. Do not relabel a timeout as an authentication error or
  repeatedly restart software. Keep private diagnostics outside public source,
  guides, manifests and release assets.

Reuse approval that explicitly covers the identified recovery action. If no
recovery is approved, stop with the required manual step. Approval for a scan
alone is not approval for repair. After approved recovery, resume only the
original target and workflow; if the user asked for verification only, stop
after the status response.

## Run only the requested workflow

After preflight and the client's status check succeed, translate the user's
request into crawl calls. Keep the exact authorized target URL for every pass.

- **“Crawl this site”**: call `zap_crawl_start` once with `strategy="http"`.
- **“Crawl this site thoroughly”**: call `zap_crawl_start` with `strategy="http"`. Only
  after its status confirms success, call `zap_crawl_start` once on the same target with
  `strategy="client"` so Client Spider can explore pages rendered by JavaScript.
  Keep each pass's Operation ID separate. If using idempotency keys, use a
  different key for each pass.
- Explicit limits take precedence. “HTTP only” means one HTTP pass. A request
  for Client Spider alone uses `strategy="client"`; AJAX Spider uses
  `strategy="browser"`. Neither crawl request authorizes active scanning.
  For authenticated crawling, use an authorized form-login session for HTTP
  and an authorized browser session for Client Spider. If the needed session,
  browser or add-on is unavailable, report which pass could not run. Never
  switch to anonymous crawling or a different spider to finish it.

Poll `zap_crawl_status` with the Operation ID returned by each start. A queued
HTTP pass succeeds only when its status reply contains an unqualified
`Status: SUCCEEDED`. While it is `QUEUED` (including waiting for capacity),
`STARTING` or `RUNNING`, poll the same ID. For a direct HTTP crawl on v0.17.1,
`Completed: no` means keep polling and `Completed: yes` confirms completion.
A start acknowledgement or progress percentage alone does not establish success.

Stop the sequence on failure, denial, timeout, missing status, or cancellation
(including pending or unconfirmed cancellation). If a start returns no ID or
a job is held with `START_OUTCOME_UNKNOWN`, report the uncertainty. Do not
submit a replacement or start the second pass. If the user asks to stop, end the sequence and call
`zap_crawl_stop` with the current pass’s Operation ID if it is still outstanding,
even if the next pass would otherwise be eligible. Report a Client Spider result marked
stopped or outcome unknown as such, even when progress reaches 100%.

Follow the server's Next Actions within the user's request. For a crawl or active
scan already in progress, poll its existing Operation ID. Generate or retrieve
reports only when requested. Preserve target restrictions and
client settings throughout. The packaged local client uses the `*` grant for the standard
scanner tools. Use them only for the requested target and workflow. Preserve any explicit per-client restrictions.
If a required tool returns `insufficient_scope`, stop that operation and explain
its missing permission using the checkout's scope guide. A scan request, a tool
appearing in discovery or a Next Action does not authorize widening scopes,
adding `*` or disabling enforcement. Do not substitute
an active attack for a permitted crawl or retry the denied operation in a loop.

If Docker, authentication or transport fails during the workflow, stop and
diagnose the new state. Never automatically retry a mutating tool such as
`zap_crawl_start` or an active-scan start: the server may have accepted the first
request before the connection failed. Explain that uncertainty, check current
state with read-only tools when available, and obtain the user's direction
before any new start.
