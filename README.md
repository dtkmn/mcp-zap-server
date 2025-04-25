# ZAP MCP Server

A Spring Boot application exposing OWASP ZAP as an MCP (Model Context Protocol) server. It lets any MCP‑compatible AI agent (e.g., Claude Desktop, Cursor) orchestrate ZAP actions—spider, active scan, import OpenAPI specs, and generate reports.

>**IMPORTANT** This project is a work in progress and is not yet production-ready. It is intended for educational purposes and to demonstrate the capabilities of the Model Context Protocol (MCP) with OWASP ZAP.

## Features
- **MCP server**: Exposes ZAP actions as MCP tools
- **OpenAPI integration**: Import remote or uploaded OpenAPI specs into ZAP and kick off active scans
- **Report generation**: Generate HTML/JSON reports and fetch contents programmatically
- **Dockerized**: Runs ZAP and the MCP server in containers, orchestrated via docker-compose
- **Secure**: Configure API keys for both ZAP (ZAP_API_KEY) and the MCP server (MCP_API_KEY)

## Architecture
```mermaid
flowchart LR
  subgraph "Docker Compose"
    ZAP["OWASP ZAP (container)"]
    MCP["MCP Server (Spring Boot)"]
    Client["MCP Client (Open Web-UI)"]
  end
  OtherClient["MCP Clients (Claude, Cursor)"] --> |HTTP/SSE + Bearer| MCP
  Client -->|HTTP/SSE + Bearer| MCP
  MCP -->|ZAP REST API| ZAP
  ZAP -->|scan, alerts, reports| MCP
```

## Manual build

```bash
./gradlew clean build
```

## Usage with Claude Desktop, Cursor, Windsurf or any MCP‑compatible AI agent

### STDIO mode

```json
{
  "mcpServers": {
    "zap-mcp-server": {
        "command": "java",
        "args": [
          "-Dspring.ai.mcp.server.stdio=true",
          "-Dspring.main.web-application-type=none",
          "-Dlogging.pattern.console=",
          "-jar",
          "/PROJECT_PATH/mcp-zap-server/build/libs/mcp-zap-server-0.0.1-SNAPSHOT.jar"
        ]
    }
  }
}
```

### SSE mode

```json
{
  "mcpServers": {
    "zap-mcp-server": {
      "protocol": "mcp",
      "transport": "http",
      "serverUrl": "http://localhost:7456/sse"
    }
  }
}
```