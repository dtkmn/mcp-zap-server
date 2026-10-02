---
title: "Security Modes"
editUrl: false
description: "The MCP ZAP Server supports three authentication modes to balance security and ease of use for different deployment scenarios."
---
The MCP ZAP Server supports three authentication modes to balance security and ease of use for different deployment scenarios.

For shipped HTTP/server defaults, the base runtime uses `api-key`. `none` is reserved for explicit dev/test overrides.

Authentication answers who is calling. Authorization still answers which MCP tools that caller may use. Do not confuse the two.

## 🔐 Authentication Modes

### 1. No Authentication (`none`)

**⚠️ WARNING: Use ONLY for development/testing. NOT recommended for production.**

- **Use Case**: Local development, testing, debugging
- **Security**: Authentication and per-tool scope checks are disabled
- **Setup**: Minimal configuration required

This does not enable tools outside the selected surface or make every request
valid. In **v0.11.1 and later**, adapter tool-availability and request-validation
checks remain active in `none` mode. The
[unknown/disabled-tool error contract](../getting-started/tool-scope-authorization/#unknown-and-disabled-tools)
is not included in v0.11.0; see that section for version requirements.

**Configuration:**
```yaml
# application.yml
mcp:
  server:
    security:
      mode: none
```

**Environment Variable:**
```bash
export MCP_SECURITY_MODE=none
```

**When to Use:**
- Local development on trusted networks
- Integration testing
- Debugging connectivity issues
- Proof of concept demos

**CSRF Protection:**
- ✅ Disabled for MCP protocol compatibility (MCP endpoints don't use CSRF tokens)

**Risks:**
- ❌ No authentication or authorization
- ❌ Anyone with network access can use the service
- ❌ Exposes ZAP instance to unauthorized control

---

### 2. API Key Authentication (`api-key`)

**✅ Recommended for: Trusted environments, simple deployments**
**Default for the base HTTP/server runtime.**

- **Use Case**: Single-tenant deployments, internal networks, docker-compose setups
- **Security**: Simple bearer token authentication
- **Setup**: Configure API key in environment

**Configuration:**
```yaml
# application.yml
mcp:
  server:
    security:
      mode: api-key
    auth:
      apiKeys:
        - clientId: my-client
          key: your-secure-api-key-here
          description: My MCP Client
```

**Environment Variables:**
```bash
export MCP_SECURITY_MODE=api-key
export MCP_API_KEY=your-secure-api-key-here
export MCP_CLIENT_ID=my-client
```

**Usage:**
```bash
# cURL example
curl -H "X-API-Key: your-secure-api-key-here" \
  http://localhost:7456/mcp

# Python example
import requests
headers = {"X-API-Key": "your-secure-api-key-here"}
response = requests.post("http://localhost:7456/mcp", headers=headers)
```

**When to Use:**
- ✅ Docker Compose deployments
- ✅ Internal network access only
- ✅ Single-tenant applications
- ✅ Simple authentication requirements
- ✅ When you want minimal client configuration

**CSRF Protection:**
- ✅ Disabled by design for stateless API authentication (no cookie sessions)

**Advantages:**
- Simple to configure
- No token expiration
- Easy to share with trusted clients
- Minimal overhead

**Limitations:**
- Keys don't expire (manual rotation required)
- Scope changes require client configuration updates and redeploy/restart
- Single authentication factor
- Keys can be intercepted if not using HTTPS

---

### 3. JWT Authentication (`jwt`)

**✅ Recommended for: Clients needing token expiration, refresh, and revocation**

- **Use Case**: Authenticated clients within one trust boundary who need expiring tokens
- **Security**: Token-based authentication with expiration
- **Setup**: Exchange API key for JWT tokens

JWT authenticates callers; it does not isolate tenants, scan results, or other
shared ZAP state. Use a dedicated MCP ZAP deployment and ZAP instance for each
trust boundary. See the [Production Readiness Checklist](../operations/production-checklist/)
and [Multi-Replica Deployment Requirements](../operations/local-ha-compose/).

**Configuration:**
```yaml
# application.yml
mcp:
  server:
    security:
      mode: jwt
      enabled: true
    auth:
      apiKeys:
        - clientId: client-1
          key: api-key-for-client-1
          description: Production Client 1
      jwt:
        enabled: true
        secret: your-256-bit-secret-key-minimum-32-chars
        issuer: mcp-zap-server
        accessTokenExpiry: 3600      # 1 hour
        refreshTokenExpiry: 604800   # 7 days
```

**Environment Variables:**
```bash
export MCP_SECURITY_MODE=jwt
export MCP_SECURITY_ENABLED=true
export JWT_ENABLED=true
export JWT_SECRET=your-256-bit-secret-key-minimum-32-chars-required
export MCP_API_KEY=your-initial-api-key
```

**Usage Workflow:**

1. **Get Access Token** (exchange API key for JWT):
```bash
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey": "your-api-key", "clientId": "client-1"}'
```

Response:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

2. **Use Access Token**:
```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  http://localhost:7456/mcp
```

3. **Refresh Token** (one-time use; server returns a rotated refresh token):
```bash
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."}'
```

**When to Use:**
- ✅ Production environments
- ✅ Cloud deployments (AWS, Azure, GCP)
- ✅ Multiple clients within one trust boundary
- ✅ Public or semi-public access
- ✅ Compliance requirements (audit trails)
- ✅ Fine-grained access control needed

**CSRF Protection:**
- ✅ Disabled by design for stateless API authentication (no cookie sessions)

**Advantages:**
- Tokens expire automatically (access: 1hr, refresh: 7 days)
- Token revocation support (blacklist)
- Client identification (clientId in token)
- One-time refresh-token rotation with replay rejection
- Industry-standard (RFC 7519)
- Backward compatible (still accepts API keys)

**Limitations:**
- More complex client configuration
- Requires token management (refresh, storage)
- Higher overhead (token validation)

## Authorization Still Applies

Both API-key and JWT clients can be constrained with per-tool scopes.

Useful follow-on docs:

- [Tool Scope Authorization](../getting-started/tool-scope-authorization/)
- [MCP Client Authentication](../getting-started/mcp-client-authentication/)
- [Tool Surfaces](../getting-started/tool-surfaces/)

---

## 🔄 Migration Path

### Development migration: registered API keys

**Unreleased change; not included in `v0.13.0`.** Every API key must match an
entry in `mcp.server.auth.apiKeys`. The legacy `mcp.server.apiKey` fallback is
removed. A nonblank value for that property, including relaxed spellings such as
`mcp.server.api-key` or `MCP_SERVER_API_KEY`, fails startup with a migration
message even when security is disabled or the mode is `none`. Remove it from
application configuration, environment variables, and command-line overrides.

`MCP_API_KEY` remains the shortcut for the packaged `default-client` entry
(`MCP_CLIENT_ID` can change its client ID). Overriding `auth.apiKeys` replaces that
list; it does not add a fallback client for `MCP_API_KEY`. Deployments using the
removed property must register their key explicitly, for example:

```yaml
mcp:
  server:
    security:
      mode: api-key
    auth:
      apiKeys:
        - clientId: legacy-client
          key: ${MCP_API_KEY}
          scopes:
            - mcp:tools:list
            - zap:report:read
          workspaceId: legacy-client
```

Choose the client's required [tool scopes](../getting-started/tool-scope-authorization/).
The removed fallback identified callers as `legacy-client`; its workspace
defaulted to that client ID. Preserve those identities with explicit `clientId`
and `workspaceId` values if existing policies or records depend on them. A new
client ID changes the effective workspace when `workspaceId` is omitted.

The packaged application and Compose defaults no longer supply a placeholder
key. API-key and JWT modes fail startup without a configured key.
`allowPlaceholderApiKey` defaults to `false`; recognized placeholders require an
explicit opt-in and at least one active Spring profile, with every active profile
among `local`, `dev`, and `test`. Production or mixed profile sets reject them.
JWT mode continues to accept registered API keys alongside JWT tokens.

### From `none` to `api-key`:
```yaml
# Before (development)
mcp:
  server:
    security:
      mode: none

# After (staging)
mcp:
  server:
    security:
      mode: api-key
    auth:
      apiKeys:
        - clientId: staging-client
          key: generate-secure-random-key-here
```

Update clients to include API key header.

### From `api-key` to `jwt`:
```yaml
# Before
mcp:
  server:
    security:
      mode: api-key

# After
mcp:
  server:
    security:
      mode: jwt
      enabled: true
    auth:
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
```

**Backward Compatible**: JWT mode still accepts API keys, so clients can migrate gradually.

---

## 🛡️ Security Best Practices

### For All Modes:
1. **Always use HTTPS** in production (TLS/SSL)
2. **Rotate credentials** regularly
3. **Use strong, random keys** (minimum 32 characters)
4. **Limit network access** (firewall, VPC, security groups)
5. **Monitor authentication logs**
6. **Never commit secrets** to version control

### API Key Mode:
```bash
# Generate secure API key
openssl rand -base64 32
```

### JWT Mode:
```bash
# Generate JWT secret (256-bit minimum)
openssl rand -base64 64

# Store in secrets manager (AWS Secrets Manager, Azure Key Vault, etc.)
```

### Docker Deployment:
Use the [Local Compose Environment File example](examples/#local-compose-environment-file)
for the supplied Compose stack. JWT requires `MCP_SECURITY_MODE=jwt`,
`MCP_SECURITY_ENABLED=true`, and `JWT_ENABLED=true`, along with a generated
signing secret and the configured MCP API key.

Mounting Docker secret files alone does not configure authentication. Custom
deployments must [map those values into the application's Spring configuration](examples/#kubernetes-secrets).

---

## 🎯 Quick Decision Guide

| Scenario | Recommended Mode | Why |
|----------|-----------------|-----|
| Local development | `none` | No authentication overhead |
| Docker Compose (internal) | `api-key` | Simple, sufficient for trusted networks |
| Kubernetes (internal) | `api-key` or `jwt` | Depends on security requirements |
| Cloud deployment (public) | `jwt` | Token expiration, better security |
| Multi-tenant SaaS | `api-key` or `jwt` in a dedicated MCP/ZAP deployment per trust boundary | Authentication does not isolate shared ZAP state |
| CI/CD pipeline | `api-key` | Simple automation |
| Production (exposed) | `jwt` | Industry best practices |

---

## 📋 Configuration Examples

### Development Setup:
```yaml
# application-dev.yml
mcp:
  server:
    security:
      mode: none  # No auth for local dev
```

### Staging Setup:
```yaml
# application-staging.yml
mcp:
  server:
    security:
      mode: api-key
    auth:
      apiKeys:
        - clientId: staging-tester
          key: ${STAGING_API_KEY}
```

### Production Setup:
```yaml
# application-prod.yml
mcp:
  server:
    security:
      mode: jwt
      enabled: true
    auth:
      apiKeys:
        - clientId: prod-client-1
          key: ${PROD_API_KEY_1}
        - clientId: prod-client-2
          key: ${PROD_API_KEY_2}
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
        accessTokenExpiry: 3600
        refreshTokenExpiry: 604800
```

---

## 🔧 Troubleshooting

### "Security is disabled" warning in logs
- **Cause**: `mode: none` or `security.enabled: false`
- **Solution**: Change to `api-key` or `jwt` mode for production

### "Invalid API key" errors
- **Check**: API key matches configuration
- **Verify**: `X-API-Key` header is present
- **Confirm**: Client ID and key match in `apiKeys` list

### "Invalid or expired JWT token"
- **Check**: Token hasn't expired (default: 1 hour)
- **Solution**: Refresh token using `/auth/refresh`
- **Verify**: JWT secret matches between token generation and validation

### "Token has been revoked"
- **Cause**: Token was explicitly blacklisted
- **Solution**: Get new access token via `/auth/token`

---

## 📚 Related Documentation

- [JWT Authentication Guide](jwt-authentication/)
- [JWT Key Rotation Runbook](jwt-key-rotation-runbook/)
- [MCP Client Configuration](../getting-started/mcp-client-authentication/)
- [Quick Start Guide](../getting-started/jwt-quick-start/)
- [Security Review](../reference/security-policy/)

---

## 🆘 Support

For security concerns or questions:
- Review: [SECURITY.md](../reference/security-policy/)
- Issues: GitHub Issues
- Docs: [docs/](../)
