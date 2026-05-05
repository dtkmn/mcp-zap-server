![GitHub stars](https://img.shields.io/github/stars/dtkmn/mcp-zap-server?style=social)
![GitHub forks](https://img.shields.io/github/forks/dtkmn/mcp-zap-server?style=social)
![GitHub Tag](https://img.shields.io/github/v/tag/dtkmn/mcp-zap-server)

>**NOTE** This project is not affiliated with or endorsed by OWASP or the OWASP ZAP project. It is an independent implementation.

# MCP ZAP Server

`mcp-zap-server` turns OWASP ZAP into a self-hosted MCP server so AI agents can run guided or expert web security workflows without brittle glue scripts.

This repository is the open-source `mcp-zap-server` distribution.

It is designed for teams that want:

- a smaller guided default tool surface, with an optional expert surface for raw ZAP control
- self-hosted auth, queueing, findings/report workflows, and production-oriented observability
- a documented Docker and Helm path instead of ad hoc local demos

Project links:

- [Documentation](https://danieltse.org/mcp-zap-server/)
- [Quick Start Security Guide](./QUICK_START_SECURITY.md)
- [Contributing](./CONTRIBUTING.md)
- [Discussions](https://github.com/dtkmn/mcp-zap-server/discussions)
- [Security Policy](./SECURITY.md)

## Start Here

1. Copy `.env.example` to `.env`.
2. Run `docker compose up -d`.
3. Open `http://localhost:3000` for the bundled Open WebUI path, or connect your MCP client to `http://localhost:7456/mcp`.
4. Use the [Authentication Quick Start](https://danieltse.org/mcp-zap-server/getting-started/authentication-quick-start/) and [Client Configuration Guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/) if you are wiring a remote client.

## Latest Release (`v0.7.0`)

- Adds Policy Bundle v1 dry-run and runtime enforcement support, including the new `zap_policy_dry_run` tool.
- Adds guided auth session bootstrap for form-login, bearer-token, and API-key flows with credential reference allowlisting.
- Adds a scan history ledger with list, get, and export tools for release evidence and operations.
- Hardens MCP request body limits, Helm HA affinity validation, MCP/ZAP egress boundaries, Docker bind defaults, and guided auth secret handling.

See [RELEASE_NOTES_0.7.0.md](./RELEASE_NOTES_0.7.0.md) and [CHANGELOG.md](./CHANGELOG.md) for the full upgrade and change details.

## 📚 Documentation

**[📖 View Full Documentation](https://danieltse.org/mcp-zap-server/)** - Complete guides, API reference, and examples

### Quick Links
- [Security & Authentication Guide](https://danieltse.org/mcp-zap-server/security-modes/) - Three security modes
- [Client Configuration Guide](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/) - Open WebUI, Cursor, Claude Desktop, and other MCP clients
- [JWT Authentication Setup](https://danieltse.org/mcp-zap-server/security-modes/jwt-authentication/) - Production-ready auth
- [Open WebUI Setup](#choose-your-client) - Bundled browser UX for local or remote model providers
- [Production Readiness Checklist](https://danieltse.org/mcp-zap-server/operations/production-checklist/) - Cloud and public rollout baseline
- [Authenticated Scanning Best Practices](https://danieltse.org/mcp-zap-server/scanning/authenticated-scanning-best-practices/) - Context/user/authenticated scan workflow
- [AJAX Spider Guide](https://danieltse.org/mcp-zap-server/scanning/ajax-spider/) - Browser-backed crawling for JavaScript-heavy or authenticated applications
- [Queue Retry Policy](https://danieltse.org/mcp-zap-server/operations/scan-queue-retry-policy/) - Default retry/backoff by scan type
- [Queue Coordinator and Worker Claims](https://danieltse.org/mcp-zap-server/operations/queue-coordinator-leader-election/) - Claim-based HA queue recovery and optional coordinator signals
- [Scan History Ledger](https://danieltse.org/mcp-zap-server/operations/scan-history-ledger/) - Queryable scan, queue, and report evidence
- [Local HA Compose Simulation](https://danieltse.org/mcp-zap-server/operations/local-ha-compose/) - Run 3 MCP replicas with shared Postgres and a local ingress
- [Kubernetes Deployment](./helm/README.md) - Helm charts for production


### Demo on Cursor
**[📺 Watch Demo Video](https://danieltse.org/mcp-zap-server/demo.html)** | [YouTube Link](https://www.youtube.com/watch?v=9_9VqsL0lNw)

<a href="https://www.youtube.com/watch?v=9_9VqsL0lNw" target="_blank" rel="noopener noreferrer">
<img src="https://img.youtube.com/vi/9_9VqsL0lNw/0.jpg" alt="▶️ Watch the demo">
</a>

## Table of Contents
- [Start Here](#start-here)
- [Features](#features)
- [Choose Your Client](#choose-your-client)
- [Architecture](#architecture)
- [Queue HA Design](#queue-ha-design)
- [Cloud Setup (AWS Example)](#cloud-setup-aws-example)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
    - [Set Up Custom OpenAI / Ollama API Connection](#set-up-custom-openai--ollama-api-connection)
    - [Bundled MCP Connection](#bundled-mcp-connection)
- [Services Overview](#services-overview)
- [Manual build](#manual-build)
- [Usage with Claude Desktop, Cursor, Windsurf or any MCP-compatible AI agent](#usage-with-claude-desktop-cursor-windsurf-or-any-mcp-compatible-ai-agent)
    - [Streamable HTTP mode](#streamable-http-mode)
- [Prompt Examples](#prompt-examples)


## Features
- **MCP ZAP server**: Exposes ZAP actions as MCP tools. Eliminates manual CLI calls and brittle scripts.
- **Guided + expert tool surfaces**: Start with a smaller intent-first default surface or unlock the full raw workflow controls.
- **OpenAPI integration**: Import remote OpenAPI specs into ZAP and kick off active scans
- **Findings and report workflows**: Generate HTML/JSON reports, drill into findings, and compare findings snapshots across runs.
- **Automation and API schema support**: Run ZAP Automation Framework plans and import OpenAPI, GraphQL, and SOAP/WSDL definitions.
- **Scan queue v1**: Queue active, spider, and AJAX Spider scans with job lifecycle states, retries, dead-letter handling, and optional durable Postgres state.
- **Scan history ledger**: Query queue jobs, direct scan starts, and generated report artifacts for release or handoff evidence.
- **Operational guardrails**: Enforce tool scopes, rate limits, workspace quotas, and structured request correlation for shared deployments.
- **Dockerized**: Runs ZAP and the MCP server in containers, orchestrated via docker-compose
- **Secure**: Configure API keys for both ZAP (ZAP_API_KEY) and the MCP server (MCP_API_KEY)

## Choose Your Client

You can use this project in two ways:

- **Open WebUI + your preferred model provider**: Best for non-technical users who want a browser UI and zero MCP client setup. The bundled `open-webui` service can talk to OpenAI-compatible APIs, Ollama, and other model backends, while the MCP connection to this server is preconfigured for them.
- **Any MCP client you already use**: Best for technical users already working in Cursor, Claude Desktop, VS Code, Windsurf, or another MCP-capable client. Just point that client at the MCP endpoint exposed by this stack.

For the default Docker Compose stack:

- Open WebUI is available at `http://localhost:3000`
- The MCP server is available to host-side clients at `http://localhost:7456/mcp`
- Open WebUI talks to the MCP server internally at `http://mcp-server:7456/mcp`

## Architecture
```mermaid
flowchart LR
  subgraph "DOCKER COMPOSE"
    direction LR
    ZAP["OWASP ZAP (container)"]
    MCPZAP["MCP ZAP Server"]
    Client["Open WebUI (bundled client)"]
    Juice["OWASP Juice-Shop"]
    Petstore["Swagger Petstore Server"]
  end

  MCPZAP <-->|Native MCP over Streamable HTTP| Client
  MCPZAP -->|ZAP REST API| ZAP
  ZAP -->|scan, alerts, reports| MCPZAP

  ZAP -->|spider/active-scan| Juice
  ZAP -->|Import API/active-scan| Petstore
```

## Queue HA Design
High-level behavior for multi-replica deployments:

- All replicas can serve durable read/status requests and queue mutations.
- Any replica can claim queued work from shared durable state.
- Queue dispatch now uses claim-based worker ownership rather than a single required dispatcher leader.
- `single-node` and `postgres-lock` coordinator modes still exist for identity, optional maintenance leadership, and observability.
- Running jobs renew a claim lease; if a worker disappears, another replica can recover polling after lease expiry.

```mermaid
flowchart LR
  Client["Client / MCP Agent"] --> LB["Load Balancer"]
  LB --> R1["Replica A"]
  LB --> R2["Replica B"]

  subgraph Coord["Coordination + State"]
    Lock["Postgres Advisory Lock (Optional Coordinator)"]
    State["Durable Queue State + Job Claims (Postgres)"]
  end

  R1 <-->|lock attempt| Lock
  R2 <-->|lock attempt| Lock
  R1 <-->|read/write queue state| State
  R2 <-->|read/write queue state| State

  R1 -->|"if claim owner: start/poll"| ZAP["OWASP ZAP"]
  R2 -->|"if claim owner: start/poll"| ZAP
```

For configuration and troubleshooting details, see [Queue Coordinator and Worker Claims](https://danieltse.org/mcp-zap-server/operations/queue-coordinator-leader-election/) and [Local HA Compose Simulation](https://danieltse.org/mcp-zap-server/operations/local-ha-compose/).

## Cloud Setup (AWS Example)
Reference deployment pattern for production-like HA:

1. Run 2+ `mcp-zap-server` replicas on EKS (or ECS/Fargate with equivalent networking).
2. Use Amazon RDS PostgreSQL as shared backend for:
   - durable scan jobs (`zap.scan.jobs.store`)
   - leader election advisory lock (`zap.scan.queue.coordinator`)
   - JWT revocation state when JWT mode is enabled
3. Run Flyway-managed shared-schema migrations before MCP replicas scale out.
4. Route traffic through ALB/NLB/Ingress to all replicas with session affinity enabled for streamable MCP traffic.
5. Keep ZAP connectivity private (VPC internal networking/security groups).

Recommended queue-related env settings:

```bash
# Durable scan jobs
ZAP_SCAN_JOBS_STORE_BACKEND=postgres
ZAP_SCAN_JOBS_STORE_POSTGRES_URL=jdbc:postgresql://<rds-endpoint>:5432/<db>
ZAP_SCAN_JOBS_STORE_POSTGRES_USERNAME=<user>
ZAP_SCAN_JOBS_STORE_POSTGRES_PASSWORD=<password>

# Optional coordinator signals for multi-replica deployments
ZAP_SCAN_QUEUE_COORDINATOR_BACKEND=postgres-lock
ZAP_SCAN_QUEUE_COORDINATOR_NODE_ID=<unique-pod-or-task-id>
ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_URL=jdbc:postgresql://<rds-endpoint>:5432/<db>
ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_USERNAME=<user>
ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_PASSWORD=<password>
```

Migration baseline for shared Postgres state:

```bash
DB_MIGRATIONS_ENABLED=true
DB_MIGRATIONS_POSTGRES_URL=jdbc:postgresql://<rds-endpoint>:5432/<db>
DB_MIGRATIONS_POSTGRES_USERNAME=<user>
DB_MIGRATIONS_POSTGRES_PASSWORD=<password>
```

Ingress requirement for streamable MCP:

- MCP streamable HTTP sessions are stateful per replica by default.
- If you expose multiple MCP replicas behind a shared ingress, enable sticky sessions or equivalent client affinity at the ingress/load-balancer layer.
- Without ingress affinity, a client can initialize on one replica and send follow-up MCP requests to another replica that does not have that session.
- The Helm chart now provides OSS/local presets for `aws-nlb` and `ingress-nginx` session affinity under `mcp.streamableHttp.sessionAffinity`.

For the OSS deployment model:

- Use sticky ingress or equivalent client affinity as the pragmatic multi-replica operating model.
- If you need different transport/session behavior, treat that as custom architecture work on top of the OSS baseline rather than the default expectation for this repository.

Helm deployment shortcut (EKS):

```bash
# copy/edit HA values (set RDS endpoint/db placeholders)
cp ./helm/mcp-zap-server/values-ha.yaml /tmp/mcp-zap-values-ha.yaml
$EDITOR /tmp/mcp-zap-values-ha.yaml

# deploy
helm upgrade --install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap-prod --create-namespace \
  --values /tmp/mcp-zap-values-ha.yaml \
  --set zap.config.apiKey="${ZAP_API_KEY}" \
  --set mcp.zapClient.apiKey="${ZAP_API_KEY}" \
  --set mcp.security.apiKey="${MCP_API_KEY}" \
  --set mcp.security.jwt.secret="${JWT_SECRET}"
```

Operational checks after deploy:

1. Confirm the migration job completed successfully before checking MCP rollout.
2. If `postgres-lock` is enabled, confirm one replica reports `mcp.zap.queue.leadership.is_leader=1`.
3. Submit queue jobs and verify claim owner / claim expiry are populated without duplicate scan starts.
4. Terminate a worker pod/task and confirm another replica recovers polling after claim lease expiry.

Detailed coordinator behavior and troubleshooting: [Queue Coordinator and Worker Claims](https://danieltse.org/mcp-zap-server/operations/queue-coordinator-leader-election/).
Helm-specific guide: [helm/mcp-zap-server/README.md](./helm/mcp-zap-server/README.md).
Production baseline: [Production Readiness Checklist](https://danieltse.org/mcp-zap-server/operations/production-checklist/).

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

For the shipped HTTP/server defaults, the base runtime now starts in `api-key` mode. `none` is kept as an explicit dev/test override, such as `docker-compose.dev.yml`.

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
**This is the default mode for the base app/runtime configuration.**

```bash
# .env
MCP_SECURITY_MODE=api-key
MCP_API_KEY=your-secure-api-key-here
```

Simple authentication with a static API key:

```bash
# Initialize a streamable MCP session with X-API-Key
curl -si \
  -H "X-API-Key: your-mcp-api-key" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}'
```

For raw `curl` workflows, capture the returned `Mcp-Session-Id` header and send it on later `tools/list` or `tools/call` requests.

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
# Optional: shared revocation state for multi-replica deployments
JWT_REVOCATION_STORE_BACKEND=postgres
JWT_REVOCATION_STORE_POSTGRES_URL=jdbc:postgresql://postgres:5432/mcp_zap
```

Token-based authentication with automatic expiration:

```bash
# 1. Exchange API key for JWT tokens
curl -X POST http://localhost:7456/auth/token \
  -H "Content-Type: application/json" \
  -d '{"apiKey": "your-mcp-api-key", "clientId": "your-client-id"}'

# 2. Initialize a streamable MCP session with the access token
curl -si \
  -H "Authorization: Bearer YOUR_ACCESS_TOKEN" \
  -H "Accept: application/json,text/event-stream" \
  -H "Content-Type: application/json" \
  http://localhost:7456/mcp \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"curl-test","version":"1.0.0"}}}'

# 3. Refresh when expired (refresh token is one-time use and rotates)
curl -X POST http://localhost:7456/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "YOUR_REFRESH_TOKEN"}'

# 4. Revoke token on logout / incident response
curl -X POST http://localhost:7456/auth/revoke \
  -H "Content-Type: application/json" \
  -d '{"token": "YOUR_ACCESS_OR_REFRESH_TOKEN"}'
```

**CSRF Protection**: Disabled (by design) - API-only server with stateless JWT authentication. See [SECURITY.md](SECURITY.md#csrf-protection---why-its-disabled) for OWASP compliance explanation.

**Advantages**: Tokens expire (1hr access, 7d refresh), token revocation, audit trails  
When `JWT_REVOCATION_STORE_BACKEND=postgres`, revoked tokens are enforced across replicas.
**Use Cases**: Production deployments, public access, compliance requirements

**Note**: JWT mode is backward compatible—clients can still use API keys during migration.

**🔐 MCP Security Posture**: This server tracks the [Model Context Protocol Security Best Practices](https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices). See [SECURITY.md](SECURITY.md#mcp-security-best-practices-compliance) for implemented controls and operator responsibilities.

📚 **Detailed Documentation**:
- [Security Modes Guide](https://danieltse.org/mcp-zap-server/security-modes/) - Complete comparison and migration guide
- [JWT Authentication Guide](https://danieltse.org/mcp-zap-server/security-modes/jwt-authentication/) - JWT implementation details
- [JWT Key Rotation Runbook](https://danieltse.org/mcp-zap-server/security-modes/jwt-key-rotation-runbook/) - Planned/emergency key rotation procedure
- [MCP Client Configuration](https://danieltse.org/mcp-zap-server/getting-started/mcp-client-authentication/) - Client setup for all modes

### URL Security Configuration

By default, the server blocks scanning of:
- Localhost and loopback addresses (127.0.0.0/8, ::1)
- Private network ranges (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16)
- Link-local addresses (169.254.0.0/16)

You can globally enable/disable URL validation guardrails in `.env`:
```bash
# Global toggle for URL validation checks
ZAP_URL_VALIDATION_ENABLED=true
```

When `ZAP_URL_VALIDATION_ENABLED=false`, whitelist/blacklist and private-network checks are skipped.

To enable scanning specific domains, configure the whitelist in `.env`:
```bash
# Allow only specific domains (wildcards supported)
ZAP_URL_WHITELIST=example.com,*.test.com,demo.org
```

**Warning**: Only set `ZAP_URL_VALIDATION_ENABLED=false`, `ZAP_ALLOW_LOCALHOST=true`, or `ZAP_ALLOW_PRIVATE_NETWORKS=true` in isolated, secure environments.

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

Open http://localhost:3000 in your browser, and you should see the Open WebUI interface. The default Compose stack publishes host ports on `127.0.0.1` only. Set `MCP_ZAP_BIND_ADDRESS=0.0.0.0` only when you intentionally expose the stack behind trusted network controls.

### Set Up Custom OpenAI / Ollama API Connection
![Admin-Panel-Open-WebUI](./images/Admin-Panel-Open-WebUI.png)

### Bundled MCP Connection
The bundled Open WebUI container is preconfigured to connect to the local MCP ZAP server on first boot, so end users do not need to add the MCP server manually.

Open WebUI stores its settings in the `open-webui` Docker volume. If you want to reprovision the default connection from scratch, remove that volume before starting the stack again.

### Optional: Manage MCP Servers in Open WebUI
![MCP-Tools-Config-Open-WebUI](./images/MCP-Tools-Config-Open-WebUI.png)

Once your model provider is configured, you can check the [Prompt Examples](#prompt-examples) section to see how to use the MCP ZAP server with your AI agent.

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
- **Image:** `zaproxy/zap-stable`
- **Purpose:** Runs the OWASP ZAP daemon on port 8090.
- **Configuration:**
    - Requires an API key for security, configured via the `ZAP_API_KEY` environment variable.
    - Publishes the host port on `127.0.0.1` by default through `MCP_ZAP_BIND_ADDRESS`.
    - Maps the host directory `${LOCAL_ZAP_WORKPLACE_FOLDER}` to the container path `/zap/wrk`.

#### `open-webui`
- **Image:** ghcr.io/open-webui/open-webui
- **Purpose:** Provides a web interface for managing ZAP and the MCP server.
- **Configuration:**
    - Publishes `http://localhost:3000` on loopback by default.
    - Preconfigures a native MCP connection to `http://mcp-server:7456/mcp` on first boot.
    - Sends the MCP credential as an `X-API-Key` header to the local MCP server.
    - Uses a named volume to persist backend data.

#### `mcp-server`
- **Image:** mcp-zap-server:latest
- **Purpose:** This repo. Acts as the MCP server exposing ZAP actions with API key authentication.
- **Configuration:**
    - Depends on the `zap` service and connects to it using the configured `ZAP_API_KEY`.
    - Requires `MCP_API_KEY` for client authentication (set in `.env` file).
    - Publishes `http://localhost:7456` to host-side MCP clients on loopback by default.
    - Maps the host directory `${LOCAL_ZAP_WORKPLACE_FOLDER}` to `/zap/wrk` to allow file access.
    - Supports configurable scan limits and URL validation policies.
- **Security:**
    - All endpoints (except health checks) require API key authentication.
    - Include API key in requests via `X-API-Key` header or `Authorization: Bearer <token>`.
    - URL validation prevents scanning internal/private networks by default.

#### `juice-shop`
- **Image:** bkimminich/juice-shop
- **Purpose:** Provides a deliberately insecure web application for testing ZAP’s scanning capabilities.
- **Configuration:**
    - Publishes `http://localhost:3001` on loopback by default.

#### `petstore`
- **Image:** swaggerapi/petstore3:1.0.27
- **Purpose:** Runs the Swagger Petstore sample API to demonstrate OpenAPI import and scanning.
- **Configuration:**
    - Publishes `http://localhost:3002` on loopback by default.


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
If this URL points at a shared ingress in front of multiple replicas, that ingress must provide sticky sessions or equivalent client affinity.

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

Or using a JWT access token:

```json
{
  "mcpServers": {
    "zap-mcp-server": {
      "protocol": "mcp",
      "transport": "streamable-http",
      "url": "http://localhost:7456/mcp",
      "headers": {
        "Authorization": "Bearer your-jwt-access-token-here"
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

## 💼 Commercial Support

`mcp-zap-server` is the OSS project. If your team wants help adopting it in production, **Agentic Lab** offers optional commercial services around this repository:

* **🚀 Deployment Help**: Custom Kubernetes/Helm configurations, HA rollouts, and air-gapped environment support.
* **🔐 Complex Authentication**: Assistance with custom login flows such as OAuth2, 2FA, and SSO.
* **🛠️ CI/CD Quality Gates**: Automated blocking rules for GitHub Actions, GitLab CI, and Jenkins.
* **📊 Reporting & Integration**: Custom reporting pipelines, compliance mapping, and workflow integration.

**[👉 Contact Agentic Lab](mailto:agentic.lab.au@gmail.com?subject=Inquiry:%20MCP%20ZAP%20Server%20Commercial%20Support)**
