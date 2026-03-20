# Quick Start Security Guide

Use this when you want the fastest sane local setup for `mcp-zap-server` without reverse-engineering the whole repo.

If you are deploying for a shared team or public targets, stop here after local validation and read the full docs site plus the production checklist.

## Fastest Local Path

### 1. Clone and enter the repo

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server
```

### 2. Create `.env`

```bash
cp .env.example .env
```

Generate keys:

```bash
openssl rand -hex 32
openssl rand -hex 32
```

Set at least these values in `.env`:

```bash
ZAP_API_KEY=your-generated-zap-api-key
MCP_API_KEY=your-generated-mcp-api-key
LOCAL_ZAP_WORKPLACE_FOLDER=$(pwd)/zap-workplace
MCP_SECURITY_MODE=api-key
MCP_SECURITY_ENABLED=true
```

### 3. Create the local workspace directory

```bash
mkdir -p "$(pwd)/zap-workplace"/zap-wrk
mkdir -p "$(pwd)/zap-workplace"/zap-home
```

### 4. Start the default stack

```bash
docker compose up -d
```

This starts:

- OWASP ZAP
- the MCP server on `http://localhost:7456/mcp`
- Open WebUI on `http://localhost:3000`
- local demo targets such as Juice Shop

### 5. Verify health

```bash
curl http://localhost:7456/actuator/health
```

Expected shape:

```json
{"status":"UP"}
```

## Local Security Reality

The default local Compose stack is intentionally convenient, not hardened:

- MCP auth defaults to `api-key`
- Open WebUI is preconfigured to send `X-API-Key`
- local Compose defaults allow localhost and private-network targets for development convenience

That is acceptable for an isolated laptop lab. It is not a production posture.

For production-like settings, tighten these in `.env`:

```bash
ZAP_ALLOW_LOCALHOST=false
ZAP_ALLOW_PRIVATE_NETWORKS=false
ZAP_URL_WHITELIST=example.com,*.example.com
```

## Client Setup

### Open WebUI

Open [http://localhost:3000](http://localhost:3000). The default Compose stack already wires it to the local MCP server.

This is the easiest local path.

### Cursor

Example `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "zap-security": {
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "${env:MCP_API_KEY}"
      }
    }
  }
}
```

Do not send your API key as `Authorization: Bearer ...` unless you are actually using a JWT access token.

## Manual MCP Check

If you test `/mcp` with raw HTTP, remember this is streamable MCP. A bare `curl http://localhost:7456/mcp` is not a real protocol check.

### Initialize the MCP session

```bash
SESSION_ID=$(curl -si \
  -H "X-API-Key: your-mcp-api-key" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}' \
  | awk -F': ' '/Mcp-Session-Id/ {print $2}' | tr -d '\r')
```

### List tools

```bash
curl -H "X-API-Key: your-mcp-api-key" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","method":"tools/list","id":1}'
```

## JWT Quick Check

Use JWT only when you actually need token expiry, refresh rotation, or shared production auth behavior.

Enable it in `.env`:

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-base64-or-random-32-byte-secret
```

Then restart:

```bash
docker compose down
docker compose up -d
```

Mint a token:

```bash
curl -s -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey":"your-mcp-api-key","clientId":"default-client"}'
```

If you manually use the returned access token against `/mcp`, you still need the same `initialize` and `Mcp-Session-Id` flow shown above.

## Recommended Local Settings

### Local development lab

```bash
MCP_SECURITY_MODE=api-key
MCP_SECURITY_ENABLED=true
ZAP_ALLOW_LOCALHOST=true
ZAP_ALLOW_PRIVATE_NETWORKS=true
ZAP_URL_WHITELIST=
```

### Shared internal environment

```bash
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
ZAP_ALLOW_LOCALHOST=false
ZAP_ALLOW_PRIVATE_NETWORKS=false
ZAP_URL_WHITELIST=*.staging.yourcompany.com
```

## Common Mistakes

### "401 Unauthorized"

- wrong `MCP_API_KEY`
- wrong auth mode
- client forgot to send `X-API-Key` or bearer token

### "Client connects but MCP calls fail"

- you skipped the `initialize` step in manual testing
- you did not send back `Mcp-Session-Id`

### "URL host 'localhost' is not allowed"

Set:

```bash
ZAP_ALLOW_LOCALHOST=true
```

but only for isolated local work.

### "Container fails to start"

Check that `LOCAL_ZAP_WORKPLACE_FOLDER` exists and is writable.

## Useful Commands

```bash
# View logs
docker compose logs -f mcp-server

# Restart services
docker compose restart

# Stop services
docker compose down

# Rebuild after code changes
docker compose build
docker compose up -d

# Check service status
docker compose ps
```

## Read Next

- `README.md`
- `SECURITY.md`
- docs site local preview:

```bash
cd docs
npm install
npm run dev
```

- public docs site: <https://dtkmn.github.io/mcp-zap-server/>
