![GitHub stars](https://img.shields.io/github/stars/dtkmn/mcp-zap-server?style=social)
![GitHub forks](https://img.shields.io/github/forks/dtkmn/mcp-zap-server?style=social)
![GitHub watchers](https://img.shields.io/github/watchers/dtkmn/mcp-zap-server?style=social)
![GitHub repo size](https://img.shields.io/github/repo-size/dtkmn/mcp-zap-server)
![GitHub language count](https://img.shields.io/github/languages/count/dtkmn/mcp-zap-server)
![GitHub top language](https://img.shields.io/github/languages/top/dtkmn/mcp-zap-server)
![GitHub last commit](https://img.shields.io/github/last-commit/dtkmn/mcp-zap-server?color=red)
![GitHub Tag](https://img.shields.io/github/v/tag/dtkmn/mcp-zap-server)


>**IMPORTANT** This project is a work in progress and is not yet production-ready. It is intended for educational purposes and to demonstrate the capabilities of the Model Context Protocol (MCP) with OWASP ZAP.

>**NOTE** This project is not affiliated with or endorsed by OWASP or the OWASP ZAP project. It is an independent implementation of the Model Context Protocol (MCP) for use with OWASP ZAP.

# MCP ZAP Server

A Spring Boot application exposing OWASP ZAP as an MCP (Model Context Protocol) server. It lets any MCP‑compatible AI agent (e.g., Claude Desktop, Cursor) orchestrate ZAP actions—spider, active scan, import OpenAPI specs, and generate reports.


### Demo on Cursor
<a href="https://www.youtube.com/watch?v=9_9VqsL0lNw" target="_blank" rel="noopener noreferrer">
<img src="https://img.youtube.com/vi/9_9VqsL0lNw/0.jpg" alt="▶️ Watch the demo">
</a>

## Table of Contents
- [Features](#features)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
    - [Set Up Custom OpenAI / Ollama API Connection](#set-up-custom-openai--ollama-api-connection)
    - [Set Up MCP Servers Connection](#set-up-mcp-servers-connection)
- [Services Overview](#services-overview)
- [Manual build](#manual-build)
- [Usage with Claude Desktop, Cursor, Windsurf or any MCP-compatible AI agent](#usage-with-claude-desktop-cursor-windsurf-or-any-mcp-compatible-ai-agent)
    - [Streamable HTTP mode](#streamable-http-mode)
- [Prompt Examples](#prompt-examples)


## Features
- **MCP ZAP server**: Exposes ZAP actions as MCP tools. Eliminates manual CLI calls and brittle scripts.
- **OpenAPI integration**: Import remote OpenAPI specs into ZAP and kick off active scans
- **Report generation**: Generate HTML/JSON reports and fetch contents programmatically
- **Dockerized**: Runs ZAP and the MCP server in containers, orchestrated via docker-compose
- **Secure**: Configure API keys for both ZAP (ZAP_API_KEY) and the MCP server (MCP_API_KEY)

## Architecture
```mermaid
flowchart LR
  subgraph "DOCKER COMPOSE"
    direction LR
    ZAP["OWASP ZAP (container)"]
    MCPZAP["MCP ZAP Server"]
    MCPFile["MCP File System Server"]
    Client["MCP Client (Open Web-UI)"]
    Juice["OWASP Juice-Shop"]
    Petstore["Swagger Petstore Server"]
  end

  MCPZAP <-->|HTTP/Streamable + MCPO| Client
  MCPFile <-->|STDIO + MCPO| Client
  MCPZAP -->|ZAP REST API| ZAP
  ZAP -->|scan, alerts, reports| MCPZAP

  ZAP -->|spider/active-scan| Juice
  ZAP -->|Import API/active-scan| Petstore
```
## Prerequisites

- LLM support Tool calling (e.g. gpt-4o, Claude 3, Llama 3, mistral, phi3)
- Docker ≥ 20.10
- Docker Compose ≥ 1.29
- Java 21+ (only if you want to build the Spring Boot MCP server outside Docker)

## Quick Start

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server
export LOCAL_ZAP_WORKPLACE_FOLDER=$(pwd)/zap-workplace # or any other folder you want to use as ZAP's workspace
docker-compose up -d
```
![Docker-Compose](./images/mcp-zap-server-docker-compose.png)

Open http://localhost:3000 in your browser, and you should see the Open Web-UI interface.

### Set Up Custom OpenAI / Ollama API Connection
![Admin-Panel-Open-WebUI](./images/Admin-Panel-Open-WebUI.png)

### Set Up MCP Servers Connection
![MCP-Tools-Config-Open-WebUI](./images/MCP-Tools-Config-Open-WebUI.png)

Once it is done, you can check the [Prompt Examples](#prompt-examples) section to see how to use the MCP ZAP server with your AI agent.

### To view logs for all services, run:
```bash
   docker-compose logs -f
```
### To view logs for a specific service, run:
```bash
   docker-compose logs -f <service_name>
```
### Services Overview

#### `zap`
- **Image:** zaproxy/zap-stable
- **Purpose:** Runs the OWASP ZAP daemon on port 8090.
- **Configuration:**
    - Requires an API key for security, configured via the `ZAP_API_KEY` environment variable.
    - Accepts requests from all addresses.
    - Maps the host directory `${LOCAL_ZAP_WORKPLACE_FOLDER}` to the container path `/zap/wrk`.

#### `open-webui`
- **Image:** ghcr.io/open-webui/open-webui
- **Purpose:** Provides a web interface for managing ZAP and the MCP server.
- **Configuration:**
    - Exposes port 3000.
    - Uses a named volume to persist backend data.

#### `mcpo`
- **Image:** ghcr.io/open-webui/mcpo:main
- **Purpose:** Expose any MCP tool as an OpenAPI-compatible HTTP server. Required by open-webui only. https://github.com/open-webui/mcpo
- **Configuration:**
    - Runs on port 8000.
    - Connects to the MCP server using streamable HTTP mode via the URL `http://mcp-server:7456/mcp`.

#### `mcp-server`
- **Image:** mcp-zap-server:latest
- **Purpose:** This repo. Acts as the MCP server exposing ZAP actions.
- **Configuration:**
    - Depends on the `zap` service and connects to it using the configured `ZAP_API_KEY`.
    - Exposes port 7456 for streamable HTTP connections.
    - Maps the host directory `${LOCAL_ZAP_WORKPLACE_FOLDER}` to `/tmp` to allow file access.

#### `mcpo-filesystem`
- **Image:** ghcr.io/open-webui/mcpo:main
- **Purpose:** Exposes the MCP File System Server as an OpenAPI-compatible HTTP endpoint.
- **Configuration:**
    - Depends on `open-webui`
    - Exposes port 8001.

#### `juice-shop`
- **Image:** bkimminich/juice-shop
- **Purpose:** Provides a deliberately insecure web application for testing ZAP’s scanning capabilities.
- **Configuration:**
    - Runs on port 3001.

#### `petstore`
- **Image:** swaggerapi/petstore3:unstable
- **Purpose:** Runs the Swagger Petstore sample API to demonstrate OpenAPI import and scanning.
- **Configuration:**
    - Runs on port 3002.


### Stopping the Services

To stop and remove all the containers, run:
```bash
docker-compose down
```

## Manual build

```bash
./gradlew clean build
```

### Usage with Claude Desktop, Cursor, Windsurf or any MCP-compatible AI agent

#### Streamable HTTP mode

This is the recommended mode for connecting to the MCP server.

```json
{
  "mcpServers": {
    "zap-mcp-server": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp"
    }
  }
}
```


## Prompt Examples

### Asking for the tools available
![mcp-zap-server-prompt-1](./images/mcp-zap-server-prompt-1.png)

### Start the spider scan with provided URL
![mcp-zap-server-prompt-2](./images/mcp-zap-server-prompt-2.png)

### Check the alerts found from the spider scan
![mcp-zap-server-prompt-3](./images/mcp-zap-server-prompt-3.png)
