# Quick Start Security Guide

## First Time Setup (5 minutes)

### 1. Clone and Navigate
```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server
```

### 2. Generate API Keys
```bash
# Generate ZAP API key
echo "ZAP_API_KEY=$(openssl rand -hex 32)"

# Generate MCP API key
echo "MCP_API_KEY=$(openssl rand -hex 32)"
```

### 3. Create .env File
```bash
cp .env.example .env
# Edit .env and paste your generated keys
```

### 4. Set Workspace Directory
```bash
mkdir -p ./zap-workplace
echo "LOCAL_ZAP_WORKPLACE_FOLDER=$(pwd)/zap-workplace" >> .env
```

### 5. Start Services
```bash
docker-compose up -d
```

### 6. Verify Health
```bash
curl http://localhost:7456/actuator/health
# Should return: {"status":"UP"}
```

---

## Configuration Presets

### Development (Local Testing)
```bash
# .env
MCP_SECURITY_ENABLED=true
ZAP_ALLOW_LOCALHOST=true
ZAP_ALLOW_PRIVATE_NETWORKS=true
ZAP_URL_WHITELIST=
ZAP_MAX_ACTIVE_SCAN_DURATION=10
```

### Staging (Internal Network)
```bash
# .env
MCP_SECURITY_ENABLED=true
ZAP_ALLOW_LOCALHOST=false
ZAP_ALLOW_PRIVATE_NETWORKS=false
ZAP_URL_WHITELIST=*.staging.yourcompany.com
ZAP_MAX_ACTIVE_SCAN_DURATION=30
```

### Production (Public Targets)
```bash
# .env
MCP_SECURITY_ENABLED=true
ZAP_ALLOW_LOCALHOST=false
ZAP_ALLOW_PRIVATE_NETWORKS=false
ZAP_URL_WHITELIST=yourcompany.com,*.yourcompany.com
ZAP_MAX_ACTIVE_SCAN_DURATION=30
ZAP_MAX_CONCURRENT_ACTIVE_SCANS=3
```

---

## Client Configuration

### Claude Desktop
```json
{
  "mcpServers": {
    "zap": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "your-mcp-api-key-here"
      }
    }
  }
}
```

### Cursor
```json
{
  "mcpServers": {
    "zap": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp",
      "headers": {
        "Authorization": "Bearer your-mcp-api-key-here"
      }
    }
  }
}
```

---

## Testing API Access

### Test Authentication
```bash
# Should fail (no API key)
curl http://localhost:7456/mcp
# Returns: 401 Unauthorized

# Should succeed
curl -H "X-API-Key: your-mcp-api-key" http://localhost:7456/mcp
# Returns: MCP response
```

### Test URL Validation
```bash
# Start a scan (will validate URL)
# In your AI client, try:
"Start a spider scan on http://localhost:3001"  # Fails if allowLocalhost=false
"Start a spider scan on http://example.com"     # Succeeds (public URL)
```

---

## Troubleshooting

### Issue: "Server configuration error: API key not set"
**Solution**: Set `MCP_API_KEY` in your .env file

### Issue: "Missing API key in request"
**Solution**: Add API key to client headers configuration

### Issue: "URL host 'localhost' is not allowed"
**Solution**: Set `ZAP_ALLOW_LOCALHOST=true` in .env (dev only!)

### Issue: "URL host 'example.com' is not in whitelist"
**Solution**: Add to `ZAP_URL_WHITELIST=example.com` in .env

### Issue: Container fails to start
**Solution**: Check if `LOCAL_ZAP_WORKPLACE_FOLDER` path exists and is accessible

---

## Security Checklist

- [ ] Generated strong API keys (32 bytes random)
- [ ] .env file created and configured
- [ ] .env file NOT committed to git
- [ ] MCP_SECURITY_ENABLED=true for production
- [ ] ZAP_ALLOW_LOCALHOST=false for production
- [ ] ZAP_ALLOW_PRIVATE_NETWORKS=false for production
- [ ] URL whitelist configured for production
- [ ] Health check endpoint accessible
- [ ] API key authentication tested
- [ ] Scan limits configured appropriately

---

## Common Commands

```bash
# View logs
docker-compose logs -f mcp-server

# Restart services
docker-compose restart

# Stop services
docker-compose down

# Rebuild after code changes
docker-compose build && docker-compose up -d

# Check service status
docker-compose ps

# View ZAP workspace
ls -la $LOCAL_ZAP_WORKPLACE_FOLDER/zap-wrk
```

---

## Support

- **Documentation**: See README.md and SECURITY.md
- **Issues**: https://github.com/dtkmn/mcp-zap-server/issues
- **Security**: danieltse@gmail.com (private disclosure)

---

**Last Updated**: November 19, 2025
