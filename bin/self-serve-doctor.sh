#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ENV_FILE="${REPO_ROOT}/.env"
SERVER_URL="http://localhost:7456/mcp"
HEALTH_URL="http://localhost:7456/actuator/health"
ZAP_URL="http://localhost:8090/"
OPEN_WEBUI_HEALTH_URL="http://localhost:3000/health"
MCP_API_KEY="${MCP_API_KEY:-}"
MCP_PROTOCOL_VERSION="${MCP_PROTOCOL_VERSION:-2025-11-25}"
SKIP_DOCKER=0
SKIP_TOOL_CALL=0
SKIP_ZAP=0

FAILURES=()

usage() {
  cat <<'EOF'
Usage: ./bin/self-serve-doctor.sh [options]

Checks the local self-serve API-key path end-to-end:
- local .env / API key presence
- Docker and Compose availability
- MCP and ZAP reachability
- MCP initialize + tools/list
- guided scan, report, and evidence tool visibility
- one harmless tool call (zap_passive_scan_status)

Options:
  --env-file PATH      Read MCP_API_KEY from a different env file.
  --api-key VALUE      Override MCP_API_KEY instead of reading it from env.
  --server-url URL     MCP endpoint URL. Default: http://localhost:7456/mcp
  --health-url URL     MCP health URL. Default: http://localhost:7456/actuator/health
  --zap-url URL        ZAP URL to probe. Default: http://localhost:8090/
  --protocol-version VERSION
                       MCP protocol version to negotiate. Default: 2025-11-25.
  --skip-docker        Skip Docker/Compose and local service checks.
  --skip-zap           Skip the ZAP reachability probe.
  --skip-tool-call     Skip the harmless zap_passive_scan_status probe.
  --help, -h           Show this help message.
EOF
}

pass() {
  printf 'PASS %s\n' "$1"
}

note() {
  printf 'NOTE %s\n' "$1"
}

fail() {
  printf 'FAIL %s\n' "$1" >&2
  FAILURES+=("$1")
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "Missing required command: $1"
    return 1
  fi
}

require_tool_in_list() {
  local tool="$1"
  local body_file="$2"

  if grep -Eq "\"name\"[[:space:]]*:[[:space:]]*\"${tool}\"" "${body_file}"; then
    pass "Guided tool available: ${tool}"
  else
    fail "Guided tool missing from tools/list: ${tool}"
  fi
}

trim_quotes() {
  local value="$1"
  if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then
    value="${value:1:${#value}-2}"
  elif [[ "${value}" == \'*\' && "${value}" == *\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "${value}"
}

read_env_value() {
  local key="$1"
  local file="$2"
  local value

  if [[ ! -f "${file}" ]]; then
    return 1
  fi

  value="$(sed -n "s/^${key}=//p" "${file}" | tail -n 1)"
  if [[ -z "${value}" ]]; then
    return 1
  fi

  trim_quotes "${value}"
}

post_json() {
  local url="$1"
  local payload="$2"
  local headers_file="$3"
  local body_file="$4"
  shift 4
  local -a args

  args=(
    curl
    -sS
    -D "${headers_file}"
    -o "${body_file}"
    -w "%{http_code}"
    -H "Accept: application/json,text/event-stream"
    -H "Content-Type: application/json"
  )

  while [[ $# -gt 0 ]]; do
    args+=(-H "$1")
    shift
  done

  args+=("${url}" -d "${payload}")
  "${args[@]}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      if [[ $# -lt 2 ]]; then
        echo "--env-file requires a value" >&2
        exit 1
      fi
      ENV_FILE="$2"
      shift 2
      ;;
    --api-key)
      if [[ $# -lt 2 ]]; then
        echo "--api-key requires a value" >&2
        exit 1
      fi
      MCP_API_KEY="$2"
      shift 2
      ;;
    --server-url)
      if [[ $# -lt 2 ]]; then
        echo "--server-url requires a value" >&2
        exit 1
      fi
      SERVER_URL="$2"
      shift 2
      ;;
    --health-url)
      if [[ $# -lt 2 ]]; then
        echo "--health-url requires a value" >&2
        exit 1
      fi
      HEALTH_URL="$2"
      shift 2
      ;;
    --zap-url)
      if [[ $# -lt 2 ]]; then
        echo "--zap-url requires a value" >&2
        exit 1
      fi
      ZAP_URL="$2"
      shift 2
      ;;
    --protocol-version)
      if [[ $# -lt 2 ]]; then
        echo "--protocol-version requires a value" >&2
        exit 1
      fi
      MCP_PROTOCOL_VERSION="$2"
      shift 2
      ;;
    --skip-docker)
      SKIP_DOCKER=1
      shift
      ;;
    --skip-zap)
      SKIP_ZAP=1
      shift
      ;;
    --skip-tool-call)
      SKIP_TOOL_CALL=1
      shift
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

require_command curl || true
require_command awk || true
require_command sed || true
require_command grep || true

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  require_command docker || true
fi

if [[ ${#FAILURES[@]} -gt 0 ]]; then
  printf '\nDoctor could not run because required commands are missing.\n' >&2
  exit 1
fi

if [[ -z "${MCP_API_KEY}" ]]; then
  if [[ -f "${ENV_FILE}" ]]; then
    MCP_API_KEY="$(read_env_value "MCP_API_KEY" "${ENV_FILE}" || true)"
  fi
fi

if [[ -f "${ENV_FILE}" ]]; then
  pass "Environment file found at ${ENV_FILE}"
else
  fail "Missing ${ENV_FILE}. Run ./bin/bootstrap-local.sh first."
fi

if [[ -n "${MCP_API_KEY}" ]]; then
  pass "MCP_API_KEY is available for the self-serve API-key path"
else
  fail "MCP_API_KEY is missing. Set it in ${ENV_FILE} or pass --api-key."
fi

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  if docker info >/dev/null 2>&1; then
    pass "Docker daemon is reachable"
  else
    fail "Docker daemon is not available. Start Docker and retry."
  fi

  if docker compose version >/dev/null 2>&1; then
    pass "docker compose is available"
  else
    fail "docker compose is not available."
  fi

  if [[ ${#FAILURES[@]} -eq 0 ]]; then
    running_services="$(docker compose ps --services --status running 2>/dev/null || true)"
    if grep -qx 'mcp-server' <<<"${running_services}"; then
      pass "Local mcp-server container is running"
    else
      fail "Local mcp-server container is not running. Start it with ./dev.sh."
    fi

    if grep -qx 'zap' <<<"${running_services}"; then
      pass "Local zap container is running"
    else
      fail "Local zap container is not running. Start it with ./dev.sh."
    fi

    if grep -qx 'open-webui' <<<"${running_services}"; then
      if curl -fsS "${OPEN_WEBUI_HEALTH_URL}" >/dev/null 2>&1; then
        pass "Open WebUI container is running"
      else
        note "Open WebUI container is running but its health endpoint is not ready yet"
      fi
    else
      note "Open WebUI is not running. The default Compose stack normally includes it for the bundled browser client."
    fi
  fi
fi

mcp_reachable=0
if curl -fsS "${HEALTH_URL}" | grep -q '"status":"UP"'; then
  pass "MCP health endpoint is UP at ${HEALTH_URL}"
  mcp_reachable=1
else
  fail "MCP health endpoint is not ready at ${HEALTH_URL}. Start the stack with ./dev.sh."
fi

if [[ "${SKIP_ZAP}" -eq 0 ]]; then
  if curl -fsS "${ZAP_URL}" >/dev/null 2>&1; then
    pass "ZAP is reachable at ${ZAP_URL}"
  else
    fail "ZAP is not reachable at ${ZAP_URL}."
  fi
fi

session_id=""
negotiated_protocol_version=""
if [[ "${mcp_reachable}" -eq 1 && -n "${MCP_API_KEY}" ]]; then
  headers_file="$(mktemp)"
  body_file="$(mktemp)"
  trap 'rm -f "${headers_file:-}" "${body_file:-}" "${list_headers_file:-}" "${list_body_file:-}" "${tool_headers_file:-}" "${tool_body_file:-}"' EXIT

  init_payload="{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"${MCP_PROTOCOL_VERSION}\",\"capabilities\":{\"roots\":{\"listChanged\":true},\"sampling\":{},\"elicitation\":{\"form\":{},\"url\":{}}},\"clientInfo\":{\"name\":\"self-serve-doctor\",\"version\":\"1.0.0\"}}}"
  http_code="$(post_json "${SERVER_URL}" "${init_payload}" "${headers_file}" "${body_file}" "X-API-Key: ${MCP_API_KEY}")"

  if [[ "${http_code}" == "200" ]]; then
    session_id="$(awk -F': ' 'tolower($1)=="mcp-session-id" {gsub("\r", "", $2); print $2}' "${headers_file}")"
    negotiated_protocol_version="$(grep -o '"protocolVersion"[[:space:]]*:[[:space:]]*"[^"]*"' "${body_file}" | head -n 1 | sed 's/.*"protocolVersion"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/' || true)"
    if [[ -n "${session_id}" ]]; then
      pass "MCP initialize succeeded and returned a session id"
    else
      fail "MCP initialize succeeded but did not return Mcp-Session-Id."
    fi
    if [[ "${negotiated_protocol_version}" == "${MCP_PROTOCOL_VERSION}" ]]; then
      pass "MCP negotiated protocol ${negotiated_protocol_version}"
    elif [[ -n "${negotiated_protocol_version}" ]]; then
      fail "MCP negotiated protocol ${negotiated_protocol_version}, expected ${MCP_PROTOCOL_VERSION}. Upgrade the MCP/Spring AI transport before release."
    else
      fail "MCP initialize response did not include a protocolVersion."
    fi
  else
    response_body="$(cat "${body_file}")"
    fail "MCP initialize failed with HTTP ${http_code}. Response: ${response_body}"
  fi

  if [[ -n "${session_id}" ]]; then
    follow_up_protocol_version="${negotiated_protocol_version:-${MCP_PROTOCOL_VERSION}}"
    list_headers_file="$(mktemp)"
    list_body_file="$(mktemp)"
    list_payload='{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
    http_code="$(post_json "${SERVER_URL}" "${list_payload}" "${list_headers_file}" "${list_body_file}" "X-API-Key: ${MCP_API_KEY}" "Mcp-Session-Id: ${session_id}" "MCP-Protocol-Version: ${follow_up_protocol_version}")"

    if [[ "${http_code}" == "200" ]] && grep -q '"result"' "${list_body_file}" && ! grep -q 'NullPointerException' "${list_body_file}"; then
      pass "tools/list succeeded through the real MCP endpoint"
      required_guided_tools=(
        zap_crawl_start
        zap_crawl_status
        zap_passive_scan_wait
        zap_passive_scan_status
        zap_findings_summary
        zap_report_generate
        zap_report_read
        zap_scan_history_release_evidence
        zap_scan_history_customer_handoff
      )
      for tool in "${required_guided_tools[@]}"; do
        require_tool_in_list "${tool}" "${list_body_file}"
      done
    else
      response_body="$(cat "${list_body_file}")"
      fail "tools/list failed with HTTP ${http_code}. Response: ${response_body}"
    fi

    if [[ "${SKIP_TOOL_CALL}" -eq 0 ]]; then
      tool_headers_file="$(mktemp)"
      tool_body_file="$(mktemp)"
      tool_payload='{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"zap_passive_scan_status","arguments":{}}}'
      http_code="$(post_json "${SERVER_URL}" "${tool_payload}" "${tool_headers_file}" "${tool_body_file}" "X-API-Key: ${MCP_API_KEY}" "Mcp-Session-Id: ${session_id}" "MCP-Protocol-Version: ${follow_up_protocol_version}")"

      if [[ "${http_code}" == "200" ]] && ! grep -q '"error"' "${tool_body_file}" && ! grep -q 'NullPointerException' "${tool_body_file}"; then
        pass "zap_passive_scan_status succeeded as a harmless tool probe"
      else
        response_body="$(cat "${tool_body_file}")"
        fail "zap_passive_scan_status failed with HTTP ${http_code}. Response: ${response_body}"
      fi
    fi
  fi
fi

if [[ ${#FAILURES[@]} -gt 0 ]]; then
  printf '\nDoctor found %d blocking issue(s).\n' "${#FAILURES[@]}" >&2
  exit 1
fi

cat <<'EOF'

Self-serve API-key path looks healthy.

Next steps:
- Cursor: copy examples/cursor/mcp.json into your Cursor MCP config and keep MCP_API_KEY in your shell environment.
- Open WebUI: open http://localhost:3000 when the default stack is running.
- First-run guide: docs/getting-started/SELF_SERVE_FIRST_RUN.md
- Published docs route: docs/src/content/docs/getting-started/self-serve-first-run.md
EOF
