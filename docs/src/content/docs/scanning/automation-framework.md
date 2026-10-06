---
title: "Automation Framework"
editUrl: false
description: "Run ZAP Automation Framework plans through the expert MCP surface."
---
This page requires the `expert` surface. Set `MCP_SERVER_TOOLS_SURFACE=expert` if you want these tools exposed.

MCP ZAP Server exposes a supported subset of ZAP's native Automation Framework through MCP.

The wrapper lets agents run repeatable ZAP plans, poll progress, and inspect generated artifacts. ZAP executes the jobs; the MCP server validates plan input and normalizes report directories.

## Tools

- `zap_automation_plan_run`
- `zap_automation_plan_status`
- `zap_automation_plan_artifacts`

## Requirements

The automation tools require:

- the ZAP `automation` add-on
- a filesystem workspace that both the MCP runtime and ZAP can read and write

Relevant settings:

- `ZAP_AUTOMATION_LOCAL_DIRECTORY`
- `ZAP_AUTOMATION_ZAP_DIRECTORY`

### Align automation and report workspaces

The two automation settings identify the same shared directory from different
containers: `LOCAL_DIRECTORY` is the path visible to MCP, and `ZAP_DIRECTORY` is
the path visible to ZAP. Both containers need read/write access to that storage.

`zap_report_read` only reads inside the authenticated caller's report workspace.
For the default single API-key client, that workspace is
`/zap/wrk/workspaces/default-client`, under `ZAP_REPORT_DIRECTORY=/zap/wrk`.
Place the automation directory inside it so a generated report can be read
through MCP. The report access restriction remains in place.

The updated `docker-compose.yml` in this source checkout defaults both automation
directories to
`${ZAP_REPORT_DIRECTORY}/workspaces/${MCP_CLIENT_ID}/automation`, using `/zap/wrk`
and `default-client` when those settings are absent. It also forwards
`MCP_SERVER_TOOLS_SURFACE`, so `.env` can select the expert tools:

```dotenv
MCP_SERVER_TOOLS_SURFACE=expert
MCP_CLIENT_ID=default-client
ZAP_REPORT_DIRECTORY=/zap/wrk
ZAP_AUTOMATION_LOCAL_DIRECTORY=/zap/wrk/workspaces/default-client/automation
ZAP_AUTOMATION_ZAP_DIRECTORY=/zap/wrk/workspaces/default-client/automation
```

These Compose changes are not in the `v0.14.0` tagged Compose file. The
`v0.14.0` server image already supports the explicit settings above; use a Compose
file that forwards them to the container.

The explicit paths above also work in a custom Compose file. A standalone
application retains the application defaults
`/zap/wrk/automation` unless overridden; that directory is outside the default
caller's report workspace. Configure the two paths explicitly there if you need
`zap_report_read` access to automation reports. If the containers use different
mount paths, each setting must name its own view of the same host directory.

The Helm chart's shared-workspace configuration derives its automation directory
as `<shared-mount>/automation` and rejects environment overrides of those paths.
It currently has no setting for a workspace-aligned automation directory, so
adding the two variables to `mcp.env` does not resolve report readback there.
The Compose configuration change does not change that chart behavior.

The Compose-derived path assumes one API-key client whose ID starts with a
letter or digit, uses only letters, digits, dots, underscores and hyphens, and
has at most 81 characters. With a custom workspace identity, an extension report
boundary, or another client ID format, set both automation paths explicitly to
match the actual report workspace. Other identity formats are hashed into report
directory names; their raw client IDs are not report paths.

Automation directories are process-wide settings; they do not switch with the
caller. A shared directory does not establish isolation between multiple clients
or workspaces. Use a separate MCP/ZAP deployment for independent clients in this
single-client configuration. Keep plans and reports on writable persistent
storage, and recreate the MCP container after changing its environment.

## How Plan Input Works

`zap_automation_plan_run` accepts exactly one of:

- `planPath` for an existing plan file inside the configured automation workspace
- `planYaml` for inline YAML content

In both cases, the server writes a normalized per-run copy of the plan before calling ZAP.

That gives you:

- a stable run-specific plan file path
- a stable run-specific artifacts directory
- bounded report output handling for MCP clients

The returned `Plan File` path is the one to reuse with `zap_automation_plan_artifacts`.

### YAML Limits

The limits below are introduced in `v0.14.0` and are not included in `v0.13.0`.
Check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for availability.

Inline plans and workspace plan files must fit within 1 MiB of UTF-8 input and contain a single YAML document with a mapping at the root. Plans must define at least one `env.contexts` entry. The normalized per-run copy must satisfy the same limits, so an expanded plan that exceeds them is rejected before the plan is written or ZAP is called.

Inline plans also have to fit inside the MCP HTTP request body, which defaults
to **256 KiB** (`MCP_REQUEST_MAX_BODY_BYTES=262144`). The JSON envelope and escaped
YAML count toward that limit. The 1 MiB plan limit does not increase the HTTP
limit; use a workspace plan file for plans that exceed the configured envelope.

The server checks the composed YAML graph before constructing or normalizing the plan. The limits are:

- 10,000 expanded node occurrences, including mapping keys and values
- expanded depth of 50, including paths through aliases
- 1,048,576 expanded scalar characters, including mapping keys and repeated scalar aliases
- 50 aliases to collections and parser nesting depth of 50

Bounded acyclic aliases, merge mappings, and ordinary typed scalars are supported. Cyclic aliases and collection-valued mapping keys are rejected. Mapping keys must be scalars; their parsed scalar values are converted to strings during normalization. YAML object tags that construct arbitrary Java classes are unsupported.

These limits also apply when `zap_automation_plan_artifacts` reads a plan to find its declared reports. Invalid or oversized plan input is rejected before ZAP starts the plan.

### Destination Policy

This contract is introduced in `v0.14.0` and is not included in `v0.13.0`.

Both inline YAML and workspace files use the same validation before the normalized plan is written or ZAP is called. A file's presence in the workspace does not grant it an exemption.

Every context must have a unique literal name and a nonempty `urls` list. The server applies the ordinary scan destination policy to each context URL, each requestor request URL, explicit spider/active-scan URLs, form/JSON login URLs, and authentication verification polling URLs. Destinations must be full literal HTTP(S) URLs without user info, fragments, or surrounding whitespace.

The same operator settings apply:

- `ZAP_URL_WHITELIST` and `ZAP_URL_BLACKLIST`
- `ZAP_ALLOW_LOCALHOST` and `ZAP_ALLOW_PRIVATE_NETWORKS`
- `ZAP_URL_VALIDATION_ENABLED`

Use the documented field spellings. Malformed mappings/lists, legacy singular context `url`, and capitalized parameter aliases are rejected. Optional job context selectors must refer to a context declared in the plan. Every job is checked, including disabled jobs.

Supported job types are:

| Job | Destination handling |
| --- | --- |
| `requestor` | Every entry in `requests` requires a validated literal `url`. |
| `spider`, `activeScan` | Context URLs and an optional `parameters.url` are validated. Omit `parameters.context` to use the first declared context. |
| `passiveScan-config`, `passiveScan-wait` | Configure or wait for passive scanning. |
| `activeScan-config`, `activeScan-policy` | Configure active scanning or its policy. |
| `report`, `exitStatus`, `delay` | Produce reports or control plan completion. Existing report-directory normalization remains in effect. |

Context authentication supports `manual`, `http`, `form`, and `json`; session management supports `cookie` and `http`. Form/JSON authentication requires `parameters.loginRequestUrl`; an optional `loginPageUrl` and any `verification.pollUrl` also pass destination validation.

The MCP runner rejects:

- `${...}` substitutions in destinations, context names, and context selectors, because ZAP resolves them in its own environment
- arbitrary `includePaths`, which can expand context scope beyond declared URLs
- `env.proxy` and `env.configs`, which can change engine networking or configuration
- script, browser, client, and autodetect authentication or script session management
- all other job types, including imports (OpenAPI, GraphQL, SOAP, HAR/sequence), scripts, replacer rules, add-on installation, and browser crawlers

Variables remain available for credentials and request bodies. Disabling URL validation deliberately disables the configured network filtering; it does not enable unsupported jobs or destination substitutions.

For API definitions, use the dedicated [API schema import tools](../api-schema-imports/) before a supported scan plan. Plans needing the full native job set must be run directly by an operator through ZAP, outside this MCP runner.

This is validation of declared destinations, not an engine network sandbox. Crawling, redirects, imported sites-tree entries, add-ons, and DNS changes can introduce runtime destinations. MCP and ZAP may also resolve names differently. Keep ZAP isolated, use trusted engine configuration, and enforce ZAP-side egress restrictions for the permitted targets. See the [Helm network policy guidance](https://github.com/dtkmn/mcp-zap-server/tree/main/helm/mcp-zap-server#network-policy) and [ZAP's environment reference](https://www.zaproxy.org/docs/desktop/addons/automation-framework/environment/).

## Example Inline Plan

```json
{
  "tool": "zap_automation_plan_run",
  "arguments": {
    "planFileName": "nightly-plan.yaml",
    "planYaml": "env:\n  contexts:\n    - name: local-target\n      urls:\n        - http://example.com/\njobs:\n  - type: requestor\n    requests:\n      - url: http://example.com/\n        method: GET\n        responseCode: 200\n  - type: passiveScan-wait\n    parameters:\n      maxDuration: 2\n  - type: report\n    parameters:\n      template: traditional-json-plus\n      reportFile: nightly-report\n      reportTitle: Nightly Automation Report\n      displayReport: false\n    sites:\n      - example.com"
  }
}
```

## Status And Artifacts

Use the response from `zap_automation_plan_run` like this:

1. capture `Plan ID`
2. poll `zap_automation_plan_status`
3. capture `Plan File`
4. call `zap_automation_plan_artifacts` with that `Plan File`

If you need a deeper read of a generated report file, use `zap_report_read` with
the returned absolute file path. The file must be inside the caller's report
workspace as described above. A successful plan outside that workspace can still
produce artifacts, but does not grant permission to read them through this tool.

The preview is limited to 200,000 characters. On a server exposing the unreleased
`zap_report_read_chunk`, retrieve complete files in pages using `nextOffset` and
`expectedSha256`; see [Findings and Reports](../findings-and-reports/#preview-or-complete-retrieval).
That tool is not included in `v0.14.0`.

Native Automation Framework report jobs are generated by ZAP directly. Their
templates can include session-wide insights and diagnostics even when alert
sites are filtered. The scoped-report sanitization in the MCP report-generation
tools does not apply to these jobs. Review their artifacts before sharing them,
or use a separate ZAP session for workloads that require isolated reports.

## Relationship To Queue Mode

Automation Framework plans and queue-managed scans solve different problems.

Use queue mode when you need:

- durable `ScanJob` state
- retries and dead-letter handling
- HA-safe worker claims
- centralized job lifecycle operations

Use Automation Framework plans when you need:

- a repeatable ZAP-native multi-step workflow
- one plan file that captures supported requestor, spider, passive-wait, report, and scan-policy jobs
- lightweight MCP control over an existing Automation Framework plan
