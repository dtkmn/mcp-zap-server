#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ENV_EXAMPLE="${REPO_ROOT}/.env.example"
ENV_FILE="${REPO_ROOT}/.env"
WORKSPACE_DIR="${REPO_ROOT}/zap-workplace"
FORCE=0
START=0
WORKSPACE_SET=0
WAIT_TIMEOUT=300

usage() {
  cat <<'EOF'
Usage: ./bin/bootstrap-local.sh [--start] [--wait-timeout SECONDS]
                                [--force] [--workspace /absolute/path]

Prepare private .env settings and workspace directories for local Docker use.
New settings use generated MCP/ZAP API keys, disable JWT, and allow the bundled
localhost/private-network demo targets.

Existing settings and keys are preserved. Use --start to download missing
release images, start the stack without building source, wait for container
health, and verify authenticated MCP readiness. The health wait defaults to
300 seconds; --wait-timeout accepts 1 through 900 seconds.

--force deliberately replaces .env and rotates both keys. It is not a restart
or repair option. --workspace applies when creating or resetting settings.
EOF
}

start_stack() {
  require_command docker
  if ! docker info >/dev/null 2>&1; then
    echo "Docker is not running. Start Docker and retry --start." >&2
    return 1
  fi
  if ! docker compose up --help | grep -- '--wait-timeout' >/dev/null; then
    echo "Update Docker Compose to a version supporting --wait and --wait-timeout." >&2
    return 1
  fi

  local -a compose
  local images mcp_image
  compose=(docker compose --project-directory "$REPO_ROOT" --env-file "$ENV_FILE" -f "$REPO_ROOT/docker-compose.yml")
  if ! images="$("${compose[@]}" config --images)"; then
    echo "Local Compose settings are invalid. Correct .env and retry; existing settings were preserved." >&2
    return 1
  fi
  mcp_image="$(printf '%s\n' "$images" | sed -n '/^dtkmn\/mcp-zap-server:/p')"
  if [[ ! "$mcp_image" =~ ^dtkmn/mcp-zap-server:v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Select a published version such as IMAGE_TAG=v0.15.0 in .env; --start does not use latest or development images." >&2
    return 1
  fi

  printf 'Starting the configured release (%s); only missing images will be downloaded.\n' "$mcp_image"
  if ! "${compose[@]}" up -d --pull missing --no-build --wait --wait-timeout "$WAIT_TIMEOUT"; then
    echo "Startup failed or timed out. Containers were retained for diagnosis; inspect docker compose ps and private service logs." >&2
    return 1
  fi

  if ! "$SCRIPT_DIR/self-serve-doctor.sh" --env-file "$ENV_FILE"; then
    echo "The containers started, but authenticated MCP readiness failed. Resolve the doctor failure before connecting your client." >&2
    return 1
  fi
  echo "Local release stack is ready."
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start)
      START=1
      shift
      ;;
    --wait-timeout)
      if [[ $# -lt 2 || ! "$2" =~ ^[1-9][0-9]{0,2}$ ]] || (( $2 > 900 )); then
        echo "--wait-timeout requires a whole number from 1 through 900." >&2
        exit 1
      fi
      WAIT_TIMEOUT="$2"
      shift 2
      ;;
    --force)
      FORCE=1
      shift
      ;;
    --workspace)
      if [[ $# -lt 2 || -z "$2" || "$2" == --* || "$2" == *$'\n'* || "$2" == *$'\r'* ]]; then
        echo "--workspace requires a path" >&2
        exit 1
      fi
      WORKSPACE_DIR="$2"
      WORKSPACE_SET=1
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

if [[ -e "$ENV_FILE" && ! -f "$ENV_FILE" ]]; then
  echo ".env must be a regular file. Resolve the conflicting path before setup." >&2
  exit 1
fi

if [[ -f "$ENV_FILE" && "$FORCE" -ne 1 ]]; then
  if [[ "$WORKSPACE_SET" -eq 1 ]]; then
    echo "Existing workspace settings are preserved. --workspace applies only when creating settings or explicitly using --force." >&2
    exit 1
  fi
  echo "Existing .env, keys and workspace settings are preserved."
else
  require_command openssl
  if [[ ! -f "$ENV_EXAMPLE" ]]; then
    echo "Could not find $ENV_EXAMPLE" >&2
    exit 1
  fi
  if ! zap_key="$(openssl rand -hex 32)" || ! mcp_key="$(openssl rand -hex 32)"; then
    echo "Could not generate local API keys. Existing settings were preserved." >&2
    exit 1
  fi
  mkdir -p "${WORKSPACE_DIR}/zap-wrk" "${WORKSPACE_DIR}/zap-home"
  WORKSPACE_DIR="$(cd "$WORKSPACE_DIR" && pwd)"
  settings_file="$(mktemp "${ENV_FILE}.tmp.XXXXXX")"
  trap 'rm -f "${settings_file:-}"' EXIT
  # A single-quoted dotenv value keeps spaces, dollars and backslashes literal.
  workspace_value="'${WORKSPACE_DIR//\'/\\\'}'"
  BOOTSTRAP_ZAP_KEY="$zap_key" BOOTSTRAP_MCP_KEY="$mcp_key" BOOTSTRAP_WORKSPACE="$workspace_value" awk '
    BEGIN {
      settings["ZAP_API_KEY"]=ENVIRON["BOOTSTRAP_ZAP_KEY"]
      settings["MCP_API_KEY"]=ENVIRON["BOOTSTRAP_MCP_KEY"]
      settings["LOCAL_ZAP_WORKPLACE_FOLDER"]=ENVIRON["BOOTSTRAP_WORKSPACE"]
      settings["MCP_SECURITY_MODE"]="api-key"
      settings["MCP_SECURITY_ENABLED"]="true"
      settings["MCP_SECURITY_ALLOW_PLACEHOLDER_API_KEY"]="false"
      settings["JWT_ENABLED"]="false"
      settings["ZAP_ALLOW_LOCALHOST"]="true"
      settings["ZAP_ALLOW_PRIVATE_NETWORKS"]="true"
    }
    {
      key=substr($0, 1, index($0, "=")-1)
      if (key in settings) {
        print key "=" settings[key]
        found[key]=1
      } else {
        print
      }
    }
    END { for (key in settings) if (!(key in found)) print key "=" settings[key] }
  ' "$ENV_EXAMPLE" > "$settings_file"
  chmod 600 "$settings_file"
  mv -f "$settings_file" "$ENV_FILE"
  settings_file=""

  printf 'Local settings created at %s; workspace prepared at %s.\n' "$ENV_FILE" "$WORKSPACE_DIR"
fi

if [[ "$START" -eq 1 ]]; then
  start_stack
else
  echo "Settings are ready. Start the released stack with ./bin/bootstrap-local.sh --start."
fi
