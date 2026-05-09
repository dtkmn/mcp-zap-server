---
title: "Extension API Release Policy"
editUrl: false
description: "Publication stages, versioning rules, and compatibility gates for mcp-zap-extension-api."
---
This document explains how `mcp-zap-extension-api` should move from local proof
to a public artifact builders can trust.

Blunt version: publishing the API JAR is easy. Promising compatibility is the
expensive part. Do not invite external builders to depend on this artifact
until the release path, versioning rules, and compatibility proof are all in
place.

## Current Status

Current status: `experimental-local`.

That means:

- the project builds `mcp-zap-extension-api`
- normal builds verify a local staged Maven publication
- the standalone sample resolves the API from `build/extension-api-publication`
- no public artifact repository is promised
- no third-party binary compatibility window is promised

The current coordinate is:

```text
mcp.server.zap:mcp-zap-extension-api:<version>
```

Treat that coordinate as project-local until a public repository is declared.
The public-preview coordinate decision is documented below; do not assume the
local staged group is the public Maven group.

## Release Stages

| Stage | What It Means | Builder Promise |
| --- | --- | --- |
| `experimental-local` | API is staged locally and used by in-repo compatibility proof. | No public dependency. No binary compatibility promise. |
| `public-preview` | API is published to a public artifact repository for early builders. | Best-effort source compatibility inside the same minor line. Breaking changes may still happen with release notes. |
| `stable` | API has compatibility tests, migration notes, and a support window. | Semantic versioning applies to public API contracts. Breaking changes require a major version. |

Do not skip stages. A public artifact without a compatibility gate creates
support debt before the product has earned trust.

## Repository Policy

Preferred target for public-preview and stable public releases: Maven Central.

Reason: external builders should not need private credentials, GitHub account
permissions, or a custom company package registry just to compile an OSS
extension.

Allowed repositories by stage:

- `experimental-local`: `build/extension-api-publication` only
- `public-preview`: Maven Central under a verified public namespace
- `stable`: Maven Central
- private enterprise-only artifacts: private registry is allowed, but those
  artifacts must not be required by OSS extensions

The release workflow must not publish the API from any non-public source tree
unless the OSS export has already proven the same source is public-safe.

## Public-Preview Publishing Decision

Decision for the first public preview:

- target repository: Maven Central
- target public coordinate: `io.github.dtkmn:mcp-zap-extension-api:<version>`
- local staged coordinate remains:
  `mcp.server.zap:mcp-zap-extension-api:<version>`
- namespace requirement: `io.github.dtkmn` must be verified in the Central
  Portal before publication
- artifact ID: `mcp-zap-extension-api`
- Java package: `mcp.server.zap.extension.api`

Do not publish `mcp.server.zap:mcp-zap-extension-api` publicly unless that
namespace is explicitly verified and the release policy is updated first. The
current `mcp.server.zap` group is useful for local proof because it matches the
project package, but it is not a responsible public Maven coordinate unless the
namespace can be owned.

The public-preview release workflow must be wired from the OSS-safe source tree
and must produce the Central-required publication shape:

- main API JAR
- sources JAR
- Javadoc JAR
- POM with required metadata
- artifact signatures or the active Central-required signing mechanism
- release notes that mark the artifact as `public-preview`

Use the Central Publisher Portal path, not GitHub Packages, for the public API
artifact. GitHub Packages is fine for private experiments, but it teaches the
wrong developer path because unauthenticated OSS builders should be able to
resolve the API with normal Maven tooling.

Preview wording must be exact:

> `mcp-zap-extension-api` is public preview. It is intended for early extension
> builders. Best-effort source compatibility is expected inside the same minor
> line, but binary compatibility is not stable yet and breaking changes may
> still ship with release notes.

Preview wording must not imply:

- stable binary compatibility
- runtime plugin-directory discovery
- marketplace installation
- runtime multi-engine support
- public engine adapter contracts

## Versioning Policy

While `experimental-local`:

- breaking changes may happen in minor releases
- docs and samples must be updated in the same change
- the staged publication proof must pass before merge

When `public-preview`:

- avoid breaking changes inside a minor line unless the release notes call them
  out clearly
- additive API changes may ship in minor releases
- bug fixes and documentation corrections may ship in patch releases
- every breaking change must include migration notes

When `stable`:

- `MAJOR` version changes may break public API
- `MINOR` version changes may add public API without breaking old extensions
- `PATCH` version changes must not change public API signatures or required
  extension behavior

Breaking changes include:

- removing a public type, method, enum value, or metadata key
- changing method signatures or return semantics
- changing required Spring registration metadata
- moving public packages or Maven coordinates
- making an optional extension capability mandatory

## Compatibility Gate

Before public preview, CI must prove:

- `verifyExtensionApiPublication` passes
- `verifyPublicPreviewExtensionApiPublication` passes with the planned
  `io.github.dtkmn:mcp-zap-extension-api` coordinate
- the staged POM has no runtime dependencies or dependency management
- the staged JAR contains only `mcp/server/zap/extension/api/**` plus manifest
  entries
- a standalone extension builds using only the API artifact and third-party
  dependencies
- a compatibility test loads the standalone extension JAR into the gateway and
  proves the expected beans wire through Spring Boot auto-configuration
- docs state the current stability level and repository path

Before stable, CI must also prove:

- the current gateway can load at least one extension compiled against the
  previous supported API version
- public API changes are reviewed against a generated API diff or equivalent
  compatibility report
- release notes include a dedicated extension API section
- migration notes exist for any breaking change

## Support Window

No support window exists while the API is `experimental-local`.

Recommended stable support window:

- support the latest minor line
- support one previous minor line for security and migration fixes
- document end-of-support when a new minor line becomes stable

That support window should only begin once compatibility tests can load an
extension compiled against an older published API.

## Release Checklist

Before publishing any public extension API artifact:

- confirm the artifact comes from the OSS-safe source tree
- confirm the Maven Central namespace and public coordinate are verified
- run `./gradlew verifyExtensionApiPublication`
- run `./gradlew verifyPublicPreviewExtensionApiPublication`
- confirm the standalone extension build resolves the API from the staged
  local and public-preview repositories, not Maven Central
- inspect the staged POM and JAR shape
- run the external-extension runtime wiring compatibility test
- generate the sources JAR, Javadoc JAR, POM metadata, and required signatures
- update `EXTENSION_API_COMPATIBILITY.md`
- update release notes with the API stability level
- confirm no enterprise package, private path, private workflow, or private
  namespace is included in the artifact or docs

## Founder Translation

The API artifact is the promise. The sample proves the promise can work. The
release policy says how expensive the promise becomes after someone depends on
it.

Until this policy graduates beyond `experimental-local`, talk about the
extension API as a proof and builder preview path, not as a stable platform.
