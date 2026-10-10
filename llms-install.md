# Install MCP ZAP Server For Agentic MCP Clients

Use Docker Compose for normal installs. This project is a streamable HTTP MCP server that runs with a ZAP sidecar; do not install it as a stdio-only MCP package.

The versioned image example targets `v0.15.0`. Before using it, confirm that
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and the
corresponding release workflow show successful publication to your registry.
Release preparation and a merge to `main` do not publish an image.

## What This Server Does

MCP ZAP Server lets MCP clients drive ZAP through guided, operator-controlled security workflows:

- traditional Spider, AJAX Spider, Client Spider, active scan, passive scan, API import, findings, reports, and scan history tools
- API-key or JWT authentication for the MCP endpoint
- tool-scope authorization, runtime policy controls, rate limits, request limits, audit events, and metrics
- Docker Compose for local/self-hosted use and Helm for Kubernetes deployments

## Install Locally

Follow [Self-Serve First Run](docs/getting-started/SELF_SERVE_FIRST_RUN.md)
for prerequisites, the supported `./bin/bootstrap-local.sh --start` path,
version selection, restart, and recovery. The stack does not bundle an MCP
client; configure the user's chosen client separately.

Before assisting with setup:

- Download images, run local containers, and edit client settings within the
  user's authorized setup scope. Preserve unrelated settings and connections;
  obtain permission for changes beyond that scope.
- Inspect the existing setup before replacing it. Preserve `.env`, credentials,
  workspace contents, and the user's selected version. Do not use bootstrap
  `--force` for recovery; it resets settings and rotates API keys.
- Keep credentials in private local settings. Never print API keys, tokens,
  complete credential-bearing configs, or private logs into the conversation.
- Report readiness only after the startup checks succeed and the client can
  discover tools. If blocked, use the existing guide's diagnostics and report
  the failing step instead of treating installation as a working connection.

For source builds, use [contributor setup](CONTRIBUTING.md#local-stack).

## MCP Client Configuration

Use Streamable HTTP with the `X-API-Key` header from `MCP_API_KEY` in `.env`.
Follow the [client compatibility and setup guide](docs/src/content/docs/getting-started/mcp-client-authentication.md)
for the user's client and its validation status. Do not assume one client's
configuration schema or environment-variable syntax works in another client.
The default `http://localhost:7456/mcp` endpoint requires a client running on
the same host as Compose; web-only clients cannot reach that loopback address.

## Standalone OCI Image With External ZAP

Use this path only when you already run, or are willing to run, ZAP separately. Marketplace and registry clients do not automatically start the ZAP sidecar for this package.

Create a shared network and report workspace volume, then initialize the volume for the standard ZAP container UID/GID:

```bash
docker network create mcp-zap-network
docker volume create mcp-zap-wrk
export ZAP_API_KEY="$(openssl rand -hex 32)"
export MCP_API_KEY="$(openssl rand -hex 32)"

docker run --rm \
  --user root \
  -v mcp-zap-wrk:/zap/wrk \
  zaproxy/zap-stable:2.17.0 \
  sh -c 'mkdir -p /zap/wrk && chown -R 1000:1000 /zap/wrk && chmod -R u+rwX,g+rwX /zap/wrk'
```

Start ZAP with the same report workspace mounted:

```bash
docker run -d \
  --name mcp-zap-zap \
  --network mcp-zap-network \
  -v mcp-zap-wrk:/zap/wrk \
  -e ZAP_API_KEY="$ZAP_API_KEY" \
  zaproxy/zap-stable:2.17.0 \
  zap.sh -daemon -host 0.0.0.0 -port 8090 \
  -config "api.key=$ZAP_API_KEY" \
  -config "api.addrs.addr.name=.*" \
  -config "api.addrs.addr.regex=true"
```

Then start the MCP server image:

```bash
docker run -d \
  --name mcp-zap-server \
  --network mcp-zap-network \
  --user 1000:1000 \
  -p 127.0.0.1:7456:7456 \
  -v mcp-zap-wrk:/zap/wrk \
  -e ZAP_API_URL=mcp-zap-zap \
  -e ZAP_API_PORT=8090 \
  -e ZAP_API_KEY="$ZAP_API_KEY" \
  -e MCP_SECURITY_MODE=api-key \
  -e MCP_SECURITY_ENABLED=true \
  -e MCP_SECURITY_ALLOW_PLACEHOLDER_API_KEY=false \
  -e MCP_API_KEY="$MCP_API_KEY" \
  ghcr.io/dtkmn/mcp-zap-server:v0.15.0
```

Check the MCP server:

```bash
curl http://127.0.0.1:7456/actuator/health
```

Configure the MCP client to use:

```text
http://localhost:7456/mcp
```

and send:

```text
X-API-Key: $MCP_API_KEY
```

The `mcp-zap-wrk:/zap/wrk` volume must be mounted into both containers. ZAP writes report artifacts there, and the MCP server reads those same paths back for report and evidence-handoff tools. The MCP container is run as UID/GID `1000:1000` to match the standard `zaproxy/zap-stable` container user, so report directories remain writable by both containers.

This default guided standalone path has been smoke-tested with MCP `initialize`, `tools/list`, `zap_passive_scan_status`, `zap_report_generate`, and `zap_report_read` against a separate ZAP container. Keep `MCP_SERVER_TOOLS_SURFACE=guided` for normal report readback. Use `-e MCP_SERVER_TOOLS_SURFACE=expert` only when you need lower-level ZAP tools outside the guided surface.

## Safe First Test

Use the bundled demo workflow in [Self-Serve First Run](docs/getting-started/SELF_SERVE_FIRST_RUN.md)
after tool discovery succeeds. For Compose targets, use the container URL
reachable by ZAP, not the host browser preview. Do not run active scans without
the user's explicit authorization for the target and scan scope. A failed
scan or report retrieval is not a clean security result.

## Important Safety Notes

- Only scan systems you own or are explicitly authorized to test.
- Keep the default loopback binding unless you are deliberately deploying behind trusted network controls.
- Keep `MCP_SECURITY_MODE=api-key` or `jwt`; use `none` only for isolated local development.
- This repository does not currently ship a first-party Claude Desktop OAuth connector or stdio proxy helper.

More documentation:

- [Full documentation](https://danieltse.org/mcp-zap-server/)
- [Quick Start Security](QUICK_START_SECURITY.md)
- [Production Readiness Checklist](docs/src/content/docs/operations/production-checklist.md)
