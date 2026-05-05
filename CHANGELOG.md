# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.7.0] - 2026-05-05

### Added
- Added Policy Bundle v1 dry-run and runtime enforcement support, including `zap_policy_dry_run`, policy preview contracts, shared-core enforcement hooks, and example policy bundles.
- Added guided auth session bootstrap tools for form-login, bearer-token, and API-key flows, with prepared session validation.
- Added a scan history ledger with in-memory and Postgres backends, release-evidence export tools, and the `V5__create_scan_history_entries.sql` migration.
- Added a gateway boundary layer around ZAP engine access, including capability-specific interfaces and record types for scans, findings, targets, and artifacts.
- Added production and auth-bootstrap runbooks, Prometheus alerts, a Grafana dashboard, and expanded architecture, unit, and integration coverage.

### Changed
- Bumped the runtime, MCP server, and Helm chart release metadata to `0.7.0`.
- Removed the vendored WebFlux streamable MCP transport shim and expanded streamable HTTP regression coverage.
- Refactored core services to use explicit gateway capability interfaces instead of direct ZAP client coupling.
- Upgraded Gradle to `9.5.0` and refreshed docs dependencies, including Astro and `@astrojs/compiler`.
- Bound Docker Compose-published ports to loopback by default via `MCP_ZAP_BIND_ADDRESS`.

### Security
- Added early MCP request body limiting through `McpRequestBodyLimitWebFilter`.
- Hardened guided auth credential references with operator-managed allowlists, path containment checks, and inline-secret controls.
- Added same-origin form-login validation, URL-encoded ZAP auth config serialization, stricter field-name validation, and quoted context regex literals.
- Added stricter Helm session-affinity validation and MCP/ZAP egress NetworkPolicy boundaries.
- Kept malformed upstream ZAP progress values out of client-visible exception messages.

### Fixed
- Fixed multi-replica Helm defaults that could break streamable MCP sessions without sticky affinity.
- Fixed credential allowlist wildcard sibling-prefix and symlink escape handling.
- Fixed mixed-case Helm session affinity provider values silently rendering no affinity.
- Fixed ZAP form-auth parameter injection and context scope regex overmatching risks.
- Fixed CodeQL findings around deprecated JSON field iteration and ignored access-boundary parameters.

### Documentation
- Added release notes for `0.7.0`.
- Added scan history ledger documentation and updated production, observability, JWT, tool-scope authorization, security mode, security policy, and Helm docs.

## [0.6.1] - 2026-04-28

### Changed
- Upgraded Spring Boot, Gradle, AspectJ, CycloneDX, Astro, and Starlight patch-level dependencies used by the runtime, build, SBOM, and docs site.
- Updated Docker and Compose defaults, including Gradle build image alignment and container healthcheck/configuration refinements.
- Improved CI, release, Pages, dependency-submission, and branch-image publishing workflows, including newer GitHub Actions versions, manual release inputs, release image tagging, and multi-platform release image builds.
- Added CodeQL analysis for Java and JavaScript to improve repository security scanning coverage.
- Aligned documented ZAP addon configuration with Helm values.

### Fixed
- Fixed streamable HTTP MCP responses returning `500 Internal Server Error` after `initialize` because the WebFlux SSE transport passed a null message id to Spring Framework 7.
- Restored `tools/list`, `prompts/list`, and `resources/list` event-stream responses after a successful Streamable HTTP MCP handshake.

### Documentation
- Added contribution guidelines, pull request template, issue templates, CODEOWNERS, and Contributor Covenant Code of Conduct.
- Refined README positioning, usage guidance, vulnerability reporting instructions, and security policy documentation.

### Tests
- Added regression coverage for the `initialize` -> `tools/list` streamable HTTP flow.


## [0.6.0] - 2026-03-20

### Added
- Default guided MCP tool surface with a separate expert surface for raw ZAP workflows, controlled by `MCP_SERVER_TOOLS_SURFACE`.
- Guided security workflow tools for target import, crawl start/status/stop, attack start/status/stop, findings summary/details, and guided report generation.
- Expert tool groups for inventory, direct scans, queue lifecycle management, API imports, findings/reporting, authenticated scanning setup, and Automation Framework plans.
- Passive scan status/wait tools, findings snapshots/diffs, bounded alert instance views, API schema imports for OpenAPI/GraphQL/SOAP, active-scan policy controls, and queued AJAX Spider support.
- Tool-scope authorization with configurable `off`, `warn`, and `enforce` modes, plus startup validation for public tool scope mappings.
- Abuse-protection controls for request throttling, workspace quotas, and overload shedding of MCP traffic.
- Structured request correlation via `X-Correlation-Id`, centralized observability services, audit-event publishing, and Prometheus-ready metrics for auth, authorization, tools, queue, and protection flows.
- Expanded Flyway migrations and shared-state handling for queue idempotency, claim ownership, and recovery metadata.
- Helm enhancements including HA value presets, cloud values, migration jobs, network policies, and supporting shared database migration assets.
- Architecture, unit, integration, and Docker/Testcontainers coverage for the expanded tool surface, queue model, schema imports, automation plans, findings/reporting, and passive scan behavior.

### Changed
- The default MCP experience now favors the smaller guided tool surface instead of exposing the full raw control plane by default.
- Multi-replica queue execution now centers on claim-based worker ownership and recovery rather than a simple single-dispatcher leadership model.
- `application.yml` and runtime configuration now expose broader controls for authorization, protection, observability, queue coordination, revocation, and tool-surface behavior.
- `ScanJob`, `ScanJobStore`, `ScanJobQueueService`, and Postgres-backed queue state handling were expanded for idempotent admission, durable claims, recovery, dead-letter replay, and richer job status visibility.
- `McpServerApplication` now assembles the MCP tool surface from guided, passive-scan, and expert tool groups instead of relying on tool annotations directly on core services.
- README, root quick-start guidance, and the docs site were rewritten to match the current runtime, auth flow, streamable MCP session behavior, and queue semantics.

### Fixed
- Removed stale documentation examples that implied fake HTTP scan endpoints or invalid client auth patterns.
- Corrected queue and HA documentation to match the implemented claim-based execution model.
- Improved traceability of auth, authorization, validation, and protection failures through correlation IDs and bounded error payloads.

### Documentation
- Added public docs for tool surfaces, tool-scope authorization, abuse protection, observability, structured logging, scan execution modes, passive scan handling, API schema imports, scan policy controls, findings/reporting, and Automation Framework support.
- Expanded legacy `.html` redirect coverage so older doc links continue to resolve to the current Astro/Starlight site.

## [0.5.0] - 2026-03-14

### Added
- Persistent scan job orchestration via `ScanJob`, `ScanJobQueueService`, and `ScanJobStore` implementations for in-memory and Postgres backends.
- `ContextUserService` for managing ZAP contexts, users, and authentication setup for more advanced scan workflows.
- Queue leadership coordination with single-node and Postgres advisory lock strategies for multi-replica deployments.
- Flyway migrations and Postgres schema readiness validation for shared queue and token revocation state.
- `TokenRevocationStore` backends plus `TokenBlacklistService` support for pluggable JWT revocation persistence.
- `SecurityStartupValidator` and expanded test coverage for safer startup defaults and auth configuration validation.

### Changed
- Reorganized core runtime classes under the `mcp.server.zap.core` package for clearer separation of concerns.
- Enhanced URL validation, scan queue configuration, retry/backoff behavior, and scheduling support for scan execution.
- Upgraded the Gradle wrapper and Docker build image to `9.4.0`.
- Upgraded the CycloneDX plugin to `3.2.2` and added Flyway/PostgreSQL runtime dependencies.
- Migrated the public docs site from Jekyll to Astro + Starlight while preserving legacy `.html` entry points via redirects.

### Fixed
- Fail-fast validation for insecure placeholder API key configurations in stricter deployments.
- Markdown rendering issue in the authenticated scanning guide that previously broke the Pages build.

### Documentation
- Expanded public documentation with authenticated scanning best practices, production readiness guidance, HA Compose simulation, queue coordination, retry policy, and JWT rotation guidance.
- Updated the README, docs site structure, and Pages workflow for the Astro-based documentation stack.

## [0.4.0] - 2026-02-26

### Added
- New `zap_get_findings_summary` tool in `ReportService` to generate token-optimized Markdown summaries grouped by risk level and alert type.
- `ZapHealthIndicator` for reactive ZAP API connectivity checks via Spring Boot Actuator.
- `GlobalExceptionHandler` with consistent JSON error responses for ZAP API, validation, and unexpected server errors.
- CycloneDX SBOM generation and artifact upload steps in both CI and release workflows.
- Manual `workflow_dispatch` support for CI and release workflows, including custom version input for release builds.

### Changed
- Upgraded Spring Boot plugin to `4.0.3`.
- Upgraded Spring AI BOM to `1.1.2`.
- Upgraded Gradle wrapper to `9.3.1` and Docker build image to `gradle:9.3.1-jdk25`.
- Upgraded ZAP client API to `1.17.0` and added `spring-boot-starter-validation`.
- Updated release workflow image tagging/version resolution and Docker registry publishing behavior (versioned + `latest` tags).
- Updated CI Docker publishing to include `dev` tags and SBOM artifacts.
- Updated `docker-compose.yml` images for MCP ZAP Server (`dtkmn/mcp-zap-server`), Juice Shop (`v19.1.1`), and Petstore (`1.0.27`).

### Fixed
- Resolved transitive `org.jdom` conflict by excluding legacy `jdom` and explicitly adding `org.jdom:jdom2:2.0.6.1`.
- Hardened Actuator health endpoint exposure by setting `management.endpoint.health.show-details: never`.
- Clarified URL whitelist/blacklist behavior comments in `application.yml`.

### Documentation
- Added non-affiliation/project-positioning note and expanded enterprise/commercial support details in `README.md`.
- Added commercial/private vulnerability reporting section to `SECURITY.md`.
- Added commercial support contact section to docs homepage (`docs/index.md`).

## [0.3.0] - 2025-11-22

### Changed
- **Framework**: Upgraded Spring Boot from 3.5.7 to 4.0.0
- **Security**: Migrated from JJWT to Spring Security OAuth2 JWT for GraalVM native image compatibility
- **Security**: CSRF protection now disabled in `none` mode for MCP protocol compatibility
- **Security**: CSRF protection enabled by default in `api-key` and `jwt` modes (Spring Security default)
- **Security**: Changed default security mode to disabled (`MCP_SECURITY_ENABLED=false`) in docker-compose for development
- **Performance**: Added GraalVM native image support with 10x faster startup time (0.6s vs 3-5s)
- **Docker**: Added healthcheck to Dockerfile.native for container orchestration
- **Docker**: Added curl to runtime dependencies for healthcheck support
- **Tests**: Removed deprecated `@AutoConfigureWebTestClient` annotation (auto-configured in Spring Boot 4.0.0)

### Added
- GraalVM native image build support via `Dockerfile.native`
- Spring Security OAuth2 JWT library for native-image-ready JWT handling
- Comprehensive CSRF documentation in README, SECURITY_MODES.md, and JWT_AUTHENTICATION.md
- Development and production Docker Compose profiles (`docker-compose.dev.yml` and `docker-compose.prod.yml`)
- Quick start scripts (`dev.sh` for JVM builds, `prod.sh` for native builds)
- Native image performance documentation (`docs/NATIVE_IMAGE_PERFORMANCE.md`)

### Removed
- JJWT library (incompatible with GraalVM native images)
- Native-image reflection configuration files (no longer needed with Spring Security OAuth2)
- Deprecated `@AutoConfigureWebTestClient` annotation from tests

## [0.2.1] - 2025-10-24

### Changed
- Updated healthcheck command in `docker-compose.yml` for the `zap` service.
- Updated various GitHub Actions to newer versions in build and release workflows.
- Enabled multi-platform Docker builds for `linux/amd64` and `linux/arm64`.
- Upgraded Gradle from 8.14.2 to 9.1.0.
- Upgraded Spring Boot from 3.5.3 to 3.5.7.
- Switched from `spring-ai-starter-mcp-server-webmvc` to `spring-ai-starter-mcp-server-webflux`.
- Updated ZAP API key configuration to be mandatory.

### Added
- A new test profile `application-test.yml` for testing.


## [0.2.0] – 2025-06-27

### Added

- Dependabot configuration for automated dependency updates.
- Custom `ZapApiException` for ZAP API related errors.

### Changed

- Updated CI/CD workflows for improved automation.
- Modified Dockerfile and Docker Compose configurations.
- Updated Gradle build configuration and wrapper version.
- Refinements to the main application class (`McpServerApplication.java`).
- Adjusted application properties in `application.yml`.

## [0.1.1] – 2025-06-11

### Added

- Initial project configuration files (`.dockerignore`, `.gitignore`, `SECURITY.md`).
- Basic application test class (`McpServerApplicationTests.java`).

### Changed

- Updated CI/CD workflows.
- Updated Docker Compose configuration.

## [0.1.0] – 2025-05-27

### Added

- Expose OWASP ZAP actions (spider, active scan, import OpenAPI specs, HTML/JSON report generation) as MCP tools in a Spring Boot application.

- Eliminate manual CLI calls by integrating ZAP with MCP-compatible AI agents (e.g., Claude Desktop, Cursor, Windsurf).

- Support remote OpenAPI specification import into ZAP for automated scanning workflows.

- Docker Compose orchestration for ZAP, MCP server, MCP File System, Open Web-UI, Juice Shop, and Swagger Petstore services.

- Secure configuration using ZAP_API_KEY and MCP_API_KEY environment variables.

- Provide both STDIO (-Dspring.ai.mcp.server.stdio=true) and HTTP SSE transport modes for MCP communication.

- Offer Web UI panels to configure OpenAI/Ollama API connections and MCP server endpoints.

- Include prompt examples for orchestrating ZAP scans and retrieving scan results via any MCP-compatible AI agent.

- Manual build support via Gradle (./gradlew clean build) and log management commands (docker-compose logs -f).
