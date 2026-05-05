#!/bin/bash
set -euo pipefail

replicas="${MCP_REPLICAS:-3}"
gateway_port="${HA_MCP_GATEWAY_PORT:-7456}"
project_name="${COMPOSE_PROJECT_NAME:-mcp-zap-server}"
gateway_conf="docker/nginx/mcp-ha.conf"
gateway_template="docker/nginx/mcp-ha.conf.tmpl"

compose() {
  docker compose \
    -f docker-compose.yml \
    -f docker-compose.ha.yml \
    "$@"
}

render_gateway_config() {
  local servers=()
  local server

  while IFS= read -r server; do
    [ -n "${server}" ] && servers+=("${server}")
  done < <(
    docker ps \
      --filter "label=com.docker.compose.project=${project_name}" \
      --filter "label=com.docker.compose.service=mcp-server" \
      --filter "status=running" \
      --format '{{.Names}}' | sort
  )

  if [ "${#servers[@]}" -eq 0 ]; then
    echo "No running mcp-server containers found for project ${project_name}" >&2
    exit 1
  fi

  {
    cat <<'EOF'
map $http_x_forwarded_for $mcp_client_ip {
    "~^(?P<first>[^,]+)" $first;
    default $remote_addr;
}

upstream mcp_backend {
    # Streamable MCP sessions are stored in-memory on each replica, so the
    # ingress must keep a client pinned to one concrete backend for follow-up
    # requests. Explicit upstream entries are required here; hashing against
    # the Docker service alias alone is not stable enough.
    hash $mcp_client_ip$http_user_agent consistent;
EOF
    for server in "${servers[@]}"; do
      printf '    server %s:7456;\n' "${server}"
    done
    cat <<'EOF'
    keepalive 32;
}

server {
    listen 7456;
    server_name _;

    location = /.well-known/oauth-authorization-server {
        return 404;
    }

    location / {
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Connection "";
        proxy_buffering off;
        proxy_request_buffering off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_pass http://mcp_backend;
    }
}
EOF
  } > "${gateway_conf}"

  echo "Rendered gateway config with MCP upstreams:"
  for server in "${servers[@]}"; do
    echo "  - ${server}:7456"
  done
}

ensure_gateway_config() {
  mkdir -p "$(dirname "${gateway_conf}")"
  if [ ! -f "${gateway_conf}" ]; then
    cp "${gateway_template}" "${gateway_conf}"
  fi
}

echo "Starting HA simulation environment"
echo "MCP replicas: ${replicas}"
echo "Gateway port: ${gateway_port}"
echo ""

ensure_gateway_config

compose up -d --build --scale mcp-server="${replicas}"

render_gateway_config

compose restart mcp-gateway

echo ""
echo "HA simulation environment ready"
echo "Services:"
echo "  - MCP Gateway: http://localhost:${gateway_port}"
echo "  - ZAP:         http://localhost:8090"
echo "  - Juice Shop:  http://localhost:3001"
echo "  - Petstore:    http://localhost:3002"
echo ""
echo "Tips:"
echo "  - Check migration logs: docker compose -f docker-compose.yml -f docker-compose.ha.yml logs db-migrate"
echo "  - Check leader logs: docker compose -f docker-compose.yml -f docker-compose.ha.yml logs -f mcp-server"
echo "  - Check gateway logs: docker compose -f docker-compose.yml -f docker-compose.ha.yml logs -f mcp-gateway"
echo "  - Check Postgres:    docker compose exec postgres psql -U \${HA_POSTGRES_USER:-mcp} -d \${HA_POSTGRES_DB:-mcp_zap}"
echo "  - Stop stack:        docker compose -f docker-compose.yml -f docker-compose.ha.yml down"
