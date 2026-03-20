# Release Notes - Version 0.6.0

**Release Date:** March 20, 2026

## Highlights

- Added a new default guided MCP tool surface with a separate expert surface for raw ZAP workflows.
- Added tool-scope authorization, abuse protection, structured request correlation, audit events, and Prometheus-ready observability.
- Expanded the scan and reporting surface with passive scan tools, findings drilldown, findings snapshots and diffs, API schema imports, Automation Framework plan execution, and active-scan policy controls.
- Evolved HA queue behavior from leader-centric dispatch assumptions to claim-based worker ownership and recovery.
- Expanded Helm support, shared-state migrations, and public documentation for production and HA deployments.

## Upgrade Notes

Read this section before you upgrade and then act like it was optional.

### Guided vs Expert tool surface

The server now supports:

- `guided` tool surface, which is the default
- `expert` tool surface, which exposes the broader raw ZAP control surface

If your clients depend on direct scan tools, queue administration, context/user management, raw findings tools, or automation-plan tools, set:

```bash
MCP_SERVER_TOOLS_SURFACE=expert
```

If you do nothing, the runtime defaults to the smaller guided surface.

### Queue execution model

Multi-replica queue behavior now centers on durable worker claims and lease recovery rather than a simple “only one leader dispatches” mental model.

That means:

- queued work can be claimed by any healthy replica
- running jobs renew claim leases
- another replica can recover polling when a worker disappears
- coordinator leadership remains useful for identity, maintenance coordination, and observability, but it is no longer the whole queue story

### Manual MCP testing

If you test `/mcp` with raw HTTP, you must follow the streamable MCP session flow:

1. send `initialize`
2. capture `Mcp-Session-Id`
3. send that session id on later requests

A bare `curl http://localhost:7456/mcp` is not a valid protocol check.

## Tool Surface and Scanning Expansion

### Guided security workflows

- Added `GuidedSecurityToolsService` and `GuidedScanWorkflowService`.
- Added guided tools for target import, crawl start/status/stop, attack start/status/stop, findings summary/details, and guided report generation.
- Added `GuidedExecutionModeResolver` so the guided workflow can prefer direct or queue-backed execution based on deployment topology.

### Expert surface expansion

- Added expert tool groups for inventory, direct scan execution, queue lifecycle management, API imports, findings/reporting, authenticated scanning setup, and Automation Framework plans.
- Added `ToolSurfaceProperties` to control the exposed MCP surface.

### New scanning and findings capabilities

- Added passive scan status and wait tools.
- Added findings detail, bounded alert instance views, normalized findings snapshots, and findings diff support.
- Added API schema imports for OpenAPI, GraphQL, and SOAP/WSDL.
- Added Automation Framework plan execution, status, and artifact inspection.
- Added active-scan policy discovery and bounded policy-rule mutation tools.
- Added queued AJAX Spider support and expanded queue lifecycle coverage.

## Security, Authorization, and Protection

- Added per-tool scope authorization with configurable `off`, `warn`, and `enforce` modes.
- Added startup validation to fail fast when public MCP tools are exposed without scope mappings.
- Added request-throttling, workspace quotas, and overload shedding for MCP traffic.
- Added optional `workspaceId` support for API-key clients to improve multi-client isolation.
- Expanded JWT revocation support and shared-state options.

## Observability and Diagnostics

- Added structured request correlation with `X-Correlation-Id` and legacy `X-Request-Id` upgrade behavior.
- Added bounded correlation IDs to error responses and request-completion logs.
- Added centralized observability services for:
  - HTTP request timing
  - authentication and authorization events
  - tool execution timing
  - audit event emission
  - protection rejections
  - queue and claim metrics
- Added Actuator audit stream integration and structured logging support for operator troubleshooting.

## Queue, Storage, and Shared State

- Added and expanded Flyway migrations for:
  - baseline shared Postgres state
  - durable scan jobs
  - queue idempotency
  - queue claim ownership and recovery metadata
- Expanded `ScanJob`, `ScanJobStore`, `InMemoryScanJobStore`, and `PostgresScanJobStore` for idempotent admission, claim handling, recovery, and richer queue state.
- Expanded `ScanJobQueueService` for claim-based worker ownership, dead-letter replay, queued AJAX Spider support, and richer queue status visibility.
- Expanded schema readiness checks for the additional shared-state columns required by the new queue model.

## Deployment, Helm, and Platform

- Expanded Helm support with HA-oriented values, AWS and GCP value presets, migration job templates, network policies, and service account support.
- Added local and private values presets plus shared database migration assets under the Helm chart.
- Expanded `application.yml` configuration surface for authorization, protection, observability, queue coordination, and tool surfaces.
- Added AOP, AspectJ, Prometheus registry, and Testcontainers dependencies needed by the expanded runtime and test suite.

## Documentation and Developer Experience

- Added public documentation for:
  - tool surfaces
  - tool-scope authorization
  - abuse protection
  - observability
  - structured logging
  - scan execution modes
  - passive scan handling
  - API schema imports
  - scan policy controls
  - findings and reports
  - Automation Framework support
- Updated the README and root quick-start guidance to match the current runtime, auth flow, and queue behavior.
- Preserved legacy `.html` routes in the docs site for older links and bookmarks.

## Testing and Validation Coverage

- Added architecture tests for service and tool-surface boundaries.
- Added unit and Docker/Testcontainers coverage for:
  - active-scan policy tools
  - API schema imports
  - Automation Framework plans
  - direct scan services
  - findings and reports
  - passive scan behavior
  - Postgres-backed queue recovery
  - tool-surface registration

## Recommended Release Validation

- `./gradlew test`
- `cd docs && npm run check`
- `cd docs && npm run build`

## Full Changelog

- [`CHANGELOG.md`](./CHANGELOG.md)
