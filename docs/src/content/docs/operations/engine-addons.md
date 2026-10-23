---
title: "ZAP Add-on Inspection and Updates"
editUrl: false
description: "Inspect loaded ZAP add-ons and passive rules, authorize a Marketplace update, and verify readiness through MCP."
---

The add-on tools let an MCP client inspect the scanner before scanning a target,
update an approved installed add-on, and check what the engine actually loaded.
They are available on both the `guided` and `expert` surfaces. No prior target
scan is required for inspection.
Use MCP `tools/list` to confirm these tools are present in your server build
before following this guide.

| Tool | Purpose | Required scope |
| --- | --- | --- |
| `zap_addon_inspect` | Inspect an installed add-on, the engine's Marketplace candidate, and requested passive rules | `zap:engine:addons:read` |
| `zap_addon_update` | Update one approved installed add-on and verify the result | `zap:engine:addons:update` |
| `zap_addon_update_status` | Read loaded readiness and the recorded maintenance outcome | `zap:engine:addons:read` |

Updates are disabled by default. Inspection does not enable updates.

## Inspect Before Scanning

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
- a declared single controller, a single-node coordinator, and no shared
  PostgreSQL scan-job store;
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

Keep the existing secret in the process environment. For multiple registered
clients, preserve their entries and add the explicit update scope only to the
intended operator. The wildcard in this example retains ordinary tool access;
it does not grant maintenance authority by itself.
When wildcard acceptance is disabled, grant `zap:engine:addons:read` and the
intended scan/report scopes explicitly as well. In JWT mode, use a token that
carries the exact update scope.

### Docker Compose

Keep private configuration outside the checkout, for example at
`$HOME/.config/mcp-zap/addons.yaml`. Mount a writable state directory owned by the
MCP container's user. An additional Compose file can configure the MCP service:

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

The equivalent environment settings are `ZAP_ADDON_UPDATES_ENABLED`,
`ZAP_ADDON_EXCLUSIVE_ENGINE`, `ZAP_ADDON_CONTROLLER_REPLICAS`,
`ZAP_ADDON_ALLOWED_IDS`, `ZAP_ADDON_OPERATOR_CLIENT_IDS` and
`ZAP_ADDON_STATE_DIRECTORY`. Pass them to the MCP process through a service
`env_file` or an explicit environment override. The project `.env` is used for
Compose interpolation and does not automatically forward these settings.

## Request and Verify an Update

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
unverified until observed traffic demonstrates it. An installation response or a downloaded archive alone is not
proof of readiness. A timeout or disconnected update can leave ZAP working;
ambiguous completion blocks new engine work and must not be retried
automatically. Read-only verification does not clear an unresolved update merely
because a version appears in inventory.

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
