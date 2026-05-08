# Extension API Compatibility

This document defines the current compatibility contract for
`mcp-zap-extension-api`.

Blunt version: the artifact now exists, but it is experimental. It is good
enough to prove the decoupled developer path. It is not yet a promise that any
third-party binary extension will keep working across releases.

## Artifact

The build creates a dedicated API JAR:

```text
build/libs/mcp-zap-extension-api-<version>.jar
```

The Maven publication is:

```text
mcp.server.zap:mcp-zap-extension-api:<version>
```

That group is not guaranteed to be the final public coordinate. The public
coordinate should be confirmed before publishing outside the project.

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
- engine adapter contracts before the engine extension ADR is accepted

## Stability Level

Current stability: `experimental`.

Rules while experimental:

- breaking changes are allowed in minor releases
- changes must be reflected in docs and compatibility tests
- extension samples must compile against the API JAR, not the full runtime
- extension samples must register through Spring Boot auto-configuration
- no public binary compatibility promise should be made

Graduating this API requires:

- a standalone sample extension repository or fixture
- compatibility tests that use only the published API artifact
- migration notes for any breaking API change
- an agreed public Maven coordinate
- explicit support policy for how long old API versions are supported

## Internal Boundaries Not Yet Public

Some boundaries are intentionally still internal.

`ScanJobAccessBoundary` and `ScanHistoryAccessBoundary` currently operate on
core runtime types. Exporting those signatures would force external builders to
depend on mutable implementation models. They should only move into the public
API after safe view DTOs exist.

Engine adapter contracts are also not part of the first API artifact. They need
the engine extension ADR first.
