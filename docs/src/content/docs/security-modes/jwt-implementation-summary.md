---
title: "JWT & Security Implementation Summary (`v0.6.0`)"
editUrl: false
description: "This document summarizes the current authentication and security implementation used by MCP ZAP Server as of v0.6.0."
---
## Overview

This document summarizes the current authentication and security implementation used by MCP ZAP Server as of `v0.6.0`.

## Current Security Model

- Authentication modes: `none`, `api-key`, `jwt`
- HTTP security is implemented in `SecurityConfig.java`
- JWT and API key authentication both use header-based credentials
- JWT mode supports API key fallback for gradual client migration
- CSRF protection is intentionally disabled for this API-only, token-based server

## JWT Architecture

### Token Flow

1. Client exchanges API key at `POST /auth/token`
2. Server returns access token + refresh token
3. Client calls protected APIs with `Authorization: Bearer <access-token>`
4. Client refreshes at `POST /auth/refresh` when access token expires

### Validation

Incoming JWTs are validated for:

- Signature integrity
- Expiration
- Issuer
- Token type (`access` vs `refresh`)
- Blacklist status (revoked token detection)

## Runtime Configuration

Configured via environment variables and `application.yml`:

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=<at-least-32-bytes>
JWT_ISSUER=mcp-zap-server
JWT_ACCESS_TOKEN_EXPIRATION=3600
JWT_REFRESH_TOKEN_EXPIRATION=604800
```

## Key Implementation Components

- `src/main/java/mcp/server/zap/core/configuration/SecurityConfig.java`
- `src/main/java/mcp/server/zap/core/controller/AuthController.java`
- `src/main/java/mcp/server/zap/core/service/JwtService.java`
- `src/main/java/mcp/server/zap/core/service/TokenBlacklistService.java`
- `src/main/java/mcp/server/zap/core/service/revocation/TokenRevocationStore.java`
- `src/main/java/mcp/server/zap/core/configuration/JwtProperties.java`
- `src/main/java/mcp/server/zap/core/configuration/ApiKeyProperties.java`

## Notes for Operators
- Use API key mode for simpler trusted-network deployments; use JWT mode for production-grade token lifecycle

## Verification

Run:

```bash
./gradlew test
./gradlew build
```

## Related Docs

- `/security-modes/`
- `/security-modes/jwt-authentication/`
- `/getting-started/mcp-client-authentication/`
- `SECURITY.md`
- `CHANGELOG.md`
