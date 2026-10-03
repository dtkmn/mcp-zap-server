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

If you need a deeper read of a generated report file, use `zap_report_read`.

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
