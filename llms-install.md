# Install MCP ZAP Server For Agentic MCP Clients

Use Docker Compose for normal installs. This project is a streamable HTTP MCP server that runs with an OWASP ZAP sidecar; do not install it as a stdio-only MCP package.

## What This Server Does

MCP ZAP Server lets MCP clients drive OWASP ZAP through guided, operator-controlled security workflows:

- spider, AJAX spider, active scan, passive scan, API import, findings, reports, and scan history tools
- API-key or JWT authentication for the MCP endpoint
- tool-scope authorization, runtime policy controls, rate limits, request limits, audit events, and metrics
- Docker Compose for local/self-hosted use and Helm for Kubernetes deployments

## Install Locally

Prerequisites:

- Docker 20.10 or newer
- Docker Compose v2
- an MCP client that supports streamable HTTP and custom request headers

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server

cp .env.example .env

# Generate ZAP_API_KEY and MCP_API_KEY values, then place them in .env.
openssl rand -hex 32
openssl rand -hex 32

docker compose up -d
```

The default stack binds local ports to `127.0.0.1`.

- MCP endpoint: `http://localhost:7456/mcp`
- bundled Open WebUI: `http://localhost:3000`
- browser-visible demo targets: Juice Shop on `http://localhost:3001`, Petstore on `http://localhost:3002`

## MCP Client Configuration

Use streamable HTTP with the `X-API-Key` header from `MCP_API_KEY`.

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

If your client requires explicit transport metadata, use:

```json
{
  "mcpServers": {
    "zap-security": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp",
      "headers": {
        "X-API-Key": "${env:MCP_API_KEY}"
      }
    }
  }
}
```

## Safe First Test

After the stack is running, ask the MCP client to list available ZAP tools before running a scan. Start with the bundled demo targets, not a public or third-party site.

Suggested first request:

```text
List the available ZAP security tools, then run a passive-safe crawl against the local Juice Shop target at http://juice-shop:3000. Do not run an active scan yet.
```

For scans inside the default Compose stack, use the service URL reachable by the ZAP container:

```text
http://juice-shop:3000
```

The host URL is only for opening the demo app in your browser:

```text
http://localhost:3001
```

## Important Safety Notes

- Only scan systems you own or are explicitly authorized to test.
- Keep the default loopback binding unless you are deliberately deploying behind trusted network controls.
- Keep `MCP_SECURITY_MODE=api-key` or `jwt`; use `none` only for isolated local development.
- This repository does not currently ship a first-party Claude Desktop OAuth connector or stdio proxy helper.

More documentation:

- https://danieltse.org/mcp-zap-server/
- https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/
- https://danieltse.org/mcp-zap-server/operations/production-checklist/
