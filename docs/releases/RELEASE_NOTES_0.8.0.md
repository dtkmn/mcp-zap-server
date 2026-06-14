# Release Notes - Version 0.8.0

**Release Date:** May 10, 2026

## Highlights

- Added a public-preview extension API publication path for `io.github.dtkmn:mcp-zap-extension-api`, with artifact-shape verification and standalone extension compatibility checks.
- Added standalone extension examples and documentation for the OSS extension model, compatibility boundaries, release policy, and external extension repository shape.
- Added release-evidence and customer-handoff scan-history tools for safer evidence packaging and downstream reporting.
- Refactored queue execution around claim fencing, dispatcher/result-applier/normalizer boundaries, and Postgres race coverage for safer multi-replica operation.
- Hardened Docker image packaging so the runtime image contains the executable application JAR, not extension API or sample-extension artifacts.
- Added MCP Registry OCI package metadata, agent install notes, and Docker image labels for registry/catalog discovery, with explicit external-ZAP requirements for standalone installs.
- Changed the project license for future releases to Apache License 2.0. Existing releases remain available under their original MIT license terms.

## Upgrade Notes

### Release metadata

The release metadata is set to `0.8.0` in:

- `build.gradle`
- `src/main/resources/application.yml`
- `.mcp/server.json`
- `helm/mcp-zap-server/Chart.yaml`

The Helm chart version is `0.8.0`; `appVersion` is `v0.8.0` so the default image tag matches GitHub release image tags.

### Extension API remains public-preview

The extension API artifact is publishable and validated, but it is still an experimental compatibility surface.

Do not treat the extension API as a long-term stable contract until the release policy says it has graduated from public preview.

### MCP Registry OCI package requires external ZAP

The repository now includes `.mcp/server.json` with OCI package metadata for the published `v0.8.0` images.

Docker Compose remains the easiest install path. Standalone OCI installs require a separately running OWASP ZAP daemon reachable from the MCP container, plus explicit `ZAP_API_URL`, `ZAP_API_PORT`, `ZAP_API_KEY`, and `MCP_API_KEY` configuration.

### Docker and Helm image tags

New Docker builds include:

```text
io.modelcontextprotocol.server.name=io.github.dtkmn/mcp-zap-server
```

For Helm, the default MCP image tag follows chart `appVersion`, now `v0.8.0`.

## Added

- Added `extensionApiJar`, `verifyExtensionApiPublication`, and standalone extension verification tasks.
- Added sample extension packaging for policy metadata and external extension compatibility checks.
- Added extension metadata, evidence metadata, policy, and protection API documentation.
- Added `zap_scan_history_release_evidence` and `zap_scan_history_customer_handoff`.
- Added scan-job claim manager, dispatcher, result applier, runtime executor, response formatter, retry policy, and state normalizer boundaries.
- Added Postgres-backed scan-job race harness coverage.
- Added MCP Registry metadata and `llms-install.md`.

## Changed

- Updated the extension API Maven group to `io.github.dtkmn`.
- Updated Docker packaging to select exactly one executable Spring Boot JAR.
- Updated release workflow validation for Compose manifests, extension API publication, and standalone extension builds.
- Updated README positioning around safe, self-hosted agentic scanning.
- Updated security docs to avoid overclaiming production readiness language.
- Updated docs dependencies and GitHub Pages workflow configuration.

## Security

- Added auth endpoint rate limiting coverage.
- Strengthened API-key property management.
- Documented that OCI package installs require an external OWASP ZAP daemon rather than bundling ZAP inside the MCP server image.

## Fixed

- Fixed Docker Compose workspace defaults so local Compose rendering uses repo-local workspace binds unless overridden.
- Fixed native image Docker metadata to expose `7456`, the actual MCP server port.
- Fixed Docker runtime image packaging ambiguity introduced by extension API and sample extension artifacts.

## Recommended Release Validation

- `./gradlew clean build`
- `./gradlew verifyExtensionApiPublication`
- `./gradlew -p examples/extensions/standalone-policy-metadata-extension clean build`
- `helm lint helm/mcp-zap-server`
- `npm --prefix docs run check`
- `npm --prefix docs run build`
- Build and inspect the release image label:

```bash
docker build -t ghcr.io/dtkmn/mcp-zap-server:v0.8.0 .
docker image inspect ghcr.io/dtkmn/mcp-zap-server:v0.8.0 \
  --format '{{ index .Config.Labels "io.modelcontextprotocol.server.name" }}'
```

Expected output:

```text
io.github.dtkmn/mcp-zap-server
```

## Full Changelog

- https://github.com/dtkmn/mcp-zap-server/compare/v0.7.0...v0.8.0
