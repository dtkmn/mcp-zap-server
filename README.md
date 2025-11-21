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

## 📚 Documentation

**[📖 View Full Documentation](https://dtkmn.github.io/mcp-zap-server/)** - Complete guides, API reference, and examples

### Quick Links
- [Security & Authentication Guide](https://dtkmn.github.io/mcp-zap-server/SECURITY_MODES.html) - Three security modes
- [JWT Authentication Setup](https://dtkmn.github.io/mcp-zap-server/JWT_AUTHENTICATION.html) - Production-ready auth
- [AJAX Spider Guide](https://dtkmn.github.io/mcp-zap-server/AJAX_SPIDER.html) - Bypass WAF protection
- [Kubernetes Deployment](./helm/README.md) - Helm charts for production


### Demo on Cursor
**[📺 Watch Demo Video](https://dtkmn.github.io/mcp-zap-server/demo.html)** | [YouTube Link](https://www.youtube.com/watch?v=9_9VqsL0lNw)

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

## Security Configuration

### Generate API Keys

Before starting the services, generate secure API keys:

```bash
# Generate ZAP API key
openssl rand -hex 32

# Generate MCP API key
openssl rand -hex 32
```

### Environment Setup

1. Copy the example environment file:
```bash
cp .env.example .env
```

2. Edit `.env` and update the following required values:
```bash
# Required: Set your secure API keys
ZAP_API_KEY=your-generated-zap-api-key-here
MCP_API_KEY=your-generated-mcp-api-key-here

# Required: Set your workspace directory
LOCAL_ZAP_WORKPLACE_FOLDER=/path/to/your/zap-workplace
```

3. **Important**: Never commit `.env` to version control. It's already in `.gitignore`.

### Security Features

The MCP ZAP Server includes comprehensive security features with **three authentication modes**:

- **🔐 Flexible Authentication**: Choose between `none`, `api-key`, or `jwt` modes
- **🔑 API Key Authentication**: Simple bearer token for trusted environments
- **🎫 JWT Authentication**: Modern token-based auth with expiration and refresh
- **🛡️ URL Validation**: Prevents scanning of internal resources and private networks
- **⏱️ Scan Limits**: Configurable timeouts and concurrent scan limits
- **📋 Whitelist/Blacklist**: Fine-grained control over scannable domains

### Authentication Modes

The MCP server supports **three authentication modes** to balance security and ease of use:

#### 🚫 Mode 1: No Authentication (`none`)

**⚠️ WARNING: Development/Testing ONLY**

```bash
# .env
MCP_SECURITY_MODE=none
```

Use this mode **only** for local development on trusted networks. All requests are permitted without authentication.

**CSRF Protection**: Disabled for MCP protocol compatibility (MCP endpoints don't support CSRF tokens).

#### 🔑 Mode 2: API Key Authentication (`api-key`)

**✅ Recommended for: Simple deployments, internal networks**

```bash
# .env
MCP_SECURITY_MODE=api-key
MCP_API_KEY=your-secure-api-key-here
```

Simple authentication with a static API key:

```bash
# Using X-API-Key header
curl -H "X-API-Key: your-mcp-api-key" http://localhost:7456/mcp
```

**CSRF Protection**: Disabled (by design) - This is an API-only server using token-based authentication, not session cookies. CSRF attacks only affect cookie-based authentication in browsers. See [SECURITY.md](SECURITY.md#csrf-protection---why-its-disabled) for detailed explanation.

**Advantages**: Simple configuration, no token expiration, minimal overhead  
**Use Cases**: Docker Compose, internal networks, single-tenant deployments

#### 🎫 Mode 3: JWT Authentication (`jwt`)

**✅ Recommended for: Production, cloud deployments, multi-tenant**

```bash
# .env
MCP_SECURITY_MODE=jwt
JWT_ENABLED=true
JWT_SECRET=your-256-bit-secret-minimum-32-chars
MCP_API_KEY=your-initial-api-key
```

Token-based authentication with automatic expiration:

```bash
# 1. Exchange API key for JWT tokens
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey": "your-mcp-api-key", "clientId": "your-client-id"}'

# 2. Use access token (expires in 1 hour)
curl -H "Authorization: Bearer YOUR_ACCESS_TOKEN" http://localhost:7456/mcp

# 3. Refresh when expired (refresh token valid for 7 days)
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "YOUR_REFRESH_TOKEN"}'
```

**CSRF Protection**: Disabled (by design) - API-only server with stateless JWT authentication. See [SECURITY.md](SECURITY.md#csrf-protection---why-its-disabled) for OWASP compliance explanation.

**Advantages**: Tokens expire (1hr access, 7d refresh), token revocation, audit trails  
**Use Cases**: Production deployments, public access, compliance requirements

**Note**: JWT mode is backward compatible—clients can still use API keys during migration.

**🔐 MCP Security Compliance**: This server follows the [Model Context Protocol Security Best Practices](https://modelcontextprotocol.io/specification/draft/basic/security_best_practices). See [SECURITY.md](SECURITY.md#mcp-security-best-practices-compliance) for full compliance details and roadmap.

📚 **Detailed Documentation**:
- [Security Modes Guide](docs/SECURITY_MODES.md) - Complete comparison and migration guide
- [JWT Authentication Guide](docs/JWT_AUTHENTICATION.md) - JWT implementation details
- [MCP Client Configuration](docs/MCP_CLIENT_AUTHENTICATION.md) - Client setup for all modes

### URL Security Configuration

By default, the server blocks scanning of:
- Localhost and loopback addresses (127.0.0.0/8, ::1)
- Private network ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
- Link-local addresses (169.254.0.0/16)

To enable scanning specific domains, configure the whitelist in `.env`:
```bash
# Allow only specific domains (wildcards supported)
ZAP_URL_WHITELIST=example.com,*.test.com,demo.org
```

**Warning**: Only enable `ZAP_ALLOW_LOCALHOST=true` or `ZAP_ALLOW_PRIVATE_NETWORKS=true` in isolated, secure environments.

## Quick Start

### Development (Fast Builds - 2-3 minutes) ⚡

For local development, use the JVM image for fast iteration:

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server

# Setup environment variables
cp .env.example .env
# Edit .env with your API keys and configuration

# Create workspace directory
mkdir -p $(grep LOCAL_ZAP_WORKPLACE_FOLDER .env | cut -d '=' -f2)/zap-wrk

# Start services (JVM - fast builds)
./dev.sh
# OR manually:
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d --build
```

**Build time:** ~2-3 minutes  
**Startup:** ~3-5 seconds  
**Use for:** Development, testing, rapid iteration

### Production (Native Image - 20+ minutes) 🏭

For production deployments with lightning-fast startup:

```bash
# Build native image (grab a coffee ☕)
./prod.sh
# OR manually:
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

**Build time:** ~20-25 minutes  
**Startup:** ~0.6 seconds  
**Use for:** Production, cloud deployments, serverless

### Performance Comparison

| Metric | JVM (Dev) | Native (Prod) |
|--------|-----------|---------------|
| Build Time | 2-3 min | 20-25 min |
| Startup Time | 3-5 sec | 0.6 sec |
| Memory | ~300MB | ~200MB |
| Image Size | 383MB | 391MB |
| **Best For** | **Development** | **Production** |
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
- **Purpose:** This repo. Acts as the MCP server exposing ZAP actions with API key authentication.
- **Configuration:**
    - Depends on the `zap` service and connects to it using the configured `ZAP_API_KEY`.
    - Requires `MCP_API_KEY` for client authentication (set in `.env` file).
    - Exposes port 7456 for streamable HTTP connections.
    - Maps the host directory `${LOCAL_ZAP_WORKPLACE_FOLDER}` to `/zap/wrk` to allow file access.
    - Supports configurable scan limits and URL validation policies.
- **Security:**
    - All endpoints (except health checks) require API key authentication.
    - Include API key in requests via `X-API-Key` header or `Authorization: Bearer <token>`.
    - URL validation prevents scanning internal/private networks by default.

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

**Important**: You must include the API key for authentication.

```json
{
  "mcpServers": {
    "zap-mcp-server": {
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

Or using Bearer token:

```json
{
  "mcpServers": {
    "zap-mcp-server": {
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

Replace `your-mcp-api-key-here` with the `MCP_API_KEY` value from your `.env` file.


## Prompt Examples

### Asking for the tools available
![mcp-zap-server-prompt-1](./images/mcp-zap-server-prompt-1.png)

### Start the spider scan with provided URL
![mcp-zap-server-prompt-2](./images/mcp-zap-server-prompt-2.png)

### Check the alerts found from the spider scan
![mcp-zap-server-prompt-3](./images/mcp-zap-server-prompt-3.png)
