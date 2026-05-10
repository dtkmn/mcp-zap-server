---
title: "Authentication Quick Start Guide"
editUrl: false
description: "Choose your authentication mode in 60 seconds."
---
Choose your authentication mode in 60 seconds.

For the shipped HTTP/server defaults, the base runtime starts in `api-key`. Use `none` only as an explicit local dev/test override.

## 🚀 Quick Setup

### Step 1: Choose Your Mode

Edit `.env` file:

```bash
# Option 1: No Authentication (dev only)
MCP_SECURITY_MODE=none

# Option 2: API Key (simple)
MCP_SECURITY_MODE=api-key
MCP_API_KEY=your-secure-key-here

# Option 3: JWT (shared or production-oriented deployments)
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-256-bit-secret-minimum-32-chars
MCP_API_KEY=your-initial-key
```

### Step 2: Generate Keys (if needed)

```bash
# Generate API key (Option 2 & 3)
openssl rand -hex 32

# Generate JWT secret (Option 3 only)
openssl rand -base64 64
```

### Step 3: Start Server

```bash
docker-compose up -d
```

## 📝 Usage Examples

### Mode 1: No Authentication

```bash
# Just call the endpoint
curl http://localhost:7456/mcp
```

⚠️ **Development only!**

### Mode 2: API Key

```bash
# Add X-API-Key header
curl -H "X-API-Key: your-key" http://localhost:7456/mcp
```

✅ **Good for simple deployments**

### Mode 3: JWT

```bash
# 1. Get token
TOKEN=$(curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-key","clientId":"client-1"}' | jq -r .accessToken)

# 2. Use token
curl -H "Authorization: Bearer $TOKEN" http://localhost:7456/mcp
```

✅ **Best for shared deployments**

## 🔄 Quick Comparison

| Feature | None | API Key | JWT |
|---------|------|---------|-----|
| Setup Time | 10 sec | 30 sec | 60 sec |
| Security | ❌ None | ⚠️ Basic | ✅ Strong |
| Token Expiry | N/A | Never | 1 hour |
| Use Case | Dev | Internal | Shared deployments |

## 📚 Need More?

- **Complete Guide**: [Security Modes](../security-modes/)
- **JWT Details**: [JWT Authentication](../security-modes/jwt-authentication/)
- **Client Setup**: [MCP Client Authentication](mcp-client-authentication/)

## 🆘 Troubleshooting

### "401 Unauthorized"
- **Mode `api-key`**: Check `X-API-Key` header matches `.env`
- **Mode `jwt`**: Token might be expired, get new one

### "Security is disabled" warning
- You're in `none` mode - change to `api-key` or `jwt` for shared deployments

### Environment variables not loading
```bash
# Recreate containers to pick up .env changes
docker-compose down
docker-compose up -d
```

## 🎯 Recommendation

- **Local Dev**: Use `none` mode
- **Docker Compose**: Use `api-key` mode  
- **Cloud/shared deployments**: Use `jwt` mode

---

**Time Investment**: 1 minute setup -> stronger deployment posture
