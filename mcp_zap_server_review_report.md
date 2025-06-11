# MCP-ZAP Server Review Report

## 1. Overview

The `mcp-zap-server` project is a Spring Boot application designed to expose OWASP ZAP's functionalities through the Model Context Protocol (MCP). This allows AI agents compatible with MCP (like Claude Desktop, Cursor, Open Web-UI) to orchestrate ZAP actions such as spidering, active scanning, importing OpenAPI specifications, and generating security reports.

The architecture, as depicted in the `README.md`, involves several Docker containers managed by `docker-compose.yml`:
- **OWASP ZAP:** The core security scanner.
- **MCP ZAP Server (this project):** A Spring Boot application that acts as a bridge between an MCP client and the ZAP API.
- **MCP File System Server:** Provides file system access via MCP.
- **MCP Client (Open Web-UI):** A web interface for interacting with MCP servers.
- **OWASP Juice-Shop:** A deliberately insecure web application for testing ZAP's scanning capabilities.
- **Swagger Petstore Server:** A sample API to demonstrate OpenAPI import and scanning.

The MCP ZAP Server communicates with ZAP via its REST API and with MCP clients via HTTP/SSE or STDIO.

## 2. Code Quality Analysis

Based on the review of `McpServerApplication.java`, `CoreService.java`, `ActiveScanService.java`, and `ZapApiConfig.java`:

*   **Modularity and Clarity:**
    *   The code is organized into packages (`configuration`, `service`).
    *   Services (`CoreService`, `ActiveScanService`, etc.) group related ZAP functionalities (e.g., core ZAP actions, active scan management). This promotes modularity.
    *   The use of Spring's `@Service` and `@Bean` annotations helps in managing dependencies and application structure.
    *   `McpServerApplication.java` is concise, primarily setting up Spring Boot and defining tool callbacks for MCP by gathering all `@Tool` annotated methods from the service beans.
    *   `ZapApiConfig.java` centralizes ZAP API client configuration, which is good practice. It uses Spring's `@Value` annotation to inject configuration properties from `application.yml`.
    *   Method names within services are generally descriptive (e.g., `getAlerts`, `activeScan`, `getActiveScanStatus`).
    *   The use of a `RestTemplate` bean in `McpServerApplication` is standard for Spring applications needing to make HTTP calls, though its direct use isn't apparent in the specific services reviewed (which use the `ClientApi` for ZAP interaction).

*   **Error Handling Practices:**
    *   Methods in `CoreService` and `ActiveScanService` (e.g., `getAlerts`, `activeScan`) declare `throws Exception`. This is a very generic way of handling exceptions. It forces the caller (ultimately the Spring AI framework and then the MCP client) to handle a broad `Exception`, lacking specificity.
    *   It would be better to catch specific exceptions from the ZAP `ClientApi` (e.g., `ClientApiException`) and either handle them gracefully (returning a user-friendly error message) or wrap them in custom, more meaningful business exceptions specific to the `mcp-zap-server`'s operations.
    *   In `ActiveScanService.activeScan()`, there's a check for `scanResp == null` which throws an `IllegalStateException`. This is good for handling unexpected API responses.
    *   In `ActiveScanService.getActiveScanStatus()`, an `IllegalStateException` is thrown if the ZAP API response is not of the expected type (`ApiResponseElement`).
    *   `ActiveScanService.stopAllScans()` includes a try-catch block for `Exception`, logging the error and returning an error message string. This is a more user-friendly approach for methods directly invoked by the AI agent.
    *   Overall, error handling should be more specific, consistent, and aim to provide clear feedback to the AI agent or user rather than relying on generic `throws Exception`.

*   **Input Validation:**
    *   In `CoreService.getAlerts()` and `CoreService.getUrls()`, `baseUrl` is checked for `null` before being used in the ZAP API call.
    *   The `activeScan` method in `ActiveScanService` takes `targetUrl`, `recurse` (String), and `policy` as parameters. There is no explicit validation shown for these inputs (e.g., checking if `targetUrl` is a valid URL format, if `recurse` is "true" or "false", or if `policy` is a non-empty string). Invalid inputs could lead to errors from the ZAP API that might be cryptic.
    *   While the ZAP API itself will perform validation, pre-validation in `mcp-server` could provide clearer error messages tailored to the MCP context. For example, ensuring `scanId` parameters are in the expected format.
    *   Consider using validation annotations (e.g., from Jakarta Bean Validation) on `@ToolParam` if Spring AI supports them, or manual checks at the beginning of methods.

*   **Logging Practices:**
    *   Lombok's `@Slf4j` annotation is used in `CoreService` and `ActiveScanService` for easy SLF4J logger injection. This is a common and good practice.
    *   Logging statements are present, for example, `log.info` in `ActiveScanService.activeScan()` upon starting a scan, and `log.error` in `ActiveScanService.stopAllScans()` when an exception occurs.
    *   The logging level for `mcp.server.zap.service.ZapService` is set to `DEBUG` in `application.yml`. Note that the actual service classes reviewed are in `mcp.server.zap.service.*`, so this specific configuration might be a typo or intended for a class not yet reviewed. A more general `logging.level.mcp.server.zap.service=DEBUG` might be more effective.
    *   Logging for parameters received in tool calls and results returned could be beneficial for auditing and debugging interactions with AI agents.

*   **Use of Lombok and Spring AI Annotations:**
    *   **Lombok:** `@Slf4j` is used for logging. No other Lombok annotations like `@Data`, `@Getter`, `@Setter`, or `@Builder` were observed in the reviewed files, but their use in other parts of the application (e.g., DTOs if any) would be typical.
    *   **Spring AI Annotations:**
        *   `@Tool` and `@ToolParam` (from `org.springframework.ai.tool.annotation`) are extensively used in the service classes. These are fundamental to the project, as they define the functions (tools) that the AI agent can invoke, along with descriptions for the AI to understand their purpose and parameters.
        *   `McpServerApplication.java` uses `@SpringBootApplication`.
        *   `@Configuration`, `@Bean`, and `@Value` are used for Spring's dependency injection and configuration management in `ZapApiConfig.java` and `McpServerApplication.java`.
        *   `@Service` annotates the service classes, making them Spring-managed beans.

## 3. Security Assessment

*   **ZAP API Key Configuration in `docker-compose.yml`:**
    *   The `zap` service in `docker-compose.yml` is configured with `api.disablekey=true` via command-line arguments.
    *   The `environment` section for the `zap` service also includes `ZAP_API_KEY: ""`.
    *   This configuration means the ZAP API instance running inside the Docker network does **not** require an API key.
    *   **Implications:** This simplifies communication between the `mcp-server` container and the `zap` container, as no API key needs to be shared or managed for this internal link. However, if the ZAP port (`8090`) were inadvertently exposed to an untrusted network (e.g., by changing `ports: - "8090:8090"` to `ports: - "0.0.0.0:8090:8090"` or due to Docker network misconfigurations), the ZAP API would be accessible without authentication.
    *   The `mcp-server`'s `application.yml` specifies `zap.server.apiKey: ${ZAP_API_KEY:}`. This means it attempts to use an environment variable `ZAP_API_KEY` if set, otherwise defaults to an empty string. This is consistent with ZAP running with a disabled/empty API key.
    *   For environments requiring higher security, enabling the ZAP API key and ensuring both ZAP and `mcp-server` are configured with it would be a necessary step.

*   **Security of the `mcp-server` itself (API key, authentication):**
    *   The `README.md` states: "Secure: Configure API keys for both ZAP (ZAP_API_KEY) and the MCP server (MCP_API_KEY)".
    *   However, the `application.yml` and the reviewed Java code for `mcp-server` do not show any explicit configuration or enforcement mechanism for such an `MCP_API_KEY`. The Spring AI MCP server starter might have provisions for security, but this is not evident from the current files and would need further investigation into Spring AI's documentation.
    *   If the `mcp-server` (listening on port `7456`) has no authentication layer, then any entity that can reach this port on the Docker host can issue commands to ZAP via this server. This is a significant security concern if the host machine is accessible from untrusted networks.
    *   An authentication mechanism (e.g., API key passed via HTTP header, token-based auth) should be implemented or verified for the `mcp-server` endpoint.

*   **`Dockerfile` Security Aspects:**
    *   **Base Image:** `eclipse-temurin:21-jdk-alpine`. Using Alpine Linux is good for reducing image size and potential attack surface. Specifying JDK 21 is also good. It's crucial to ensure this base image is kept up-to-date to incorporate security patches.
    *   **User Privileges:** The `Dockerfile` does **not** create a dedicated non-root user. The application `app.jar` will run as root inside the container by default. This is a security risk. A non-root user should be created, and the `USER` instruction should be used to switch to this user before the `ENTRYPOINT`.
    *   **Hardcoded Port:** `EXPOSE 7456` is standard for documenting the application's listening port.
    *   **JAR Copying:** `COPY build/libs/mcp-zap-server-*.jar ./app.jar` uses a wildcard. While common, it's safer if the build process produces a predictably named JAR or if the exact JAR name is specified to avoid copying unintended files if multiple JARs were present.

*   **Comment on `SECURITY.md`:**
    *   The `SECURITY.md` file is well-structured, providing clear instructions for responsibly reporting vulnerabilities via email to `danieltse@gmail.com`.
    *   It outlines supported versions and the expected process for handling vulnerabilities (acknowledgment, fix plan, disclosure).
    *   It correctly advises against opening public GitHub issues for security flaws.
    *   The scope is clearly defined to cover repository code, configurations, and scripts.
    *   The policy is based on GitHub's recommended template, which is a good practice.
    *   The `Last updated: 2025-05-27` date appears to be a placeholder or a typo, as it's in the future. This should be updated to the actual last modification date.

*   **Secrets Management in GitHub Actions:**
    *   The `ci.yml` and `release.yml` workflows use `secrets.GHCR_PAT` for logging into the GitHub Container Registry. This is the correct and secure way to handle Personal Access Tokens, by storing them as encrypted secrets in the GitHub repository/organization settings rather than hardcoding them.
    *   No other sensitive data or secrets appear to be directly embedded in the workflow files.

## 4. CI/CD Pipeline Review

*   **Current CI/CD Practices (`ci.yml`, `release.yml`):**
    *   **`ci.yml` (Build, Test, Docker on all branches):**
        *   Triggered on `push` to any branch.
        *   Steps: Checkout code, setup JDK 21, setup Gradle, run `./gradlew build --scan` (which should compile, run tests, and build the JAR), log in to GHCR, build and push Docker image tagged with the short commit SHA.
    *   **`release.yml` (GitHub Releases, versioned Docker images):**
        *   Triggered on `release` event (when a new release is published on GitHub).
        *   Similar steps: Checkout, setup JDK, setup Gradle, run `./gradlew build --scan`, log in to GHCR, build and push Docker image tagged with the release version (e.g., `v0.1.0`).
    *   The workflows are logical for a typical build and release process. The use of `--scan` with Gradle build provides insights into the build process.

*   **Identify Areas for Improvement:**
    *   **SAST (Static Application Security Testing):** No SAST tools (e.g., GitHub's own CodeQL, SonarCloud, Snyk Code) are integrated. Adding SAST would automatically scan the Java codebase for potential security vulnerabilities on each push/PR.
    *   **DAST (Dynamic Application Security Testing):** While this project *is* a ZAP integration, it doesn't appear to use ZAP (or another DAST tool) to scan any test application (like the included Juice Shop or Petstore) as part of an automated CI step. A DAST stage could be added, perhaps running a ZAP baseline scan against one of the test applications orchestrated by the newly built `mcp-zap-server`.
    *   **Dependency Vulnerability Scanning:**
        *   `build.gradle` lists several third-party dependencies.
        *   While `./gradlew build` handles compilation, dedicated dependency scanning tools (e.g., OWASP Dependency-Check, Snyk Open Source, GitHub's Dependabot alerts/updates) should be explicitly integrated or enabled. Dependabot is highly recommended for GitHub projects to automatically check for vulnerable dependencies and suggest updates.
    *   **Docker Image Vulnerability Scanning:** After the Docker image is built, it should be scanned for known vulnerabilities in the base image OS packages and any added layers (e.g., using tools like Trivy, Clair, Snyk Container, or GitHub's built-in image scanning if available for GHCR).
    *   **Test Coverage Reporting:** If and when tests are added, integrating a tool like JaCoCo for code coverage and uploading reports to a service like Codecov or Coveralls would be beneficial to track test quality.
    *   **Linting/Code Style Checks:** Consider adding tools like Checkstyle or PMD to enforce coding standards and identify potential code quality issues.

## 5. Testing

*   **Presence of testing dependencies in `build.gradle`:**
    *   The `build.gradle` file correctly includes:
        *   `testImplementation 'org.springframework.boot:spring-boot-starter-test'` (for JUnit, Mockito, Spring Test utilities).
        *   `testImplementation 'io.projectreactor:reactor-test'` (useful for reactive components, though the reviewed services are synchronous).
        *   `testRuntimeOnly 'org.junit.platform:junit-platform-launcher'`.
    *   The `tasks.named('test') { useJUnitPlatform() }` block configures the test task to use JUnit Platform (for JUnit 5).
    *   These dependencies show that the project is set up to support modern Java testing practices.

*   **Comment on the (apparent) lack of actual test files in the source code explored so far:**
    *   The `ls` commands executed so far did not specifically list contents of `src/test/java/`. A separate `ls("src/test/java")` would be needed to confirm.
    *   However, it is common for projects in early stages or proof-of-concepts to lack comprehensive tests.
    *   The `./gradlew build` command in `ci.yml` will execute the `test` task. If there are no tests, this task will pass trivially.
    *   **If tests are indeed missing, this is a major gap.** Unit tests are essential for verifying the logic of individual components (e.g., services, ensuring correct ZAP API calls are formed). Integration tests could involve using Testcontainers to spin up a real ZAP instance and verify the interactions. Without tests, ensuring correctness and preventing regressions as the codebase evolves is very difficult.

## 6. Documentation Review

*   **Assess the quality and completeness of `README.md`:**
    *   **Strengths:**
        *   The `README.md` is comprehensive and well-structured.
        *   It clearly states the project's purpose, its "work in progress" status, and that it's not affiliated with OWASP.
        *   The demo video link is a great addition.
        *   The Table of Contents aids navigation.
        *   The "Features" section is clear.
        *   The "Architecture" diagram using Mermaid is excellent for visualizing the system components and their interactions.
        *   "Prerequisites" are well-defined.
        *   The "Quick Start" guide with `docker-compose` instructions and screenshots for Open Web-UI setup is user-friendly.
        *   The "Services Overview" provides a good summary of each container in the `docker-compose` stack.
        *   Instructions for manual build and usage with MCP clients (STDIO and SSE modes) are provided with configuration examples.
        *   "Prompt Examples" with screenshots are very helpful for understanding how to interact with the server using an AI agent.
    *   **Completeness:** The README covers most aspects a user would need to understand the project, get it running, and start using it.

*   **Identify any gaps or areas for improvement in documentation:**
    *   **Detailed Tool API Documentation:** While "Prompt Examples" show usage, a dedicated section listing all available MCP tools (Java methods annotated with `@Tool`), their specific parameters (`@ToolParam`), expected input formats, and return values would be beneficial for users and developers. This could be auto-generated or manually maintained.
    *   **Configuration Options:** A consolidated list of all configurable environment variables or `application.yml` properties for `mcp-server` (e.g., `ZAP_API_URL`, `ZAP_API_PORT`, `ZAP_API_KEY`, server port, logging levels, and the potential `MCP_API_KEY`).
    *   **Troubleshooting Guide:** A section for common problems (e.g., ZAP connection issues, scan failures, Docker issues) and their solutions.
    *   **Developer's Guide:** For potential contributors: information on setting up a development environment locally (outside Docker, if intended), coding conventions, how to add new ZAP tools/services, and the testing strategy (once tests are in place).
    *   **Security Section in README:** Briefly mention the security aspects, like the default ZAP API key status and recommendations for securing the `mcp-server` deployment, linking to `SECURITY.md` for vulnerability reporting.

## 7. Potential Future Enhancements

*   **More Granular ZAP Tools:**
    *   Expose more specific ZAP functionalities beyond broad scans:
        *   **Context Management:** Tools to create, configure, and manage ZAP contexts (e.g., defining scope, technology stack, authentication methods).
        *   **Session Management:** Tools for loading, saving, and switching ZAP sessions.
        *   **Scan Policy Management:** Tools to list available scan policies, create/configure policies (e.g., enable/disable specific scanners, adjust alert thresholds, attack strength).
        *   **Script Management:** Tools to load and manage ZAP scripts (e.g., HTTP sender, active/passive scan rules).
        *   **Alert Management:** More detailed alert fetching (e.g., by risk, by CWE), and potentially tools to mark alerts as false positives or handle them.
        *   **Automation Framework:** Tools to configure and run ZAP's Automation Framework plans.
*   **Improved Configuration and Security:**
    *   Implement robust API key authentication for the `mcp-server` itself (the `MCP_API_KEY` mentioned in README).
    *   Allow dynamic configuration of ZAP settings (like those in `ActiveScanService.activeScan` that are currently commented out or fixed) via tool parameters.
*   **Enhanced Feedback and Asynchronous Handling:**
    *   For long-running ZAP operations (active scans, spidering), provide more robust asynchronous support. While scan status tools exist, consider mechanisms for completion notifications or streaming intermediate results if feasible with MCP.
*   **User Interface / Helper Tools:**
    *   While the primary interface is AI agents, a simple diagnostic web UI for `mcp-server` could be useful for administrators to check status and basic ZAP connectivity.
*   **Multi-Target/Context Operations:** Design tools that can orchestrate scans or actions across multiple targets or ZAP contexts more easily.

## 8. Summary of Key Recommendations

1.  **Implement Comprehensive Testing:** This is the most critical area. Develop unit tests for service logic (mocking `ClientApi`) and integration tests (e.g., using Testcontainers with ZAP) to ensure robustness and facilitate safer refactoring.
2.  **Bolster Security Posture:**
    *   **Container Security:** Add a non-root user in the `Dockerfile` for `mcp-server`.
    *   **Server Authentication:** Implement and enforce an API key or other authentication mechanism for the `mcp-server` endpoint (port `7456`).
    *   **ZAP API Key:** For production-like deployments, recommend and document enabling the ZAP API key and configuring it in both ZAP and `mcp-server`.
3.  **Mature CI/CD Pipeline:**
    *   Integrate SAST (e.g., CodeQL).
    *   Enable/integrate dependency vulnerability scanning (e.g., Dependabot).
    *   Add Docker image vulnerability scanning (e.g., Trivy).
4.  **Refine Error Handling:** Transition from generic `throws Exception` to specific, custom exceptions or well-defined error responses within the service methods. Ensure clear error propagation to the MCP client.
5.  **Strengthen Input Validation:** Implement validation for parameters received by `@Tool` methods to provide early and clear feedback for invalid inputs.
6.  **Documentation Accuracy & Expansion:**
    *   Correct the `Last updated` date in `SECURITY.md`.
    *   Create a detailed API reference for all MCP tools.
    *   Add a developer guide and a troubleshooting section.
7.  **Code Structure (Minor):** Review logging configuration in `application.yml` for optimal targeting of service classes.

This review provides a snapshot based on the provided codebase artifacts. Further interactive testing and deeper dives into specific components would yield more insights.
