# Release Notes - Version 0.9.1

**Release Date:** June 26, 2026

## Highlights

- Refreshed the `v0.9.0` gateway-core runtime integration with `mcp-gateway-core` and `mcp-gateway-spring-webflux` `0.5.10`.
- Updated Spring Boot to `4.0.7`, Spring AI to `2.0.0`, Gradle to `9.6.0`, and the docs stack to Astro `7.0.3`.
- Overrode Spring Boot's Jackson 2 BOM to `2.22.0` so runtime dependency resolution moves off `com.fasterxml.jackson.core:jackson-databind` `2.21.4`.
- Hardened Snyk workflow behavior by requiring explicit organization configuration and failing when expected SARIF artifacts are missing.
- Archived historical release notes under `docs/releases/` without changing the `v0.9.0` release snapshot.

## Upgrade Notes

### Release metadata

The release metadata is set to `0.9.1` in:

- `build.gradle`
- `src/main/resources/application.yml`
- `.mcp/server.json`
- `helm/mcp-zap-server/Chart.yaml`

The Helm chart version is `0.9.1`; `appVersion` is `v0.9.1` so the default image tag matches GitHub release image tags.

### Jackson 2 dependency management

Spring Boot `4.0.7` manages the Jackson 2 BOM at `2.21.4`. This release overrides `jackson-2-bom.version` to `2.22.0` to avoid publishing a new server image with an open `jackson-databind` advisory on the resolved runtime graph.

### Compatibility

This is a maintenance release. It does not add database migrations, change MCP tool names, or change the guided/expert tool-surface contract.

## Changed

- Updated release metadata, registry metadata, install snippets, Helm chart metadata, and security policy support tables for `0.9.1`.
- Updated `gatewayCoreVersion` to `0.5.10` for both `io.github.dtkmn:mcp-gateway-core` and `io.github.dtkmn:mcp-gateway-spring-webflux`.
- Updated Spring AI from `2.0.0-RC1` to `2.0.0`.
- Updated Spring Boot from `4.0.6` to `4.0.7`.
- Updated Gradle from `9.5.0` to `9.6.0`.
- Updated docs dependencies including Astro `7.0.3`, Starlight `0.41.1`, Sharp `0.35.2`, PostCSS `8.5.15`, and Esbuild `0.28.1`.
- Moved release-note snapshots from the repository root into `docs/releases/`.

## Security

- Overrode `jackson-2-bom.version` to `2.22.0` so the runtime no longer resolves `com.fasterxml.jackson.core:jackson-databind` `2.21.4`.
- Strengthened the Snyk workflow to require `SNYK_ORG` and fail when scan commands do not produce SARIF output.
- Kept Snyk monitor steps non-blocking and bounded with explicit timeouts.

## Fixed

- Corrected README latest-release messaging so the archived `v0.9.0` release notes continue to describe the actual `v0.9.0` dependency set.

## Verification

Before publishing the release tag, run:

```bash
./gradlew clean build cyclonedxBom --no-daemon --stacktrace
./gradlew verifyExtensionApiPublication --no-daemon --stacktrace
./gradlew -p examples/extensions/standalone-policy-metadata-extension clean build --no-daemon --stacktrace
./gradlew dependencyInsight --dependency jackson-databind --configuration runtimeClasspath --no-daemon
```

Expected runtime dependency resolution includes `com.fasterxml.jackson.core:jackson-databind:2.22.0`.

## Diff

- https://github.com/dtkmn/mcp-zap-server/compare/v0.9.0...v0.9.1
