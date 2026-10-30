# Update a ZAP Add-on by Asking Your MCP Client

Use this prompt pack with an LLM in a client connected to MCP ZAP Server. You
write the requests; the LLM selects native MCP tools to inspect an installed
add-on, request one approved update and read fresh verification. No manual
JSON, HTTP requests or shell commands are needed for this workflow.

The [canonical guide](../../docs/src/content/docs/operations/engine-addons.md)
covers one-time operator configuration, client connection, permissions and
recovery. Those prerequisites must already be satisfied: the running build must
expose `zap_addon_inspect`, `zap_addon_update` and `zap_addon_update_status`, and
the operator must enable controlled updates for an allowlisted identity on an
idle, dedicated engine. Pushing source does not replace a running scanner.

Replace `mcp-zap-server` with your existing connection alias if needed. Keep
credentials in private client configuration, never in prompts. The LLM must
read real tool results and use the client's native MCP connection; it must not
invent results, self-enable maintenance, widen permissions or switch to HTTP,
scripts or shell commands when a call is unavailable or denied.

This example uses `pscanrules` and its PHP2Shell passive rule `20020`. For another
already installed, operator-approved add-on, replace the ID consistently and
request only passive rule IDs relevant to it; omit passive-rule verification
when it does not apply. Use freshly inspected versions for that add-on.

## 1. Inspect and Wait for Approval

```text
Using only the native mcp-zap-server connection, discover the add-on maintenance
tools and inspect pscanrules with passive rule 20020. Show actual installed/local
versions, the Marketplace candidate, rule state, updatesEnabled and quarantine
state. Explain whether the loaded configuration meets the PHP2Shell rule's
requirements. Do not update, change settings, crawl, scan or restart anything.
Stop if tools or connection are unavailable; do not use fallbacks. Wait for my
explicit approval before any update.
```

Review the actual `zap_addon_inspect` result. If the installed version already
meets your requirement, skip the update and use step 3 with that installed
version. Do not guess a missing candidate or approve example version numbers.

## 2. Approve One Specific Update

Replace both version placeholders with the strings just returned by inspection.
Send this only if you approve possible Marketplace dependency changes too.

```text
I approve updating only the already installed pscanrules add-on from
<observed installed version> to <observed candidate version>, including possible
Marketplace dependency additions or updates. Through the same native
mcp-zap-server connection, send at most one update request with those exact
versions, dependency acknowledgment enabled and passive rule 20020 included.
Do not enable maintenance, widen permissions, change other settings, restart,
crawl or scan. Stop on refusal or uncertain completion; do not retry or use
fallbacks. Show the returned outcome and inventory changes.
```

The expected tool is `zap_addon_update`. The server enforces authority, installed
root add-on approval, version guards and engine inactivity. Dependency changes
may extend beyond `pscanrules`; this prompt does not approve bulk updates or a
new root add-on.

## 3. Verify Without Changing Anything

Use the approved candidate as the exact version, or the installed version when
you skipped step 2.

```text
Through the same native mcp-zap-server connection, use only read-only add-on
update status to verify pscanrules against expected version <exact version>
and passive rule 20020. Report fresh installed/local versions, the rule enabled
state, verification status, quarantine and passiveExecutionState. Do not update,
retry, change settings, clear recovery state, restart, crawl or scan. Distinguish
configured scanner rules from observed passive execution or a safe target.
```

The expected tool is `zap_addon_update_status`. Success requires matching loaded
versions, the requested rule enabled, `verification.status: configured` and
`verification.quarantined: false`. Global `passiveExecutionState` remains
`unknown`. This checks scanner configuration; it does not prove a target is
unaffected or run a vulnerability scan.

## Illustrative LLM Conversation

This is a worked example, not an execution transcript. The versions and results
below apply only if the running engine actually returns them.

| You | Expected LLM/tool behavior |
| --- | --- |
| Send the inspect-only prompt. | The LLM calls `zap_addon_inspect` for `pscanrules` and `20020`. Example result: installed/local `76.0.0`, candidate `77.0.0`, rule absent and readiness `unavailable`. The LLM explains this and waits. |
| Approve only `pscanrules` from the observed `76.0.0` to `77.0.0`, including dependency changes. | The LLM makes one `zap_addon_update` call using those exact versions and explicit dependency acknowledgment. A successful example reports loaded `77.0.0` and the measured inventory changes. |
| Ask for fresh read-only verification of `77.0.0`. | The LLM calls `zap_addon_update_status` and reports installed/local `77.0.0`, rule `20020` enabled, `configured`, no quarantine and global passive execution `unknown`. No restart or scan occurs. |

## Stop Conditions

- **Tools missing or maintenance disabled:** stop and ask the operator to check
  the running build or configuration. A prompt cannot add tools or enable
  updates.
- **Authority denied:** report the refusal. Do not widen scopes, change client
  identity or try another transport to bypass it.
- **Already current or candidate equal/older:** skip installation and verify
  the loaded state. Missing inventory or candidate data must remain unknown.
- **Engine busy:** wait for the existing work to finish. Do not force an update
  or loop over update requests automatically.
- **Versions changed:** inspect again, show the new values and wait for fresh
  approval; do not silently substitute another candidate.
- **Timeout, unknown completion, quarantine or restart required:** stop
  mutations. Read-only status may help the operator reconcile the engine, but
  it does not clear recovery state. No automatic retry or restart is provided.
- **Requested rule disabled or missing:** report the configuration gap. A
  loaded add-on version alone is insufficient.

Follow the canonical guide's [recovery procedure](../../docs/src/content/docs/operations/engine-addons.md#restart-requirements-and-recovery)
for unresolved outcomes. Request target scanning separately with explicit target
scope after configuration is verified.
