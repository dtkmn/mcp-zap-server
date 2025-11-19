# Security Mode Configuration Examples

Complete configuration examples for all three authentication modes.

## 📁 Configuration Files

### Development Mode (No Authentication)

**application-dev.yml**
```yaml
mcp:
  server:
    security:
      mode: none
      enabled: false

# All other ZAP settings remain the same
zap:
  server:
    url: localhost
    port: 8090
    apiKey: ${ZAP_API_KEY}
```

**docker-compose.override.yml** (for dev)
```yaml
version: '3.8'
services:
  mcp-zap-server:
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - MCP_SECURITY_MODE=none
```

**Usage:**
```bash
# No authentication needed
curl http://localhost:7456/mcp
```

---

### API Key Mode (Simple Authentication)

**application.yml** (or .env)
```yaml
mcp:
  server:
    security:
      mode: api-key
      enabled: true
    auth:
      apiKeys:
        - clientId: client-1
          key: ${MCP_API_KEY}
          description: Primary client
        - clientId: client-2
          key: ${MCP_API_KEY_2}
          description: Secondary client
```

**.env**
```bash
MCP_SECURITY_MODE=api-key
MCP_API_KEY=abc123def456ghi789jkl012mno345pq
MCP_API_KEY_2=xyz789uvw456rst123opq890lmn567ab
MCP_CLIENT_ID=client-1
```

**docker-compose.yml**
```yaml
version: '3.8'
services:
  mcp-zap-server:
    image: ghcr.io/dtkmn/mcp-zap-server:latest
    environment:
      - MCP_SECURITY_MODE=api-key
      - MCP_API_KEY=${MCP_API_KEY}
      - MCP_CLIENT_ID=${MCP_CLIENT_ID}
```

**Usage:**
```bash
# With X-API-Key header
curl -H "X-API-Key: abc123def456ghi789jkl012mno345pq" \
  http://localhost:7456/mcp
```

**Python Client:**
```python
import requests

headers = {"X-API-Key": "abc123def456ghi789jkl012mno345pq"}
response = requests.post("http://localhost:7456/mcp", headers=headers)
```

---

### JWT Mode (Production Authentication)

**application-prod.yml**
```yaml
mcp:
  server:
    security:
      mode: jwt
      enabled: true
    auth:
      apiKeys:
        - clientId: prod-client-1
          key: ${PROD_API_KEY_1}
          description: Production Client 1
        - clientId: prod-client-2
          key: ${PROD_API_KEY_2}
          description: Production Client 2
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
        issuer: mcp-zap-server-prod
        accessTokenExpiry: 3600      # 1 hour
        refreshTokenExpiry: 604800   # 7 days
```

**.env** (production)
```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-secure-256-bit-secret-minimum-32-characters-required-for-hs256-algorithm
JWT_ISSUER=mcp-zap-server-prod

# Initial API keys for token exchange
PROD_API_KEY_1=prod-abc123def456ghi789jkl012mno345pq
PROD_API_KEY_2=prod-xyz789uvw456rst123opq890lmn567ab

# JWT token expiry (seconds)
JWT_ACCESS_TOKEN_EXPIRATION=3600
JWT_REFRESH_TOKEN_EXPIRATION=604800
```

**docker-compose.yml** (production)
```yaml
version: '3.8'
services:
  mcp-zap-server:
    image: ghcr.io/dtkmn/mcp-zap-server:latest
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - MCP_SECURITY_MODE=jwt
      - JWT_ENABLED=true
      - JWT_SECRET=${JWT_SECRET}
      - PROD_API_KEY_1=${PROD_API_KEY_1}
      - PROD_API_KEY_2=${PROD_API_KEY_2}
    secrets:
      - jwt_secret
      - api_keys

secrets:
  jwt_secret:
    external: true
  api_keys:
    external: true
```

**Usage:**

1. **Get JWT Token:**
```bash
# Exchange API key for JWT
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "apiKey": "prod-abc123def456ghi789jkl012mno345pq",
    "clientId": "prod-client-1"
  }'
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

2. **Use Access Token:**
```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..." \
  http://localhost:7456/mcp
```

3. **Refresh Token (when expired):**
```bash
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }'
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
            json={"apiKey": self.api_key, "clientId": self.client_id}
        )
        data = response.json()
        self.access_token = data["accessToken"]
        self.refresh_token = data["refreshToken"]
        self.token_expiry = datetime.now() + timedelta(seconds=data["expiresIn"])
        return self.access_token
    
    def refresh(self):
        """Refresh access token"""
        response = requests.post(
            f"{self.base_url}/auth/refresh",
            json={"refreshToken": self.refresh_token}
        )
        data = response.json()
        self.access_token = data["accessToken"]
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
    api_key="prod-abc123def456ghi789jkl012mno345pq",
    client_id="prod-client-1"
)

# All requests automatically handle token refresh
response = client.request("/mcp")
```

---

## 🔄 Migration Path

### From `none` to `api-key`

1. Update configuration:
```yaml
# Before
mcp.server.security.mode: none

# After
mcp.server.security.mode: api-key
mcp.server.auth.apiKeys:
  - clientId: client-1
    key: ${MCP_API_KEY}
```

2. Update clients to include API key header
3. Test with both configurations before switching
4. Deploy and monitor logs

### From `api-key` to `jwt`

1. Add JWT configuration (keep API keys):
```yaml
mcp:
  server:
    security:
      mode: jwt
    auth:
      apiKeys:  # Keep existing keys for backward compatibility
        - clientId: client-1
          key: ${MCP_API_KEY}
      jwt:
        enabled: true
        secret: ${JWT_SECRET}
```

2. Update clients gradually to use JWT
3. Monitor which clients are still using API keys
4. Remove API key support after full migration

---

## 🔒 Secrets Management

### Using Docker Secrets

```yaml
version: '3.8'
services:
  mcp-zap-server:
    secrets:
      - jwt_secret
      - mcp_api_key

secrets:
  jwt_secret:
    file: ./secrets/jwt_secret.txt
  mcp_api_key:
    file: ./secrets/mcp_api_key.txt
```

### Using Environment from File

```bash
# secrets.env (gitignored)
JWT_SECRET=your-secret-here
MCP_API_KEY=your-key-here

# docker-compose.yml
services:
  mcp-zap-server:
    env_file:
      - secrets.env
```

### Using AWS Secrets Manager

```bash
# Fetch secrets at runtime
export JWT_SECRET=$(aws secretsmanager get-secret-value \
  --secret-id mcp-zap/jwt-secret \
  --query SecretString --output text)

docker-compose up -d
```

---

## 📊 Security Comparison

| Feature | None | API Key | JWT |
|---------|------|---------|-----|
| **Authentication** | ❌ None | ✅ Static Key | ✅ Dynamic Token |
| **Authorization** | ❌ | ⚠️ Basic | ✅ Granular |
| **Token Expiry** | N/A | Never | 1 hour |
| **Token Refresh** | N/A | N/A | 7 days |
| **Revocation** | N/A | Manual | Automatic |
| **Audit Trail** | ❌ | ⚠️ Limited | ✅ Full |
| **Client ID** | N/A | ⚠️ Optional | ✅ Required |
| **Setup Time** | 10s | 30s | 2 min |
| **Use Case** | Dev only | Internal | Production |

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

- [Security Modes Guide](SECURITY_MODES.md) - Detailed comparison
- [JWT Authentication](JWT_AUTHENTICATION.md) - JWT implementation
- [MCP Client Config](MCP_CLIENT_AUTHENTICATION.md) - Client setup
- [Quick Start](AUTHENTICATION_QUICK_START.md) - 60-second setup
