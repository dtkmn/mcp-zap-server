#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
ENV_FILE="${REPO_ROOT}/.env"
WORKSPACE_DIR="${REPO_ROOT}/zap-workplace"
FORCE=0

usage() {
  cat <<'EOF'
Usage: ./bin/bootstrap-local.sh [--force] [--workspace /absolute/path]

Creates a local .env for the Docker Compose quick start by:
- generating secure ZAP and MCP API keys
- setting the workspace path
- keeping MCP auth in api-key mode
- disabling JWT by default
- enabling localhost/private-network scanning for bundled local demo targets
- creating the workspace directories used by ZAP
EOF
}

replace_or_append() {
  local key="$1"
  local value="$2"
  local file="$3"
  local tmp

  tmp="$(mktemp)"
  awk -v k="$key" -v v="$value" '
    $0 ~ "^" k "=" {
      print k "=" v
      found=1
      next
    }
    { print }
    END {
      if (!found) {
        print k "=" v
      }
    }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force)
      FORCE=1
      shift
      ;;
    --workspace)
      if [[ $# -lt 2 ]]; then
        echo "--workspace requires a path" >&2
        exit 1
      fi
      WORKSPACE_DIR="$2"
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

require_command openssl

if [[ ! -f "$ENV_EXAMPLE" ]]; then
  echo "Could not find $ENV_EXAMPLE" >&2
  exit 1
fi

if [[ -f "$ENV_FILE" && "$FORCE" -ne 1 ]]; then
  echo ".env already exists at ${ENV_FILE}" >&2
  echo "Re-run with --force to overwrite it." >&2
  exit 1
fi

mkdir -p "${WORKSPACE_DIR}/zap-wrk" "${WORKSPACE_DIR}/zap-home"

cp "$ENV_EXAMPLE" "$ENV_FILE"

replace_or_append "ZAP_API_KEY" "$(openssl rand -hex 32)" "$ENV_FILE"
replace_or_append "MCP_API_KEY" "$(openssl rand -hex 32)" "$ENV_FILE"
replace_or_append "LOCAL_ZAP_WORKPLACE_FOLDER" "$WORKSPACE_DIR" "$ENV_FILE"
replace_or_append "MCP_SECURITY_MODE" "api-key" "$ENV_FILE"
replace_or_append "MCP_SECURITY_ENABLED" "true" "$ENV_FILE"
replace_or_append "MCP_SECURITY_ALLOW_PLACEHOLDER_API_KEY" "false" "$ENV_FILE"
replace_or_append "JWT_ENABLED" "false" "$ENV_FILE"
replace_or_append "ZAP_ALLOW_LOCALHOST" "true" "$ENV_FILE"
replace_or_append "ZAP_ALLOW_PRIVATE_NETWORKS" "true" "$ENV_FILE"

cat <<EOF
Local quick-start environment created:
- .env: ${ENV_FILE}
- workspace: ${WORKSPACE_DIR}

Generated values:
- ZAP_API_KEY
- MCP_API_KEY

Local demo behavior:
- MCP auth mode: api-key
- JWT: disabled
- localhost/private-network scanning: enabled for bundled local targets

Next steps:
1. Start the default self-serve stack with ./dev.sh
2. Run ./bin/self-serve-doctor.sh to verify the API-key MCP path
3. Open the bundled browser client at http://localhost:3000, or connect Cursor with examples/cursor/mcp.json
EOF
