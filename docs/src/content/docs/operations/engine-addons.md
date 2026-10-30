---
title: "Update ZAP Add-ons with an MCP Client"
editUrl: false
description: "Ask an LLM to inspect, update and verify an approved ZAP add-on through its native MCP connection, with explicit operator approval."
---

Ask the LLM in your MCP client to inspect an installed ZAP add-on, wait for your
approval, update it and verify what ZAP loaded. You type the prompts below; the
LLM selects and calls the server's native MCP tools. You do not need to issue
JSON, HTTP or shell commands to perform the add-on workflow.

The tools are available in the guided catalog. No target
scan is required. Updates are disabled by default and must first be enabled by
the operator; a prompt cannot grant the LLM maintenance authority.

| Tool | Purpose | Required scope |
| --- | --- | --- |
| `zap_addon_inspect` | Inspect an installed add-on, the engine's Marketplace candidate, and requested passive rules | `zap:engine:addons:read` |
| `zap_addon_update` | Update one approved installed add-on and verify the result | `zap:engine:addons:update` |
| `zap_addon_update_status` | Read loaded readiness and the recorded maintenance outcome | `zap:engine:addons:read` |

The [reusable prompt pack and illustrative conversation](https://github.com/dtkmn/mcp-zap-server/tree/main/examples/addon-maintenance)
provide a shorter version of this workflow.

## Before You Start

- **Run a server build containing the tools above.** Pushing source to a branch
   does not replace a running image, JAR or packaged scanner. For a source build,
   follow [Development Setup](https://github.com/dtkmn/mcp-zap-server/blob/main/CONTRIBUTING.md#local-stack).
   For a packaged installation, choose a build that exposes all three tools.
- **Have the operator enable maintenance once.** Follow
   [Enable Updates for a Dedicated Local Engine](#enable-updates-for-a-dedicated-local-engine)
   and apply that configuration to the MCP process. This may require replacing
   the MCP process or container through the normal deployment procedure; it is
   separate from the add-on operation. The add-on tools do not apply their own
   server configuration or restart ZAP.
- **Connect the intended operator client.** Use its native MCP connection and
   the credential for the allowlisted operator identity. Follow
   [MCP Client Authentication](../../getting-started/mcp-client-authentication/)
   for your client, preserving its other settings. Rediscover the server's tools
   after a runtime change. Confirm discovery and inspection through that
   client's own connection before requesting an update.

## Ask the LLM to Inspect, Update and Verify

Use these prompts in order in the same MCP client conversation. Substitute its
configured scanner alias if it is not `mcp-zap-server`. Never paste an API key or
bearer token into a prompt. The LLM must use actual discovered tools and their
results; it must not invent a result or fall back to direct HTTP, scripts or
shell commands when the MCP connection is unavailable.

### 1. Inspect Only, Then Wait

```text
Use only the native mcp-zap-server connection. Discover its add-on maintenance
tools and inspect the installed pscanrules add-on and passive rule 20020.
Show the actual installed and local versions, Marketplace candidate, rule state,
updatesEnabled and quarantine state. Tell me whether an update is needed for
PHP2Shell version detection. Do not update, change settings, crawl, scan or
restart anything. If the tools or connection are unavailable, stop and explain.
Wait for my explicit approval before any update; do not use HTTP or shell fallbacks.
```

Review the LLM's report against the tool result shown by your client. It should
have called `zap_addon_inspect` with `pscanrules` and rule `20020`, without an
update call. Seeing a tool name or running a separate HTTP test does not verify
that your selected client can discover and call it.

If the loaded version already meets your requirement, skip installation and
use the verification prompt with that installed version. The update tool
rejects an equal or older candidate. A missing candidate or unknown inventory
is a reason to stop, not permission to guess a version.

### 2. Approve One Observed Update

Replace both placeholders with the exact version strings from inspection, then
send this prompt only if you approve that update and its possible dependencies:

```text
I approve updating only the already installed pscanrules add-on from
<observed installed version> to <observed candidate version>, including any
Marketplace dependency additions or updates. Use only the same native
mcp-zap-server connection and those exact versions. Acknowledge dependency
changes in the update request and include passive rule 20020 for verification.
Send at most one update request. Do not enable maintenance, widen permissions,
change other settings, restart anything, crawl or scan. If the server refuses
or completion is uncertain, stop and report it; do not retry or use a fallback.
Show the returned outcome and inventory changes without claiming target safety.
```

The expected mutation is one `zap_addon_update` call. The server checks the
versions and engine inactivity again before sending an install request. Review
`inventoryChanges`: Marketplace dependencies can change alongside the approved
root add-on. This is not permission for a bulk update or a new root add-on.

### 3. Verify Fresh Loaded State

Replace the placeholder with the approved candidate version, or the installed
version when you skipped an unnecessary update:

```text
Through the same native mcp-zap-server connection, use only the read-only add-on
update-status tool to verify pscanrules against expected version <exact version>
and passive rule 20020. Show fresh installed/local versions, rule enabled state,
verification status, quarantine state and passiveExecutionState. Do not install,
retry, change settings, clear recovery state, restart, crawl or scan. Report
unknown or restart-required results as such, and distinguish scanner configuration
from actual passive execution or a target being safe.
```

The expected call is `zap_addon_update_status`. Require the expected version
loaded, rule `20020` enabled, `verification.status: configured` and
`verification.quarantined: false`. `passiveExecutionState` remains `unknown`;
this workflow verifies configuration, not passive execution or exploitability.
Follow [Restart Requirements and Recovery](#restart-requirements-and-recovery)
for an unknown, quarantined or restart-required outcome. Do not automatically
retry installation. A target check is a separate authorized workflow.

### Illustrative Conversation

These values are examples, not a transcript or promised result. The LLM must
use the versions and states returned by your running engine.

| Turn | Expected MCP activity and response |
| --- | --- |
| You send the inspect-only prompt | The LLM discovers the tools and calls `zap_addon_inspect`. In this example, installed/local are `76.0.0`, candidate is `77.0.0`, and rule `20020` is absent, so readiness is `unavailable`. It reports the observations and waits. |
| You explicitly approve `pscanrules` from `76.0.0` to `77.0.0`, including dependency changes | The LLM sends one `zap_addon_update` request using those exact versions and dependency acknowledgment. In a successful example, the response verifies loaded `77.0.0` and reports inventory changes. |
| You send the verify-only prompt for `77.0.0` | The LLM calls `zap_addon_update_status` and reports fresh installed/local `77.0.0`, rule `20020` enabled, `configured`, no quarantine and global passive execution `unknown`. |

If your actual result differs, follow
[When the Workflow Refuses to Proceed](#when-the-workflow-refuses-to-proceed).
An LLM saying “done” without a completed tool result is not verification.

## Tool Argument Reference

The LLM supplies these arguments to the discovered tools. They are provided as
a reference for checking its calls; the prompts above do not require you to
submit JSON manually.

### Inspect Before Scanning

For example, the PHP2Shell version-detection rule requires Passive Scan Rules
(`pscanrules`) version 77 or later and passive rule `20020` enabled:

```json
{
  "addonId": "pscanrules",
  "passiveRuleIds": ["20020"]
}
```

Pass these arguments to `zap_addon_inspect`. Use the installed and candidate
version strings returned by the engine verbatim in a subsequent update request.
A version such as `77.0.0` is not the ZAP core version.

The Marketplace candidate is the version reported by ZAP's current Marketplace
metadata. It is not a promise that every mirror or cached image already contains
that version. Missing or malformed engine information must be treated as
unknown, not as a clean security result.

`configured` means the add-on is loaded and any requested passive rules are
present and enabled. `passiveExecutionState` remains `unknown`: ZAP does not
provide a view for its global passive-scanning switch. Inspection never changes
that switch. Use observed fixture traffic to verify actual passive execution.

## Enable Updates for a Dedicated Local Engine

The update workflow supports one MCP controller with exclusive control of a
dedicated ZAP engine. Keep other MCP controllers, direct API clients and browser
proxy users away from that engine during maintenance. Exclusivity is an operator
deployment requirement; setting a flag cannot discover or exclude other clients.

Before first enabling maintenance, establish that there is no previously
unmanaged browser or Automation Framework work still running. If that cannot be
established, an operator must stop that work or restart the engine manually.
Subsequent traffic-producing work must go through the same controller so its
activity tracking remains authoritative.

Updates require all of the following:

- API-key or JWT authentication and enforced tool authorization;
- an explicitly configured maintenance client with the exact
  `zap:engine:addons:update` scope; a wildcard alone is insufficient;
- the requested add-on already installed and on the operator's allowlist;
- a declared single controller, a `single-node` coordinator, and an `in-memory`
  scan-job store;
- a private, durable maintenance state directory that survives MCP restarts;
- an idle engine, including zero passive records and zero active passive tasks.

Keep the state directory dedicated to this engine. Do not delete it to bypass a
maintenance refusal. It preserves unresolved updates and tracked asynchronous
engine work across MCP process restarts.

For a single-client local installation, an additional private Spring
configuration file can contain:

```yaml
zap:
  addons:
    updates-enabled: true
    exclusive-engine: true
    controller-replicas: 1
    allowed-addon-ids: [pscanrules]
    operator-client-ids: [addon-operator]
    state-directory: /var/lib/mcp-zap/addon-maintenance
mcp:
  server:
    security:
      enabled: true
      mode: api-key
      authorization:
        mode: enforce
    auth:
      apiKeys:
        - clientId: addon-operator
          key: ${MCP_API_KEY}
          scopes: ["*", "zap:engine:addons:update"]
```

`addon-operator` is an example server-authenticated client identity, not the MCP
connection alias, chat name or client's `clientInfo` value. Prefer the existing
operator registration and key: replace `addon-operator` consistently in both
`operator-client-ids` and `apiKeys.clientId`, preserving its workspace identity.
Keep the existing secret in the process environment. For multiple registered
clients, preserve their entries and add the explicit update scope only to the
intended operator. The wildcard in this example retains ordinary tool access;
it does not grant maintenance authority by itself.
When wildcard acceptance is disabled, grant `mcp:tools:list`,
`zap:engine:addons:read` and the intended scan/report scopes explicitly as well.
In JWT mode, use the allowlisted authenticated identity and a token that carries
the exact update scope.

### Docker Compose

Keep private configuration outside the checkout, for example at
`$HOME/.config/mcp-zap/addons.yaml`. Mount a writable state directory owned by the
MCP container's user. The repository's Docker image runs as UID/GID `1000:1000`;
grant that identity the necessary access rather than making private files or
state directories world-writable. An additional Compose file can configure the
MCP service:

```yaml
services:
  mcp-server:
    environment:
      SPRING_CONFIG_ADDITIONAL_LOCATION: file:/etc/mcp-zap/addons.yaml
    volumes:
      - ${HOME}/.config/mcp-zap/addons.yaml:/etc/mcp-zap/addons.yaml:ro
      - ${HOME}/.local/share/mcp-zap/addon-maintenance:/var/lib/mcp-zap/addon-maintenance
```

Load this file alongside the base Compose file using `-f`. If an additional
Spring configuration location is already configured, preserve it and append
this file. Preserve existing client settings, service `env_file` entries and
volume mounts. Render `docker compose config` locally before operating the
stack; the rendered model can contain secrets, so do not post it publicly.

For a source checkout with an existing, running ZAP engine, save the override
above as `$HOME/.config/mcp-zap/addons.compose.yaml`. From the installation's
checkout, validate and apply it with the development build:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml \
  -f "$HOME/.config/mcp-zap/addons.compose.yaml" config --quiet
docker compose -f docker-compose.yml -f docker-compose.dev.yml \
  -f "$HOME/.config/mcp-zap/addons.compose.yaml" \
  up -d --build --no-deps mcp-server
```

Retain your installation's existing project name, environment-file options,
profiles and other overrides in both commands. This operator deployment action
can replace the MCP container; `--no-deps` leaves ZAP and other services alone.
Do not use this as an automatic recovery action or apply it while engine work
is still active. Reconnect the selected MCP client and repeat tool discovery
and read-only inspection afterward.

The equivalent environment settings are `ZAP_ADDON_UPDATES_ENABLED`,
`ZAP_ADDON_EXCLUSIVE_ENGINE`, `ZAP_ADDON_CONTROLLER_REPLICAS`,
`ZAP_ADDON_ALLOWED_IDS`, `ZAP_ADDON_OPERATOR_CLIENT_IDS` and
`ZAP_ADDON_STATE_DIRECTORY`. Pass them to the MCP process through a service
`env_file` or an explicit environment override. The project `.env` is used for
Compose interpolation and does not automatically forward these settings.

<a id="request-and-verify-an-update"></a>

## Update and Verification Argument Reference

After inspecting the add-on, call `zap_addon_update` with the observed versions:

```json
{
  "addonId": "pscanrules",
  "expectedInstalledVersion": "76.0.0",
  "expectedCandidateVersion": "77.0.0",
  "acknowledgeDependencyChanges": true,
  "passiveRuleIds": ["20020"]
}
```

These version values are examples. The server checks the engine's installed and
candidate versions again before mutation and rejects drift. ZAP installs the
Marketplace candidate by add-on ID; this API cannot select an arbitrary older
version. Use a separate operator-managed installation procedure when an exact
historical archive must be pinned.

ZAP may also install or update dependencies. The allowlist restricts the requested
root add-on, not its entire dependency graph. Acknowledgment is required, and the
result reports changes across the measured before/after inventory. This workflow
does not provide a dependency dry run or arbitrary URL, file, shell, uninstall,
bulk-update or ZAP-core-update operations.

Call `zap_addon_update_status` to recheck loaded readiness:

```json
{
  "addonId": "pscanrules",
  "expectedVersion": "77.0.0",
  "passiveRuleIds": ["20020"]
}
```

The expected result is the requested version loaded and the requested passive
rules enabled, reported as `configured`. Global passive execution remains
unverified until observed traffic demonstrates it. Check the fresh `verification`
field rather than relying only on `lastUpdate`, which can be absent after an MCP
process restart. An installation response or a downloaded archive alone is not
proof of readiness. A timeout or disconnected update can leave ZAP working;
ambiguous completion blocks new engine work and must not be retried
automatically. Read-only verification does not clear an unresolved update merely
because a version appears in inventory.

## When the Workflow Refuses to Proceed

| Observation | Next action |
| --- | --- |
| A tool is missing from discovery | Check the running MCP build and selected endpoint, then refresh the client's tool discovery. A source push does not update the runtime. Do not widen permissions to make a tool appear. |
| Authentication fails or the client declines a tool call | Check the selected connection's credential or client approval. Preserve unrelated client settings; do not put credentials in the prompt. |
| `updatesEnabled: false`, or update reports disabled by the operator | Have the operator apply the maintenance configuration. Inspection alone cannot enable it. |
| Update authority or deployment topology is rejected | Check enforced API-key/JWT authorization, the exact update scope, matching allowlisted client identity, exclusivity, one controller, `single-node` coordination and an `in-memory` job store. A wildcard alone is insufficient. |
| Installed add-on is not approved, or a dependency acknowledgment is missing | Review the root add-on allowlist and installed inventory, or explicitly approve possible dependency changes. These tools do not install a new root add-on. |
| Engine is busy or inactivity cannot be established | Let existing work complete and verify passive records and active tasks are zero. Resolve unknown activity with the operator; do not force an update. |
| Candidate is equal/older, missing, or inspected versions have changed | Skip an unnecessary install, or inspect again and review the fresh candidate before approving a new request. This workflow cannot refresh or pin arbitrary Marketplace versions. |
| Verification reports `disabled` or `unavailable` for requested rules | Review the rule/add-on configuration. `disabled` here describes the passive rule, while `updatesEnabled` describes maintenance policy. A loaded version alone does not establish rule readiness. |
| `unknown`, `pending_restart` or `quarantined: true` | Stop engine mutations and follow the operator recovery procedure below. Read-only status is available; it does not clear quarantine. |

## Restart Requirements and Recovery

The tools never shut down or restart ZAP, MCP, Docker or another service. If an
add-on requires a restart, the result reports that requirement for an operator
to handle. A failed or unknown update is not reported as successful.

If an update is unresolved, first determine whether ZAP is still updating and
resolve the engine state outside this workflow. An operator may need to stop
the engine, inspect its installed add-ons, and restart it manually. Clear a
maintenance recovery marker only after establishing that no update or tracked
engine operation remains in flight. Keep the previous inventory available for
comparison. No automatic retry, restart or forced unlock is provided.

With maintenance enabled, an ambiguous ordinary engine action can also trigger
quarantine: an import, authentication attempt, scan start or configuration
request may still be running after its HTTP connection fails. Some definite
upstream errors are handled conservatively the same way. Read-only status
remains available, but further engine actions wait for operator reconciliation.

## Continue with the Target Check

Once the loaded configuration is verified, use the normal guided sequence:

1. `zap_crawl_start` with the authorized target and `strategy: http`.
2. Poll `zap_crawl_status` using its returned operation ID.
3. Run `zap_passive_scan_wait` and require zero remaining records and active
   tasks before accepting a settled result.
4. Read `zap_findings_details` with the target's `baseUrl`, `pluginId: "20020"`
   and `includeInstances: true` to inspect version evidence and CVE tags.
5. Generate and read back a target-scoped report when an artifact is needed.

PHP2Shell detection here is based on disclosed PHP versions, not execution of
an exploit. A missing PHP version header limits coverage, while backported
security fixes can cause false positives. Use affected and patched header
fixtures on different origins when checking detection behavior. Readiness
verification proves the scanner configuration; actual detection requires
observed target traffic and findings.
