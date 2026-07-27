# Release Notes - Version 0.11.0

**Release Date:** July 27, 2026

## Highlights

- Built the published JVM image on a Cosign-verified, digest-pinned distroless Java 25 runtime with a shell-free HTTP health probe.
- Updated `mcp-gateway-core` and `mcp-gateway-spring-webflux` to `0.8.0` and migrated application data binding to Jackson 3 under the `3.2.1` BOM.
- Removed the unsupported native-image deployment path.
- Stopped publishing rolling `main` and `sha-*` images from main CI; versioned AMD64 and ARM64 publication remains release-only.
- Tightened GitHub and GitLab security-gate validation of image references before workspace mutation.

## Upgrade Notes

### Container runtime

The published JVM image is now distroless. It intentionally contains no shell,
`curl`, package manager, or general-purpose debugging utilities. Use application
logs, Actuator endpoints, Kubernetes-native probes, or an external diagnostic
container. The built-in `/usr/local/bin/http-healthcheck` remains available for
bounded HTTP reachability checks, and the existing Actuator health endpoint is
unchanged. Replace custom exec probes or hooks that call a shell or `curl`.

Runtime UID/GID remains `1000`; Helm now states both values explicitly. Existing
mounted files, secrets, and workspaces must remain readable or writable by that
identity as appropriate.

Docker Compose and the standalone installation commands now pin
`zaproxy/zap-stable:2.17.0`, matching the Helm default and Docker-backed
integration coverage.

### Removed native-image deployment path

`Dockerfile.native`, `docker-compose.prod.yml`, and `prod.sh` are removed. Deploy
the versioned JVM image directly or through Helm. The base Compose file is still
the easiest local path, but it is not a substitute for a hardened production
configuration with explicit authentication, secrets, URL policy, persistence,
and network controls.

### Image publication

Main CI no longer publishes `main` or `sha-<commit>` image tags. Existing tags
may remain in registries but will not receive new builds. Pin `v0.11.0` or an
exact release-image digest. Stable releases continue to publish AMD64 and ARM64
images to GHCR and Docker Hub. Registry tags are still mutable references; an
exact digest is the strongest production pin.

### Repository verification helper

The legacy repository-only `bin/github-ci-pack-verify.sh` aggregate verifier is
removed. Maintainers should run the Python CI-helper unit tests for local
behavior checks and use the Juice Shop workflow for the live end-to-end gate.

### Jackson 3 migration

Application JSON handling and gateway WebFlux governance now use Jackson 3 APIs
under `tools.jackson.*`, managed by the Jackson `3.2.1` BOM. Jackson 3
intentionally continues to use the `com.fasterxml.jackson.core:jackson-annotations`
`2.22` artifact; that annotation coordinate does not mean the data-binding
runtime is still Jackson 2.

Custom extensions or downstream code that depend on internal Jackson 2
implementation types must migrate and rebuild.

## Compatibility

- No database migration is required.
- MCP tool names and input schemas are unchanged from `v0.10.1`.
- API-key, JWT, guided-auth profile, and runtime-policy configuration contracts are unchanged.
- `zap_scan_history_get` and `zap_scan_history_export` were already exposed on the guided surface; their registry metadata now matches that runtime behavior.
- `mcp-zap-extension-api` remains experimental/local and carries no long-term binary compatibility promise. Its public API source contract is unchanged.

## Security and Supply Chain

- Container build, health-probe, runtime, and release BuildKit inputs are pinned by digest.
- Main and release workflows verify the upstream distroless base signature with Cosign before building the application image.
- GitHub and GitLab security-gate helpers reject malformed SHA-256 image digests before creating workspace directories.
- Helm explicitly sets the MCP container to UID/GID `1000`.

## Runtime and Build Versions

- Java `25`
- OWASP ZAP `2.17.0`
- Spring Boot `4.1.0`
- Spring AI `2.0.0`
- `mcp-gateway-core` and `mcp-gateway-spring-webflux` `0.8.0`
- Jackson BOM `3.2.1`
- Netty `4.2.16.Final`
- PostgreSQL JDBC `42.7.12`
- Logback `1.5.36`
- Gradle `9.6.1`
- CycloneDX plugin `3.3.0`
- Testcontainers `2.0.5`

## Extension API

`mcp-zap-extension-api` remains `experimental-local`. The locally staged proof
now uses version `0.11.0`; the artifact is not published by the release workflow.

## Diff

- https://github.com/dtkmn/mcp-zap-server/compare/v0.10.1...v0.11.0
