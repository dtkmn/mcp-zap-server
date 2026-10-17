# Extension API Compatibility

This document defines the current compatibility contract for
`mcp-zap-extension-api`.

The API is experimental. Build extensions against the selected API version and
check runtime loading when upgrading; third-party binary compatibility across
releases is not guaranteed.

## Artifact

The build creates a dedicated API JAR:

```text
build/libs/mcp-zap-extension-api-<version>.jar
```

The Maven publication is:

```text
io.github.dtkmn:mcp-zap-extension-api:<version>
```

Use this coordinate with the locally staged repository while the API is
experimental. Do not assume it is available from Maven Central. Public
publication requires a verified namespace and a signed artifact set from the
public source tree, following the release policy below.

The release rules for moving beyond local staging live in
[Extension API Release Policy](./EXTENSION_API_RELEASE_POLICY.md).

## Optional Local Publication

Normal builds package the extension API JAR but do not stage or verify its
Maven publication. To stage the API locally when needed, run the generated
Gradle publication task:

```bash
./gradlew publishExtensionApiPublicPreviewPublicationToExtensionApiPublicPreviewStagingRepository
```

The staged artifacts are written under:

```text
build/extension-api-public-preview-publication/io/github/dtkmn/mcp-zap-extension-api/<version>/
```

To stage the API and compile the standalone sample against it in one optional
build, run:

```bash
./gradlew standalonePolicyMetadataExtensionJar
```

These tasks build and stage local artifacts; they do not perform custom POM/JAR
shape verification or publish to a remote repository. Public publishing still
requires the separate release policy checklist.

## Current Scope

The API artifact may expose:

- policy hooks
- policy bundle preview and access boundaries
- selected report/workspace protection boundaries
- evidence metadata enrichment contracts
- extension identity and compatibility metadata

The API artifact must not expose:

- application runtime services
- enterprise packages
- ZAP-native APIs
- MCP tool implementation classes
- queue or history boundaries that require mutable core model objects
- engine adapter contracts

## Stability Level

Current stability: `experimental`.

Rules while experimental:

- breaking changes are allowed in minor releases
- changes must be reflected in docs and compatibility tests
- extension samples must compile against the API JAR, not the full runtime
- extension samples must register through Spring Boot auto-configuration
- no public binary compatibility promise should be made

See the release policy for publication requirements and compatibility checks.

## Internal Boundaries Not Yet Public

Some boundaries are intentionally still internal.

`ScanJobAccessBoundary` and `ScanHistoryAccessBoundary` currently operate on
mutable gateway runtime implementation types. Exporting those signatures would
force external builders to depend on implementation models. They should only
move into the public API after safe view DTOs exist.

Engine adapter contracts are not part of this API artifact. External extensions
cannot add a supported scanner engine through the current standalone API.
