# Release Notes - Version 0.9.0

**Release Date:** June 10, 2026

## Highlights

- Integrated `mcp-gateway-core` and the `mcp-gateway-spring-webflux` adapter module at `0.5.9` through dedicated audit, policy, protection, and Spring WebFlux boundaries.
- Replaced bespoke MCP authorization and abuse-protection filters with shared gateway-core enforcement paths.
- Added reusable GitHub Actions for ZAP security gates and webhook callbacks, plus GitLab CI examples and seeded API scan contracts.
- Added a Juice Shop CI gate pilot workflow, CI pack verification script, and image-reference validation for immutable release tags.
- Added self-serve bootstrap and doctor scripts with first-run documentation for local setup and MCP client validation.
- Improved MCP client timeout handling and report path mapping for CI/evidence workflows.
- Updated Spring AI, Netty, Astro, and related dependencies.

## Upgrade Notes

### Release metadata

The release metadata is set to `0.9.0` in:

- `build.gradle`
- `src/main/resources/application.yml`
- `.mcp/server.json`
- `helm/mcp-zap-server/Chart.yaml`

The Helm chart version is `0.9.0`; `appVersion` is `v0.9.0` so the default image tag matches GitHub release image tags.

### Gateway-core adapter module

The runtime now consumes shared gateway-core policy, audit, and protection contracts through the `mcp-gateway-spring-webflux` adapter module plus local bridge classes instead of maintaining separate MCP WebFlux filter implementations for those boundaries.

Operators should validate policy dry-run and enforcement behavior in their own bundles before rolling this release into production.

### CI security gate pack

The GitHub Actions and GitLab examples expect immutable MCP server image tags or digests. Mutable tags such as `latest`, `dev`, `main`, and `nightly` are rejected by the validation scripts.

## Added

- Added `zap-security-gate` and `zap-webhook-callback` reusable GitHub Actions.
- Added GitHub Actions and GitLab CI examples for seeded API security gate workflows.
- Added CI gate contract documentation, pilot install runbook, and Juice Shop validation workflow.
- Added `McpGatewayWebFluxAdapterConfiguration`, `GatewayCoreAuditAdapter`, and `GatewayCorePolicyAdapter` backed by the `mcp-gateway-spring-webflux` adapter module.
- Added self-serve local bootstrap and doctor scripts with matching docs.

## Changed

- Updated release metadata, registry metadata, install snippets, Helm chart metadata, and security policy support tables for `0.9.0`.
- Updated `gatewayCoreVersion` to `0.5.9` for both `io.github.dtkmn:mcp-gateway-core` and `io.github.dtkmn:mcp-gateway-spring-webflux`.
- Updated Spring AI to `2.0.0-RC1`, Netty to `4.2.15.Final`, and Astro to `6.4.5`.
- Reworked tool-scope authorization, policy dry-run, rate limiting, workspace resolution, and audit publishing around gateway-core adapters.

## Security

- Centralized MCP policy and abuse-protection enforcement through gateway-core adapter boundaries.
- Added Snyk workflow coverage for open-source and container security scanning.
- Added CI image reference validation to prevent mutable MCP server tags in security gate examples.

## Fixed

- Improved MCP client timeout behavior in CI gate orchestration.
- Improved report path mapping for generated evidence artifacts.
- Removed legacy URL-scope and policy decision classes after their responsibilities moved into gateway-core.

## Recommended Release Validation

- `./gradlew clean build`
- `./gradlew verifyExtensionApiPublication`
- `./gradlew -p examples/extensions/standalone-policy-metadata-extension clean build`
- `helm lint helm/mcp-zap-server`
- `npm --prefix docs run check`
- `npm --prefix docs run build`
- `python3 -m unittest discover -s tests/python -p 'test_*.py'`
- Build and inspect the release image label:

```bash
docker build -t ghcr.io/dtkmn/mcp-zap-server:v0.9.0 .
docker image inspect ghcr.io/dtkmn/mcp-zap-server:v0.9.0 \
  --format '{{ index .Config.Labels "io.modelcontextprotocol.server.name" }}'
```

Expected output:

```text
io.github.dtkmn/mcp-zap-server
```

## Full Changelog

- https://github.com/dtkmn/mcp-zap-server/compare/v0.8.0...v0.9.0
