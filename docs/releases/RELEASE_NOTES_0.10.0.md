# Release Notes - Version 0.10.0

**Release Date:** July 15, 2026

## Highlights

- Reworked guided target authentication around operator-managed profiles that bind credentials and login settings to one approved HTTP(S) origin.
- Upgraded Spring Boot from `4.0.7` to `4.1.0` and gateway-core plus its WebFlux adapter from `0.5.10` to `0.7.1`.
- Migrated from Testcontainers `1.20.6` to Boot-managed Testcontainers `2.0.5` modules and added a dedicated Docker-backed test task.
- Hardened the JVM runtime image to run as non-root UID/GID `1000`.
- Added a first-timer Compose and Cursor guide for optional form-login target authentication.
- Limited continuous image publication to successful `main` pushes.

Spring AI remains at `2.0.0`; it was already included in `v0.9.1` and is now verified with Spring Boot `4.1.0`.

## Upgrade Notes

### Guided target authentication

This release changes the input contract of `zap_auth_session_prepare`:

```text
Before: callers supplied the auth kind, credential reference or secret, and login configuration
Now:    callers supply only profileId and targetUrl
```

Replace these removed deployment settings:

```text
MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES
MCP_AUTH_BOOTSTRAP_ALLOW_INLINE_SECRETS
```

with operator-managed `mcp.server.auth.bootstrap.profiles` configuration. Profiles default to an empty list, so deployments that do not use target authentication need no profile configuration.

Credentials must use an exact `env:NAME` or `file:/absolute/path` reference owned by the operator. Inline secrets and caller-selected credential or login settings are no longer supported. Restart MCP ZAP Server after changing a profile or secret, reconnect clients that cached the old tool schema, then prepare and validate a new session. Continue only when validation reports all three lines:

```text
Valid: true
Outcome: authenticated
likelyAuthenticated=true
```

### Container identity

The JVM image now runs as UID/GID `1000`. Existing bind mounts and mounted secrets must grant that identity the minimum required read or write access. The standard shared `/zap/wrk` setup remains aligned with the ZAP container user.

### Compatibility

- No database migration is required.
- Guided and expert tool names are unchanged; the material tool-schema break is limited to `zap_auth_session_prepare` arguments.
- Normal unauthenticated scans and MCP access authentication through API keys or JWT remain supported.
- Guided authenticated crawl and active scan currently apply only simple form-login sessions and use the HTTP spider. Target bearer/API-key profiles are not automatically injected into guided scans. OAuth, SSO, MFA, CAPTCHA, multi-step, and browser-only login flows remain unsupported by this guided path.

## Changed

- Updated Spring Boot to `4.1.0`, Gradle to `9.6.1`, and gateway-core plus `mcp-gateway-spring-webflux` to `0.7.1`.
- Retained Spring AI `2.0.0` and aligned dependency-resolution checks with the Boot `4.1.0` runtime.
- Adopted the consolidated gateway-core WebFlux governance filter while retaining the server-owned ZAP tool-to-scope catalog.
- Updated the docs stack to Astro `7.0.9`, Starlight `0.41.3`, and Sharp `0.35.3`.
- Updated release, Helm, MCP Registry, extension proof, and installation metadata to `0.10.0` / `v0.10.0`.
- Made the release workflow reject a tag that does not match the Gradle project version.

## Security

- Removed caller-selected target credentials and login configuration in favor of operator-owned profiles.
- Bound each credential, login URL, target URL, ZAP context, and direct or queued authenticated dispatch to one canonical approved origin.
- Made profile contexts unique and origin-wide, validated login indicator regexes before secret resolution or engine mutation, and sanitized caller-visible credential errors.
- Made form-login validation fail closed unless ZAP reports `likelyAuthenticated=true`.
- Rejected terminal-dot hosts so URL policy and ZAP cannot resolve different destination identities.
- Moved the JVM runtime image to a non-root user.

## Extension API

`mcp-zap-extension-api` remains `experimental-local`. The locally staged proof now uses version `0.10.0`, but the artifact is not published to a public repository and carries no third-party binary compatibility promise. No extension API contract changed in this release.

## Diff

- https://github.com/dtkmn/mcp-zap-server/compare/v0.9.1...v0.10.0
