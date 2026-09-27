---
title: "Security Mode Configuration Examples"
editUrl: false
description: "Configure authentication modes, manage JWT tokens, and supply runtime secrets."
---
Configure authentication for an existing MCP ZAP deployment and manage its JWT tokens.

Use documentation and deployment files from the same checkout or release. Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and the
corresponding release workflow for image availability before deploying.

## 📁 Configure The Supplied Compose Stack

These examples extend the repository's `mcp-server` service. Start with the
[self-serve setup](../../getting-started/self-serve-first-run/) to generate
credentials and prepare ZAP. Run commands from the repository root and replace
credential placeholders with your generated values. Keep JWT disabled unless
using it, and never use example strings as real credentials.

The commands below include the development override used by `./dev.sh`. Omit
`-f docker-compose.dev.yml` if your stack was started with only the base file.
For a local JVM process, export the same settings or use Spring configuration;
the application does not automatically load `.env`.

### Development Mode (No Authentication)

For an isolated authentication-free test, save this temporary override as
`docker-compose.auth.yml`:

```yaml
services:
  mcp-server:
    environment:
      MCP_SECURITY_MODE: none
      MCP_SECURITY_ENABLED: "false"
      JWT_ENABLED: "false"
```

Apply it explicitly:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml -f docker-compose.auth.yml up -d --force-recreate mcp-server
```

Keep the stack bound to loopback. When returning to an authenticated mode, omit
this override and recreate the container using the next example; otherwise its
`MCP_SECURITY_ENABLED=false` setting still disables authentication.

### API Key Mode

Set these values in `.env`, retaining the generated ZAP key and workspace
configuration from bootstrap:

```bash
MCP_SECURITY_MODE=api-key
MCP_SECURITY_ENABLED=true
MCP_API_KEY=your-generated-mcp-api-key
MCP_CLIENT_ID=default-client
JWT_ENABLED=false
```

Recreate the MCP service without the no-auth override:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate mcp-server
./bin/self-serve-doctor.sh
```

Configure the client to send:

```http
X-API-Key: your-generated-mcp-api-key
```

The default configuration defines one client. For multiple clients and explicit
permissions, configure the `mcp.server.auth.apiKeys` list in deployment-supplied
Spring configuration; see [Tool Scope Authorization](../../getting-started/tool-scope-authorization/).
Adding an arbitrary variable such as `MCP_API_KEY_2` alone does not register a
second client.

### JWT Mode

Keep the existing MCP API key for token issuance. Generate a signing secret
with `openssl rand -base64 32`, then update `.env`:

```bash
MCP_SECURITY_MODE=jwt
MCP_SECURITY_ENABLED=true
MCP_API_KEY=your-generated-mcp-api-key
MCP_CLIENT_ID=default-client
JWT_ENABLED=true
JWT_SECRET=replace-with-generated-secret
JWT_ISSUER=mcp-zap-server
JWT_ACCESS_TOKEN_EXPIRATION=3600
JWT_REFRESH_TOKEN_EXPIRATION=604800
```

Apply the settings:

```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate mcp-server
```

Exchange the configured API key for a token pair:

```bash
curl -fsS -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-generated-mcp-api-key","clientId":"default-client"}'
```

The response contains `accessToken`, `refreshToken`, `tokenType`, `expiresIn`,
`clientId`, and `scopes`. Validate the returned access token:

```bash
curl -fsS -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  http://localhost:7456/auth/validate
```

Expect `valid: true`. To call MCP tools, follow the
[JWT initialization and session example](../../getting-started/jwt-quick-start/#step-5-initialize-the-mcp-session).
A bare request to `/mcp` does not perform the protocol handshake.

When the access token expires, exchange the current refresh token:

```bash
curl -fsS -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"YOUR_CURRENT_REFRESH_TOKEN"}'
```

**Python Client (JWT):**
```python
import requests
from datetime import datetime, timedelta

class JWTAuthClient:
    def __init__(self, base_url, api_key, client_id):
        self.base_url = base_url
        self.api_key = api_key
        self.client_id = client_id
        self.access_token = None
        self.refresh_token = None
        self.token_expiry = None
    
    def get_token(self):
        """Exchange API key for JWT tokens"""
        response = requests.post(
            f"{self.base_url}/auth/token",
            json={"apiKey": self.api_key, "clientId": self.client_id},
            timeout=30
        )
        response.raise_for_status()
        data = response.json()
        self.access_token = data["accessToken"]
        self.refresh_token = data["refreshToken"]
        self.token_expiry = datetime.now() + timedelta(seconds=data["expiresIn"])
        return self.access_token
    
    def refresh(self):
        """Refresh access token"""
        response = requests.post(
            f"{self.base_url}/auth/refresh",
            json={"refreshToken": self.refresh_token},
            timeout=30
        )
        response.raise_for_status()
        data = response.json()
        self.access_token = data["accessToken"]
        self.refresh_token = data["refreshToken"]
        self.token_expiry = datetime.now() + timedelta(seconds=data["expiresIn"])
        return self.access_token
    
    def get_valid_token(self):
        """Get a valid access token (refresh if expired)"""
        if not self.access_token or datetime.now() >= self.token_expiry:
            if self.refresh_token:
                return self.refresh()
            else:
                return self.get_token()
        return self.access_token
    
    def request(self, endpoint, **kwargs):
        """Make authenticated request"""
        headers = kwargs.pop('headers', {})
        headers['Authorization'] = f'Bearer {self.get_valid_token()}'
        return requests.post(f"{self.base_url}{endpoint}", headers=headers, **kwargs)

# Usage
client = JWTAuthClient(
    base_url="http://localhost:7456",
    api_key="your-generated-mcp-api-key",
    client_id="default-client"
)

# Obtain a token for an MCP client that handles initialization and sessions.
access_token = client.get_valid_token()
```

Refresh tokens are single-use: each successful refresh replaces both tokens.
If refresh fails, obtain a new token pair with the API key. Concurrent callers
must serialize refresh operations to avoid consuming the same refresh token
twice. The client above is a minimal sequential example.

---

## 🔄 Migration Path

### From `none` to `api-key`

1. Update the deployed Spring configuration:

```yaml
mcp.server.security.mode: api-key
mcp.server.security.enabled: true
mcp.server.auth.apiKeys:
  - clientId: client-1
    key: ${MCP_API_KEY}
```

2. Remove any no-auth Compose override and set `MCP_SECURITY_ENABLED=true`.
3. Recreate the MCP service and update clients to include `X-API-Key`.
4. Verify requests without credentials fail and configured clients can initialize
   an MCP session.

### From `api-key` to `jwt`

1. Add JWT configuration (keep API keys):

```yaml
mcp:
  server:
    security:
      mode: jwt
      enabled: true
    auth:
      apiKeys:  # Keep existing keys for backward compatibility
        - clientId: client-1
          key: ${MCP_API_KEY}
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
```

2. Recreate the service with `MCP_SECURITY_ENABLED=true`, then update clients
   gradually to use JWT
3. Monitor which clients are still using API keys
4. Retain the configured client API keys for token issuance and the client entries
   needed by refresh validation. JWT mode continues to accept API-key
   authentication; there is no separate JWT-only mode switch.

---

## 🔒 Secrets Management

### Local Compose Environment File

For an existing local setup, copy the bootstrap-generated `.env` to an ignored
alternate environment file. This preserves its workspace path and demo-target
settings:

```bash
cp .env .env.local
```

Keep generated MCP and ZAP keys in `.env.local`. For JWT mode, also set
`MCP_SECURITY_MODE=jwt`, `MCP_SECURITY_ENABLED=true`, `JWT_ENABLED=true`, and a
generated `JWT_SECRET` of at least 32 bytes. Then pass the file explicitly to
Compose:

```bash
docker compose --env-file .env.local -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate mcp-server
```

The supplied `docker-compose.yml` forwards those values to the MCP service.
Keep this file private; `.env.local` is ignored by the repository. Do not put
secret values in a tracked example or commit them in another filename.

### Kubernetes Secrets

The [Helm guide](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#prepare-credentials)
shows how to use existing Kubernetes Secrets for MCP, ZAP, and JWT credentials.
The chart maps those Secret keys into the container environment.

Mounting Docker secret files alone does not configure authentication. A custom
deployment must explicitly map the mounted values to the application's Spring
configuration; there is no automatic `JWT_SECRET_FILE` or `MCP_API_KEY_FILE`
handling in the supplied Compose stack.

### External Secret Managers

Have your deployment system inject resolved secrets into the documented
application environment or Kubernetes Secret references. For a local shell
using AWS Secrets Manager, the JWT secret value can be supplied as follows:

```bash
export JWT_SECRET="$(aws secretsmanager get-secret-value \
  --secret-id mcp-zap/jwt-secret \
  --query SecretString --output text)"
export JWT_ENABLED=true
export MCP_SECURITY_MODE=jwt
export MCP_SECURITY_ENABLED=true
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --force-recreate mcp-server
```

This example assumes the secret's value is the signing string itself, not a
JSON object, and that MCP/ZAP API keys are already configured. The default
Compose stack still needs a complete private environment configuration.

---

## 📊 Security Comparison

| Feature | None | API Key | JWT |
|---------|------|---------|-----|
| Credential | None | Configured API key | Bearer access token; API-key fallback remains available |
| Token expiry | N/A | No token expiry | Configurable; access-token default is 1 hour |
| Refresh | N/A | N/A | Single-use rotating refresh token; default lifetime is 7 days |
| Credential revocation | N/A | Replace or remove the configured key | Token revocation store and signing-key rotation |
| Intended use | Isolated development/testing | Authenticated clients with managed key distribution | Authenticated clients needing token expiry, refresh, and revocation |

Tool scopes and audit configuration are separate from the authentication mode.
API-key and JWT clients both use the configured client identity and scope
policy; JWT alone does not enable authorization or an audit sink.

---

## 🎯 Best Practices

1. **Always use HTTPS in production**
2. **Rotate secrets regularly** (90 days recommended)
3. **Use secrets managers** (not .env files in production)
4. **Monitor authentication logs**
5. **Implement rate limiting** at reverse proxy level
6. **Enable audit logging** for JWT mode
7. **Use different secrets** per environment (dev/staging/prod)

---

## 📚 Related Documentation

- [Security Modes Guide](../) - Detailed comparison
- [JWT Authentication](../jwt-authentication/) - JWT implementation
- [MCP Client Config](../../getting-started/mcp-client-authentication/) - Client setup
- [MCP Access Authentication](../../getting-started/authentication-quick-start/) - Mode selection
