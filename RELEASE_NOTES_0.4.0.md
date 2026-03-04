# Release Notes - Version 0.4.0

**Release Date:** February 26, 2026

## Highlights

- Added `zap_get_findings_summary` for token-optimized, LLM-friendly markdown scan summaries.
- Added `ZapHealthIndicator` for ZAP API connectivity health checks.
- Added `GlobalExceptionHandler` for consistent API error responses.
- Added CycloneDX SBOM generation/upload in CI and release workflows.

## Platform & Dependency Updates

- Spring Boot plugin: `4.0.3`
- Spring AI BOM: `1.1.2`
- Gradle wrapper: `9.3.1`
- ZAP Client API: `1.17.0`
- Docker build image: `gradle:9.3.1-jdk25`

## CI/CD and Release Workflow Improvements

- Added manual `workflow_dispatch` trigger for CI and release workflows.
- Added release input version handling for manual builds.
- Added SBOM artifact upload to CI and release workflows.
- Updated Docker image publishing to include versioned tags plus `latest` and `dev` tags where relevant.

## Security and Reliability

- Clarified and standardized API error responses.
- Added runtime health signal for external ZAP availability.
- Hardened health endpoint details exposure (`management.endpoint.health.show-details: never`).
- Resolved `jdom` transitive dependency conflict by explicitly using `jdom2`.

## Container Stack Updates

- MCP server image reference aligned to `dtkmn/mcp-zap-server`.
- Updated supporting service tags in Compose:
  - `bkimminich/juice-shop:v19.1.1`
  - `swaggerapi/petstore3:1.0.27`

## Documentation Updates

- Updated changelog for `0.4.0`.
- Updated README and docs pages for current host port mapping (`7458 -> 7456`) in Docker Compose.
- Updated security docs to reflect CSRF-disabled-by-design behavior for this API-only server.

## Full Changelog

- [`CHANGELOG.md`](./CHANGELOG.md)
