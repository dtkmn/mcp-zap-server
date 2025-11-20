---
layout: default
title: JWT Setup
parent: Security Modes
nav_order: 1
---

# JWT Authentication Guide

## Overview

The MCP ZAP Server supports JWT (JSON Web Token) authentication for enhanced security and token-based access control. This provides a modern, stateless authentication mechanism with token expiration and refresh capabilities.

## Features

- **Token-Based Authentication**: Exchange API keys for JWT tokens with configurable expiration
- **Access & Refresh Tokens**: Short-lived access tokens (1 hour default) with long-lived refresh tokens (7 days default)
- **Token Revocation**: Blacklist tokens before expiration for immediate access revocation
- **Backward Compatible**: Coexists with existing API key authentication
- **HS256 Signing**: Industry-standard HMAC-SHA256 algorithm for token signing

## Quick Start

### 1. Configure JWT Secret

Generate a secure 256-bit (32-byte) secret key:

```bash
openssl rand -base64 32
```

Add to your `.env` file:

```bash
JWT_ENABLED=true
JWT_SECRET=your-generated-secret-key-here
```

### 2. Obtain JWT Tokens

Exchange your API key for JWT tokens:

```bash
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "apiKey": "your-mcp-api-key",
    "clientId": "your-client-id"
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

### 3. Use Access Token

Include the access token in subsequent requests:

```bash
curl -X POST http://localhost:7456/zap/spider/start \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com",
    "maxDepth": 5
  }'
```

### 4. Refresh Token

When the access token expires, use the refresh token to get a new one:

```bash
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "YOUR_REFRESH_TOKEN"
  }'
```

## Configuration

### Environment Variables

Configure JWT authentication in your `.env` file:

```bash
# Enable JWT authentication
JWT_ENABLED=true

# JWT secret key (required, must be at least 256 bits / 32 bytes)
JWT_SECRET=your-jwt-secret-key-at-least-32-bytes

# JWT issuer identifier
JWT_ISSUER=mcp-zap-server

# Access token expiration in seconds (default: 3600 = 1 hour)
JWT_ACCESS_TOKEN_EXPIRATION=3600

# Refresh token expiration in seconds (default: 604800 = 7 days)
JWT_REFRESH_TOKEN_EXPIRATION=604800
```

### Application Configuration

The JWT settings are configured in `application.yml`:

```yaml
mcp:
  server:
    auth:
      jwt:
        enabled: ${JWT_ENABLED:true}
        secret: ${JWT_SECRET:}
        issuer: ${JWT_ISSUER:mcp-zap-server}
        accessTokenExpiry: ${JWT_ACCESS_TOKEN_EXPIRATION:3600}
        refreshTokenExpiry: ${JWT_REFRESH_TOKEN_EXPIRATION:604800}
```

## API Endpoints

### POST /auth/token

Exchange API key for JWT tokens.

**Request:**

```json
{
  "apiKey": "your-mcp-api-key",
  "clientId": "your-client-id"
}
```

**Response:**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### POST /auth/refresh

Refresh an expired access token using a valid refresh token.

**Request:**

```json
{
  "refreshToken": "YOUR_REFRESH_TOKEN"
}
```

**Response:**

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

### GET /auth/validate

Validate and inspect a JWT token (for debugging).

**Headers:**

```
Authorization: Bearer YOUR_ACCESS_TOKEN
```

**Response:**

```json
{
  "valid": true,
  "clientId": "your-client-id",
  "scopes": ["scan:read", "scan:write"],
  "issuer": "mcp-zap-server",
  "issuedAt": "2025-01-18T10:30:00Z",
  "expiresAt": "2025-01-18T11:30:00Z",
  "tokenType": "access"
}
```

## Token Structure

### Access Token Claims

```json
{
  "sub": "client-id",           // Subject (client identifier)
  "iss": "mcp-zap-server",      // Issuer
  "iat": 1705574400,            // Issued at (Unix timestamp)
  "exp": 1705578000,            // Expiration (Unix timestamp)
  "jti": "uuid-v4",             // JWT ID (for blacklisting)
  "scopes": ["scan:read"],      // Access scopes
  "type": "access"              // Token type
}
```

### Refresh Token Claims

```json
{
  "sub": "client-id",           // Subject (client identifier)
  "iss": "mcp-zap-server",      // Issuer
  "iat": 1705574400,            // Issued at (Unix timestamp)
  "exp": 1706179200,            // Expiration (Unix timestamp)
  "jti": "uuid-v4",             // JWT ID (for blacklisting)
  "type": "refresh"             // Token type
}
```

## Authentication Flow

### Initial Authentication

```
Client → [API Key] → Server
Server → [Access Token + Refresh Token] → Client
```

### Subsequent Requests

```
Client → [Access Token] → Server
Server → [Validates Token] → Response
```

### Token Refresh

```
Client → [Refresh Token] → Server
Server → [New Access Token] → Client
```

## Security Features

### Token Expiration

- **Access Tokens**: Short-lived (1 hour default) for active sessions
- **Refresh Tokens**: Long-lived (7 days default) for obtaining new access tokens
- Expired tokens are automatically rejected

### Token Blacklisting

Tokens can be revoked before expiration:

- Each token has a unique ID (jti claim)
- Blacklisted tokens are stored in-memory
- Cleanup automatically removes expired blacklist entries

### Token Validation

All JWT tokens are validated for:

- Valid signature (HS256)
- Not expired
- Correct issuer
- Not blacklisted
- Correct token type (access vs refresh)

## Backward Compatibility

JWT authentication works alongside existing API key authentication:

1. **JWT (Recommended)**: `Authorization: Bearer <token>`
2. **API Key**: `X-API-Key: <key>` or `Authorization: Bearer <api-key>`

Existing clients using API keys continue to work without changes.

## Best Practices

### Security

1. **Strong Secrets**: Use at least 256-bit (32-byte) random secrets
2. **HTTPS Only**: Always use HTTPS in production
3. **Short Expiration**: Keep access token expiration short (1 hour or less)
4. **Rotate Secrets**: Periodically rotate JWT secret keys
5. **Secure Storage**: Store tokens securely on client side

### Token Management

1. **Refresh Before Expiry**: Refresh access tokens before they expire
2. **Revoke on Logout**: Blacklist tokens when users log out
3. **Monitor Blacklist**: Implement cleanup for old blacklist entries
4. **Handle Errors**: Implement proper error handling for expired/invalid tokens

### Performance

1. **Cache Tokens**: Cache valid tokens to avoid repeated validation
2. **Batch Requests**: Use access tokens for multiple requests
3. **Cleanup**: Regularly clean up expired blacklist entries

## Troubleshooting

### "Invalid or expired JWT token"

- Check token expiration using `/auth/validate`
- Refresh the token using `/auth/refresh`
- Verify JWT_SECRET is correctly configured

### "JWT secret must be at least 256 bits"

- Ensure JWT_SECRET is at least 32 characters/bytes
- Generate a new secret: `openssl rand -base64 32`

### "Token has been revoked"

- Token was blacklisted
- Obtain a new token using API key via `/auth/token`

### "Invalid token type. Use access token"

- Using refresh token for API requests
- Use refresh token only with `/auth/refresh`
- Use access token for all other API endpoints

## Example Client Implementation

### Python

```python
import requests
import time

class MCPZapClient:
    def __init__(self, base_url, api_key, client_id):
        self.base_url = base_url
        self.api_key = api_key
        self.client_id = client_id
        self.access_token = None
        self.refresh_token = None
        self.token_expiry = 0
    
    def authenticate(self):
        """Exchange API key for JWT tokens"""
        response = requests.post(
            f"{self.base_url}/auth/token",
            json={
                "apiKey": self.api_key,
                "clientId": self.client_id
            }
        )
        response.raise_for_status()
        data = response.json()
        
        self.access_token = data["accessToken"]
        self.refresh_token = data["refreshToken"]
        self.token_expiry = time.time() + data["expiresIn"] - 60  # Refresh 1 min early
    
    def refresh_access_token(self):
        """Refresh expired access token"""
        response = requests.post(
            f"{self.base_url}/auth/refresh",
            json={"refreshToken": self.refresh_token}
        )
        response.raise_for_status()
        data = response.json()
        
        self.access_token = data["accessToken"]
        self.token_expiry = time.time() + data["expiresIn"] - 60
    
    def get_headers(self):
        """Get authorization headers with valid token"""
        if time.time() >= self.token_expiry:
            self.refresh_access_token()
        
        return {"Authorization": f"Bearer {self.access_token}"}
    
    def start_spider_scan(self, url, max_depth=5):
        """Start a spider scan"""
        response = requests.post(
            f"{self.base_url}/zap/spider/start",
            headers=self.get_headers(),
            json={"url": url, "maxDepth": max_depth}
        )
        response.raise_for_status()
        return response.json()

# Usage
client = MCPZapClient("http://localhost:7456", "your-api-key", "your-client-id")
client.authenticate()
result = client.start_spider_scan("https://example.com")
```

### JavaScript/Node.js

```javascript
const axios = require('axios');

class MCPZapClient {
  constructor(baseUrl, apiKey, clientId) {
    this.baseUrl = baseUrl;
    this.apiKey = apiKey;
    this.clientId = clientId;
    this.accessToken = null;
    this.refreshToken = null;
    this.tokenExpiry = 0;
  }

  async authenticate() {
    const response = await axios.post(`${this.baseUrl}/auth/token`, {
      apiKey: this.apiKey,
      clientId: this.clientId
    });
    
    this.accessToken = response.data.accessToken;
    this.refreshToken = response.data.refreshToken;
    this.tokenExpiry = Date.now() + (response.data.expiresIn * 1000) - 60000;
  }

  async refreshAccessToken() {
    const response = await axios.post(`${this.baseUrl}/auth/refresh`, {
      refreshToken: this.refreshToken
    });
    
    this.accessToken = response.data.accessToken;
    this.tokenExpiry = Date.now() + (response.data.expiresIn * 1000) - 60000;
  }

  async getHeaders() {
    if (Date.now() >= this.tokenExpiry) {
      await this.refreshAccessToken();
    }
    
    return { 'Authorization': `Bearer ${this.accessToken}` };
  }

  async startSpiderScan(url, maxDepth = 5) {
    const headers = await this.getHeaders();
    const response = await axios.post(
      `${this.baseUrl}/zap/spider/start`,
      { url, maxDepth },
      { headers }
    );
    
    return response.data;
  }
}

// Usage
const client = new MCPZapClient('http://localhost:7456', 'your-api-key', 'your-client-id');
await client.authenticate();
const result = await client.startSpiderScan('https://example.com');
```

## Migration from API Key Authentication

### Step 1: Update Configuration

Add JWT configuration to your `.env` file:

```bash
JWT_ENABLED=true
JWT_SECRET=$(openssl rand -base64 32)
```

### Step 2: Update Client Code

Modify your client to:
1. Exchange API key for JWT tokens on startup
2. Use access token in Authorization header
3. Implement token refresh logic

### Step 3: Test Both Methods

- Keep API key authentication working during migration
- Test JWT authentication thoroughly
- Gradually migrate clients to JWT

### Step 4: Monitor and Adjust

- Monitor token expiration patterns
- Adjust expiration times based on usage
- Consider disabling API key auth once migration is complete

## Additional Resources

- [JWT.io](https://jwt.io/) - JWT token inspector and debugger
- [JJWT Documentation](https://github.com/jwtk/jjwt) - Java JWT library
- [Spring Security](https://spring.io/projects/spring-security) - Security framework

## Support

For issues or questions:
1. Check the [troubleshooting section](#troubleshooting)
2. Review server logs for detailed error messages
3. Use `/auth/validate` endpoint for token debugging
4. Open an issue on GitHub with relevant logs
