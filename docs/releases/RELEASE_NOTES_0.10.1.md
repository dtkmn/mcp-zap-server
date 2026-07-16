# Release Notes - Version 0.10.1

**Release Date:** July 16, 2026

## Highlights

- Restored reliable Streamable HTTP connections for Cursor and other MCP clients that answer server-initiated JSON-RPC requests.
- Updated `mcp-gateway-core` and `mcp-gateway-spring-webflux` from `0.7.1` to `0.7.2`.
- Kept the normal 30-second MCP keepalive enabled and verified successful Cursor responses with HTTP `202`.
- Preserved the `v0.10.0` tool schemas, authentication settings, Helm values, and database contract.

## Fixed

### Streamable HTTP client responses

The Streamable HTTP MCP endpoint accepts requests, notifications, and responses on the same `POST` route. The previous gateway adapter treated every body as a request and rejected client responses because they correctly contain an `id` and `result` or `error`, but no `method`.

Gateway adapter `0.7.2` distinguishes response-shaped envelopes from requests and replays them to Spring AI for session correlation. Responses to server-initiated keepalive pings now complete with HTTP `202` instead of producing `invalid_mcp_request` errors followed by keepalive timeouts. Requests that carry a `method` remain subject to the existing authorization and abuse-protection path.

The corrected path preserves the original body, `Mcp-Session-Id`, custom headers, body-size limits, and the surrounding authentication filter chain.

## Continuous Security Analysis

CodeQL now covers Java, JavaScript/TypeScript, Python, and GitHub Actions. Java analysis uses the repository's explicit application and standalone-extension build paths, and branch triggers are focused on pull requests and pushes targeting `main`.

## Maintenance

Policy dry-run regression coverage now explicitly verifies the existing required-description rule, and partial-rule construction is aligned with that validation. The externally visible policy-validation contract is unchanged.

## Upgrade Notes

- This is a drop-in patch from `v0.10.0`; no database migration or Helm values change is required.
- Pull the `v0.10.1` image, restart MCP ZAP Server, and reconnect clients so they establish a fresh MCP session.
- Remove any temporary keepalive-disablement or long-interval override used to work around the `v0.10.0` behavior. The supported default remains 30 seconds.
- API-key and JWT access authentication continue to work unchanged. Optional target form-login profiles are also unchanged.

## Runtime Versions

- Spring Boot `4.1.0`
- Spring AI `2.0.0`
- `mcp-gateway-core` and `mcp-gateway-spring-webflux` `0.7.2`
- Gradle `9.6.1`
- Testcontainers `2.0.5`

## Extension API

`mcp-zap-extension-api` remains `experimental-local`. The locally staged proof now uses version `0.10.1`; no extension API contract changed in this release.

## Diff

- https://github.com/dtkmn/mcp-zap-server/compare/v0.10.0...v0.10.1
