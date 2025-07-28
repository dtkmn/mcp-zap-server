# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).


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
