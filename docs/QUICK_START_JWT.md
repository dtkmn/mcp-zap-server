# Quick Start: JWT Authentication

Get started with JWT authentication in 5 minutes.

## Prerequisites

- MCP ZAP Server running (via Docker or locally)
- curl or any HTTP client

## Step 1: Generate JWT Secret

```bash
openssl rand -base64 32
```

Output example:
```
Xj8K3mN9pQ2rS5tU7vW8xY0zA1bC3dE4fG5hI6jK7lM=
```

## Step 2: Configure Environment

Edit your `.env` file:

```bash
# Enable JWT
JWT_ENABLED=true

# Set your generated secret (copy from Step 1)
JWT_SECRET=Xj8K3mN9pQ2rS5tU7vW8xY0zA1bC3dE4fG5hI6jK7lM=

# Optional: Customize expiration (defaults shown)
JWT_ACCESS_TOKEN_EXPIRATION=3600    # 1 hour
JWT_REFRESH_TOKEN_EXPIRATION=604800 # 7 days
```

## Step 3: Restart Services

```bash
docker-compose down
docker-compose up -d
```

Or if running locally:
```bash
./gradlew bootRun
```

## Step 4: Get JWT Tokens

Exchange your API key for JWT tokens:

```bash
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{
    "apiKey": "your-mcp-api-key",
    "clientId": "default-client"
  }'
```

Response:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJkZWZhdWx0LWNsaWVudCIsImlzcyI6Im1jcC16YXAtc2VydmVyIiwiaWF0IjoxNzA1NTc0NDAwLCJleHAiOjE3MDU1NzgwMDAsImp0aSI6IjEyMzQ1Njc4LTkwYWItY2RlZi0xMjM0LTU2Nzg5MGFiY2RlZiIsInNjb3BlcyI6W10sInR5cGUiOiJhY2Nlc3MifQ.signature",
  "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJkZWZhdWx0LWNsaWVudCIsImlzcyI6Im1jcC16YXAtc2VydmVyIiwiaWF0IjoxNzA1NTc0NDAwLCJleHAiOjE3MDYxNzkyMDAsImp0aSI6Ijk4NzY1NDMyLTEwYWItY2RlZi0xMjM0LTU2Nzg5MGFiY2RlZiIsInR5cGUiOiJyZWZyZXNoIn0.signature",
  "tokenType": "Bearer",
  "expiresIn": 3600
}
```

## Step 5: Use Access Token

Use the access token for API requests:

```bash
# Save the access token
ACCESS_TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

# Make authenticated request
curl -X POST http://localhost:7456/zap/spider/start \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://example.com",
    "maxDepth": 5
  }'
```

## Step 6: Refresh Token (Optional)

When access token expires, use refresh token:

```bash
REFRESH_TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."

curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\": \"$REFRESH_TOKEN\"}"
```

## Troubleshooting

### "JWT secret is not configured"

Make sure `JWT_SECRET` is set in `.env` and services are restarted.

### "JWT secret must be at least 256 bits"

Your secret is too short. Generate a new one:
```bash
openssl rand -base64 32
```

### "Invalid API key"

Check that `MCP_API_KEY` in `.env` matches the one you're using.

### "Invalid or expired JWT token"

1. Check token expiration with `/auth/validate`
2. Use `/auth/refresh` to get a new access token
3. If refresh token is expired, get new tokens via `/auth/token`

## Next Steps

- Read the [full JWT authentication guide](JWT_AUTHENTICATION.md)
- Implement automatic token refresh in your client
- Configure custom expiration times for your use case
- Set up multiple API keys for different clients

## Validation (Optional)

Test your token is valid:

```bash
curl -X GET http://localhost:7456/auth/validate \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

Response:
```json
{
  "valid": true,
  "clientId": "default-client",
  "scopes": [],
  "issuer": "mcp-zap-server",
  "issuedAt": "2025-01-18T10:30:00Z",
  "expiresAt": "2025-01-18T11:30:00Z",
  "tokenType": "access"
}
```

---

**That's it!** You now have JWT authentication working with your MCP ZAP Server.

For more details, see the [complete JWT Authentication Guide](JWT_AUTHENTICATION.md).
