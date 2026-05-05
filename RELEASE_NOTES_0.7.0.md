# Release Notes - Version 0.7.0

**Release Date:** May 5, 2026

## Highlights

- Added Policy Bundle v1 dry-run and runtime enforcement support, including the new `zap_policy_dry_run` tool, deterministic policy preview contracts, and configurable `MCP_POLICY_MODE` rollout controls.
- Added guided authenticated-session bootstrap tools for form login, bearer token, and API key flows through `zap_auth_session_prepare` and `zap_auth_session_validate`.
- Added a scan history ledger for release evidence and operations, with `zap_scan_history_list`, `zap_scan_history_get`, and `zap_scan_history_export`.
- Hardened MCP request handling, credential reference resolution, URL validation, Helm session affinity, Kubernetes egress boundaries, Docker bind defaults, and ZAP progress error handling.
- Refactored ZAP access behind explicit gateway capability interfaces, reducing direct service coupling to the ZAP client and adding boundary tests around the engine adapter layer.
- Added production runbooks, auth bootstrap runbooks, Grafana dashboards, Prometheus alerts, and expanded integration coverage for MCP authorization, protection, runtime policy, streamable HTTP, observability, and scan history.

## Upgrade Notes

Read this before upgrading. This release is not just a dependency bump.

### Release metadata

The release metadata is set to `0.7.0` in:

- `build.gradle`
- `src/main/resources/application.yml`
- `helm/mcp-zap-server/Chart.yaml`

### Auth bootstrap security

Guided auth bootstrap now fails closed by default:

- inline secrets remain disabled unless explicitly enabled for local-only workflows
- `env:` and `file:` credential references must be operator-allowlisted
- file references are checked with normalized path containment, including sibling-prefix and symlink escape protection
- form-login bootstrap validates same-origin target/login URLs, encodes ZAP auth config values, restricts unsafe field names, and quotes context regex literals

Operators using guided auth must configure allowed credential references deliberately, for example:

```bash
MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES=env:SCAN_PASSWORD,file:/var/run/secrets/mcp-zap/*
```

### MCP request body limit

MCP request bodies are now capped early by `McpRequestBodyLimitWebFilter`.
The default limit is:

```bash
MCP_REQUEST_MAX_BODY_BYTES=262144
```

Large policy bundles, prompts, or custom tool payloads may need an explicit limit increase. Do not raise this casually in internet-facing deployments.

### Helm HA and network policy behavior

The Helm chart now defaults MCP replicas to `1` because streamable MCP sessions are in-memory per replica.

If you set `mcp.replicaCount > 1` or enable autoscaling above one replica, configure a supported sticky-session provider:

- `service-client-ip`
- `ingress-nginx`
- `aws-nlb`

Provider values are intentionally strict and case-sensitive. A mixed-case value should fail rendering instead of silently disabling affinity.

The chart also adds egress boundaries for ZAP and MCP pods. If your deployment needs Postgres, JWKS, proxy, telemetry, or other outbound services, configure explicit extra egress rules.

### Scan history storage

The scan history ledger defaults to the scan-job backend and supports both in-memory and Postgres storage.

For durable release evidence, use Postgres and run Flyway migrations. This release adds:

- `V5__create_scan_history_entries.sql`
- `ZAP_SCAN_HISTORY_BACKEND`
- `ZAP_SCAN_HISTORY_RETENTION_DAYS`
- `ZAP_SCAN_HISTORY_MAX_LIST_ENTRIES`
- `ZAP_SCAN_HISTORY_MAX_EXPORT_ENTRIES`
- `ZAP_SCAN_HISTORY_POSTGRES_*`

Flyway migration resources now live under `classpath:db/migration`. If you override Flyway locations, update that override.

## Added

### Policy bundles

- Added `PolicyDryRunService`, `PolicyBundlePreviewer`, `ToolExecutionPolicyService`, and policy enforcement hook contracts.
- Added `BasicPolicyBundleToolExecutionPolicyHook` for shared-core runtime enforcement.
- Added `zap_policy_dry_run` for previewing allow/deny outcomes against a tool, target, and evaluation time.
- Added example policy bundles for guided CI guardrails and expert read-only triage.
- Added deterministic policy dry-run contract fixtures and integration tests.

### Guided auth bootstrap

- Added `GuidedAuthSessionService` and guided MCP tools for preparing and validating authenticated ZAP sessions.
- Added form-login, bearer-token, and API-key bootstrap providers.
- Added prepared session registry support for guided auth flows.
- Added credential reference resolution with inline-secret controls and operator-managed allowlists.
- Added auth bootstrap failure runbook coverage.

### Scan history ledger

- Added in-memory and Postgres-backed scan history stores.
- Added ledger entries for queued scan jobs, direct scan starts, and generated report artifacts.
- Added scan history list, get, and export MCP tools.
- Added a dedicated scan history operations doc and Postgres migration.

### Gateway boundary layer

- Added `EngineAdapter` and capability-specific ZAP gateway interfaces for context, scan execution, AJAX spider, API imports, automation, findings, inventory, passive scan, reports, and runtime access.
- Added gateway record types for targets, findings, scan runs, and artifacts.
- Moved service logic away from direct ZAP API coupling toward the new gateway access model.

### Observability and operations

- Added Prometheus alert rules and a Grafana dashboard.
- Added production simulation and auth bootstrap runbooks.
- Added structured logback configuration and expanded audit details for policy, scan, report, and request flows.

## Changed

- Removed the vendored `io.modelcontextprotocol.server.transport.WebFluxStreamableServerTransportProvider` shim and expanded streamable HTTP protocol regression coverage.
- Renamed observability metrics and configuration details toward MCP ZAP naming conventions.
- Improved retry behavior for transient target access failures and stabilized malformed ZAP progress error handling so raw upstream values are logged internally but not reflected to clients.
- Bound Docker Compose-published ports to loopback by default through `MCP_ZAP_BIND_ADDRESS`.
- Upgraded Gradle to `9.5.0`.
- Upgraded docs dependencies, including Astro and `@astrojs/compiler`, and refreshed the docs site theme from the old Mr. Robot styling to Terminal Ops.
- Updated README, security docs, Helm README, and production docs to match the new policy, auth bootstrap, network, and evidence-ledger behavior.

## Security

- Added early MCP request body limiting independent of authorization and abuse-protection settings.
- Hardened credential reference allowlisting for `env:` and `file:` references.
- Prevented user-selected credential references from freely resolving server-side secrets.
- Added same-origin validation and encoded ZAP form-auth configuration serialization.
- Escaped context include/logout regex literals generated during guided form auth.
- Added stricter username/password field validation before secret resolution.
- Added MCP and ZAP egress NetworkPolicy controls in Helm.
- Added strict Helm validation for multi-replica streamable MCP session affinity.
- Added per-tool authorization and runtime policy integration coverage for API key and JWT clients.
- Kept malformed upstream ZAP progress values out of client-visible exception messages.

## Fixed

- Fixed multi-replica Helm defaults that could break streamable MCP sessions without sticky affinity.
- Fixed advisory-only SSRF protection by adding chart-level egress boundaries.
- Fixed unbounded MCP body buffering by adding an early request-size boundary.
- Fixed credential allowlist wildcard sibling-prefix and symlink escape handling.
- Fixed mixed-case session affinity provider values silently passing validation but rendering no affinity.
- Fixed ZAP form-auth parameter injection risk by URL-encoding config values.
- Fixed context scope regex overmatching by quoting literal URL components.
- Fixed CodeQL findings around deprecated JSON field iteration and ignored access-boundary parameters.

## Documentation

- Added `docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md`.
- Added `docs/operator/runbooks/PRODUCTION_SIMULATION_RUNBOOK.md`.
- Added scan history ledger documentation.
- Updated production checklist, observability docs, JWT quick start, tool-scope authorization docs, security modes, and security policy reference.
- Added or refreshed contribution, issue, vulnerability, and repository hygiene docs through the post-`v0.6.1` work.

## Testing and Validation Coverage

- Added integration coverage for MCP request body limits, rate limiting, workspace quotas, backpressure, runtime policy dry-run/enforcement, streamable HTTP protocol behavior, API-key authorization, JWT authorization, and observability actuators.
- Added unit coverage for credential reference resolution, guided auth sessions, policy bundles, gateway records, ZAP engine boundaries, scan history ledger behavior, and protection services.
- Added architecture tests for gateway execution boundaries and edition boundaries.
- Added runbook validation tests for production simulation guidance.

## Recommended Release Validation

- `./gradlew clean build`
- `helm lint helm/mcp-zap-server`
- `cd docs && npm run check`
- `cd docs && npm run build`
- Render Helm with:
  - default values
  - `values-ha.yaml`
  - `mcp.replicaCount > 1` with each supported session affinity provider
  - MCP and ZAP NetworkPolicy enabled with expected extra egress rules
- Run a smoke test that covers:
  - MCP initialize and `tools/list`
  - guided auth session prepare and validate with an allowlisted credential reference
  - `zap_policy_dry_run`
  - one queued scan and one direct scan
  - report generation
  - scan history export

## Full Changelog

- https://github.com/dtkmn/mcp-zap-server/compare/v0.6.1...v0.7.0
