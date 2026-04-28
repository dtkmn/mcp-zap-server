# Release Notes - Version 0.6.1

**Release Date:** April 28, 2026

## Highlights

- Fixed a streamable HTTP MCP transport crash that made `tools/list`, `prompts/list`, and `resources/list` return `500 Internal Server Error` after a successful `initialize` handshake.
- Restored Streamable HTTP event-stream responses for tool, prompt, and resource list requests after a successful MCP handshake.
- Added regression coverage for the `initialize` -> `tools/list` flow so this bug does not quietly return.
- Includes post-`v0.6.0` maintenance updates for CI, release automation, CodeQL, docs, community templates, Docker/Compose defaults, and patch-level dependencies.

## Fixed

- Patched the WebFlux streamable transport shim so it no longer calls `ServerSentEvent.Builder#id` with a null message id under Spring Framework 7.

## Maintenance and Documentation

- Upgraded Spring Boot, Gradle, AspectJ, CycloneDX, Astro, and Starlight patch-level dependencies.
- Improved CI, release, Pages, dependency-submission, branch-image publishing, and multi-platform release image workflows.
- Added CodeQL analysis for Java and JavaScript.
- Added contribution guidelines, pull request template, issue templates, CODEOWNERS, and Contributor Covenant Code of Conduct.
- Refined README usage guidance, vulnerability reporting instructions, security policy docs, Docker/Compose defaults, and documented ZAP addon configuration.

## Upgrade Notes

This is a focused hotfix for `v0.6.0`. No configuration changes are required.

If you are running Docker images tagged `v0.6.0` or `latest` from before this release and your MCP client can initialize but fails on `tools/list`, upgrade to `v0.6.1` immediately.
