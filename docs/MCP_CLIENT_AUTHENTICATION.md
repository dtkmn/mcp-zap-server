# MCP Client Authentication Configuration

## Overview

When your MCP server requires authentication (API key or JWT), MCP clients need to be configured to include the appropriate authentication headers in every request. This guide covers how to configure popular MCP clients.

## Authentication Methods

Your MCP ZAP Server supports two authentication methods:

1. **API Key Authentication** - Direct API key in headers
2. **JWT Authentication** - Token-based authentication with expiration

## Client Configuration Examples

### 1. Claude Desktop

Claude Desktop uses the MCP settings file to configure servers.

#### Location
- **macOS**: `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows**: `%APPDATA%\Claude\claude_desktop_config.json`
- **Linux**: `~/.config/Claude/claude_desktop_config.json`

#### API Key Authentication

```json
{
  "mcpServers": {
    "zap-security": {
      "command": "node",
      "args": ["/path/to/mcp-client-proxy.js"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:7456",
        "MCP_API_KEY": "your-mcp-api-key-here"
      }
    }
  }
}
```

#### JWT Authentication (with Token Refresh)

For JWT, you'll need a proxy script that handles token refresh:

```json
{
  "mcpServers": {
    "zap-security": {
      "command": "node",
      "args": ["/path/to/mcp-jwt-proxy.js"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:7456",
        "MCP_CLIENT_ID": "default-client",
        "MCP_API_KEY": "your-mcp-api-key-here"
      }
    }
  }
}
```

**Proxy Script Example** (`mcp-jwt-proxy.js`):

```javascript
const http = require('http');
const https = require('https');

class JWTProxyServer {
  constructor(serverUrl, clientId, apiKey) {
    this.serverUrl = serverUrl;
    this.clientId = clientId;
    this.apiKey = apiKey;
    this.accessToken = null;
    this.refreshToken = null;
    this.tokenExpiry = null;
  }

  async getAccessToken() {
    // Check if we have a valid token
    if (this.accessToken && this.tokenExpiry && Date.now() < this.tokenExpiry - 60000) {
      return this.accessToken;
    }

    // Try to refresh if we have a refresh token
    if (this.refreshToken) {
      try {
        await this.refreshAccessToken();
        return this.accessToken;
      } catch (err) {
        console.error('Token refresh failed:', err.message);
      }
    }

    // Get new tokens
    await this.getNewTokens();
    return this.accessToken;
  }

  async getNewTokens() {
    const response = await this.makeRequest('/auth/token', 'POST', {
      apiKey: this.apiKey,
      clientId: this.clientId
    }, false);

    this.accessToken = response.accessToken;
    this.refreshToken = response.refreshToken;
    this.tokenExpiry = Date.now() + (response.expiresIn * 1000);
  }

  async refreshAccessToken() {
    const response = await this.makeRequest('/auth/refresh', 'POST', {
      refreshToken: this.refreshToken
    }, false);

    this.accessToken = response.accessToken;
    this.tokenExpiry = Date.now() + (response.expiresIn * 1000);
  }

  async makeRequest(path, method, body, useAuth = true) {
    return new Promise(async (resolve, reject) => {
      const url = new URL(path, this.serverUrl);
      const isHttps = url.protocol === 'https:';
      const client = isHttps ? https : http;

      const headers = {
        'Content-Type': 'application/json'
      };

      if (useAuth) {
        const token = await this.getAccessToken();
        headers['Authorization'] = `Bearer ${token}`;
      }

      const bodyStr = body ? JSON.stringify(body) : undefined;
      if (bodyStr) {
        headers['Content-Length'] = Buffer.byteLength(bodyStr);
      }

      const req = client.request(url, {
        method,
        headers
      }, (res) => {
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          try {
            resolve(JSON.parse(data));
          } catch (err) {
            reject(new Error(`Invalid JSON: ${data}`));
          }
        });
      });

      req.on('error', reject);
      if (bodyStr) req.write(bodyStr);
      req.end();
    });
  }

  async proxyRequest(mcpRequest) {
    // Forward MCP request to server with authentication
    return await this.makeRequest('/mcp', 'POST', mcpRequest, true);
  }
}

// Main proxy logic
async function main() {
  const serverUrl = process.env.MCP_SERVER_URL;
  const clientId = process.env.MCP_CLIENT_ID;
  const apiKey = process.env.MCP_API_KEY;

  if (!serverUrl || !clientId || !apiKey) {
    console.error('Missing required environment variables');
    process.exit(1);
  }

  const proxy = new JWTProxyServer(serverUrl, clientId, apiKey);

  // Read MCP requests from stdin, forward with auth, write responses to stdout
  let buffer = '';
  process.stdin.on('data', async (chunk) => {
    buffer += chunk.toString();
    
    // Process complete JSON messages
    let newlineIndex;
    while ((newlineIndex = buffer.indexOf('\n')) !== -1) {
      const line = buffer.slice(0, newlineIndex);
      buffer = buffer.slice(newlineIndex + 1);

      if (line.trim()) {
        try {
          const request = JSON.parse(line);
          const response = await proxy.proxyRequest(request);
          console.log(JSON.stringify(response));
        } catch (err) {
          console.error('Proxy error:', err.message);
          console.log(JSON.stringify({ error: err.message }));
        }
      }
    }
  });
}

main().catch(console.error);
```

### 2. Cursor IDE

Cursor uses similar configuration to Claude Desktop.

#### Location
- **macOS**: `~/Library/Application Support/Cursor/User/globalStorage/saoudrizwan.claude-dev/settings/cline_mcp_settings.json`
- **Windows**: `%APPDATA%\Cursor\User\globalStorage\saoudrizwan.claude-dev\settings\cline_mcp_settings.json`

#### Configuration (API Key)

```json
{
  "mcpServers": {
    "zap-security": {
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-http-client", "http://localhost:7456"],
      "env": {
        "HTTP_HEADERS": "X-API-Key: your-mcp-api-key-here"
      }
    }
  }
}
```

#### Configuration (JWT)

For JWT, you need the proxy script approach:

```json
{
  "mcpServers": {
    "zap-security": {
      "command": "node",
      "args": ["/path/to/mcp-jwt-proxy.js"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:7456",
        "MCP_CLIENT_ID": "default-client",
        "MCP_API_KEY": "your-mcp-api-key-here"
      }
    }
  }
}
```

### 3. Continue.dev

Continue.dev uses `.continue/config.json` for MCP server configuration.

#### Location
- `~/.continue/config.json` (global)
- Or `.continue/config.json` in your project root

#### Configuration

```json
{
  "mcpServers": [
    {
      "name": "zap-security",
      "command": "node",
      "args": ["/path/to/mcp-jwt-proxy.js"],
      "env": {
        "MCP_SERVER_URL": "http://localhost:7456",
        "MCP_CLIENT_ID": "default-client",
        "MCP_API_KEY": "your-mcp-api-key-here"
      }
    }
  ]
}
```

### 4. Custom MCP Client (Python)

If you're building your own MCP client:

```python
import requests
import json
import time
from datetime import datetime, timedelta

class AuthenticatedMCPClient:
    def __init__(self, server_url, client_id, api_key):
        self.server_url = server_url.rstrip('/')
        self.client_id = client_id
        self.api_key = api_key
        self.access_token = None
        self.refresh_token = None
        self.token_expiry = None
    
    def get_access_token(self):
        """Get valid access token, refreshing if necessary"""
        # Check if current token is valid
        if self.access_token and self.token_expiry:
            if datetime.now() < self.token_expiry - timedelta(minutes=1):
                return self.access_token
        
        # Try to refresh
        if self.refresh_token:
            try:
                self._refresh_token()
                return self.access_token
            except Exception as e:
                print(f"Token refresh failed: {e}")
        
        # Get new tokens
        self._get_new_tokens()
        return self.access_token
    
    def _get_new_tokens(self):
        """Exchange API key for JWT tokens"""
        response = requests.post(
            f"{self.server_url}/auth/token",
            json={
                "apiKey": self.api_key,
                "clientId": self.client_id
            }
        )
        response.raise_for_status()
        data = response.json()
        
        self.access_token = data['accessToken']
        self.refresh_token = data['refreshToken']
        self.token_expiry = datetime.now() + timedelta(seconds=data['expiresIn'])
    
    def _refresh_token(self):
        """Refresh access token"""
        response = requests.post(
            f"{self.server_url}/auth/refresh",
            json={"refreshToken": self.refresh_token}
        )
        response.raise_for_status()
        data = response.json()
        
        self.access_token = data['accessToken']
        self.token_expiry = datetime.now() + timedelta(seconds=data['expiresIn'])
    
    def call_tool(self, tool_name, arguments):
        """Call MCP tool with authentication"""
        token = self.get_access_token()
        
        response = requests.post(
            f"{self.server_url}/mcp",
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json"
            },
            json={
                "jsonrpc": "2.0",
                "method": "tools/call",
                "params": {
                    "name": tool_name,
                    "arguments": arguments
                },
                "id": 1
            }
        )
        response.raise_for_status()
        return response.json()

# Usage
client = AuthenticatedMCPClient(
    server_url="http://localhost:7456",
    client_id="default-client",
    api_key="your-mcp-api-key"
)

# Call tools
result = client.call_tool("zap_spider_scan", {
    "url": "https://example.com",
    "maxDepth": 5
})
print(result)
```

### 5. Open WebUI (Your Current Setup)

For Open WebUI with MCPO (MCP-over-HTTP):

#### docker-compose.yml Configuration

```yaml
services:
  open-webui:
    environment:
      # MCP Server Configuration
      - ENABLE_MCP_SERVERS=true
      - MCP_SERVERS=[{
          "name": "zap-security",
          "url": "http://mcp-zap-server:7456",
          "headers": {
            "X-API-Key": "${MCP_API_KEY}"
          }
        }]
```

Or for JWT authentication, you'll need to handle token management in the Open WebUI configuration:

```yaml
services:
  open-webui:
    environment:
      - ENABLE_MCP_SERVERS=true
      - MCP_SERVERS=[{
          "name": "zap-security",
          "url": "http://mcp-zap-server:7456",
          "auth": {
            "type": "jwt",
            "tokenUrl": "http://mcp-zap-server:7456/auth/token",
            "clientId": "${MCP_CLIENT_ID}",
            "apiKey": "${MCP_API_KEY}"
          }
        }]
```

## Simplified Approach: Use API Key Authentication

For most MCP clients, **API key authentication is simpler** since you don't need to handle token refresh logic. The client can just pass the API key in every request:

### Direct HTTP MCP Client

```json
{
  "mcpServers": {
    "zap-security": {
      "url": "http://localhost:7456",
      "headers": {
        "X-API-Key": "your-mcp-api-key-here"
      }
    }
  }
}
```

## When to Use JWT vs API Key

### Use API Key When:
- ✅ Simple MCP client setup (single config file)
- ✅ No need for token expiration
- ✅ Trusted environment
- ✅ Single client application

### Use JWT When:
- ✅ Multiple clients with different permissions
- ✅ Need token expiration and refresh
- ✅ Implementing proper security lifecycle
- ✅ Audit logging and token revocation needed
- ✅ Exposing MCP server over internet

## Testing Your Configuration

### Test API Key Authentication

```bash
# Test direct API key
curl -H "X-API-Key: your-mcp-api-key" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

### Test JWT Authentication

```bash
# 1. Get token
TOKEN_RESPONSE=$(curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-mcp-api-key","clientId":"default-client"}')

ACCESS_TOKEN=$(echo $TOKEN_RESPONSE | jq -r '.accessToken')

# 2. Use token
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## Troubleshooting

### "Missing API key" Error

**Problem**: MCP client isn't sending authentication headers.

**Solution**: Check your client configuration includes the `headers` or `env` section with the API key.

### "Invalid or expired JWT token"

**Problem**: Access token has expired and wasn't refreshed.

**Solution**: 
1. Implement automatic token refresh in your proxy script
2. Or use API key authentication for simpler setup

### MCP Client Can't Connect

**Problem**: Network issues or wrong URL.

**Solution**:
1. Verify MCP server is running: `curl http://localhost:7456/actuator/health`
2. Check Docker network if using containers
3. Verify firewall settings

### Headers Not Being Sent

**Problem**: Some MCP clients may not support custom headers.

**Solution**: Use a proxy script that handles authentication and forwards requests.

## Recommended Setup

For **development/testing**: Use **API key authentication** - simpler setup, no token refresh needed.

For **production**: Use **JWT authentication** with a proper client library that handles token refresh automatically.

## Next Steps

1. Choose your authentication method (API key recommended for MCP clients)
2. Configure your MCP client with appropriate headers
3. Test the connection with a simple tool call
4. Implement error handling and token refresh if using JWT

---

**Need Help?** Check the [JWT Authentication Guide](JWT_AUTHENTICATION.md) or [Security Documentation](../SECURITY.md) for more details.
