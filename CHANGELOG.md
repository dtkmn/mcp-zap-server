# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
