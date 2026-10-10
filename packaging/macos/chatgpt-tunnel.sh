#!/bin/bash
set -euo pipefail
umask 077

die() { printf '%s\n' "$*" >&2; exit 1; }
usage() {
  cat <<'EOF'
Usage: ./bin/mcp-zap tunnel init TUNNEL_ID [--replace]
       ./bin/mcp-zap tunnel doctor
       ./bin/mcp-zap tunnel run

Uses the full OpenAI tunnel-client installed separately with:
  brew install openai/tools/tunnel-client

Keep OPENAI_API_KEY or CONTROL_PLANE_API_KEY in the launching environment.
The key needs Tunnels Read + Use for an existing tunnel. No key is saved here.
init preserves an existing profile unless --replace is explicitly supplied.
run stays in the foreground; Ctrl-C disconnects this tunnel client.
EOF
}

action="${1:-help}"
shift || true
replace=0
case "${action}" in
  help|--help|-h) usage; exit 0 ;;
  init)
    [[ $# == 1 || ( $# == 2 && "$2" == --replace ) ]] || { usage; exit 1; }
    tunnel_id="$1"
    [[ "${tunnel_id}" =~ ^tunnel_[0-9a-f]{32}$ ]] || die "Use the complete tunnel ID from Platform tunnel settings."
    [[ $# == 1 ]] || replace=1
    ;;
  doctor|run) [[ $# == 0 ]] || { usage; exit 1; } ;;
  *) usage; die "Unknown tunnel command." ;;
esac

[[ -n "${MCP_ZAP_DATA_DIR:-}" && "${MCP_ZAP_DATA_DIR}" == /* && "${MCP_ZAP_DATA_DIR}" != / && "${MCP_ZAP_DATA_DIR}" != *$'\n'* ]] || die "Use the package launcher with an absolute application data directory."
[[ -d "${MCP_ZAP_DATA_DIR}" && ! -L "${MCP_ZAP_DATA_DIR}" && -O "${MCP_ZAP_DATA_DIR}" ]] || die "Application storage must be an existing directory owned by the current user."
[[ -n "${MCP_API_KEY:-}" ]] || die "The package launcher must supply the local MCP API key. Run start first."
[[ "${MCP_ZAP_SERVER_URL:-}" =~ ^http://127\.0\.0\.1:([1-9][0-9]{0,4})/mcp$ ]] || die "The packaged MCP endpoint must use HTTP on 127.0.0.1 with the /mcp path."
[[ "${BASH_REMATCH[1]}" -le 65535 ]] || die "The packaged MCP endpoint has an invalid port."

# A stable process-only reference lets the caller switch between an existing
# OpenAI key and a dedicated runtime key without rewriting the saved profile.
MCP_ZAP_TUNNEL_API_KEY="${CONTROL_PLANE_API_KEY:-${OPENAI_API_KEY:-}}"
[[ -n "${MCP_ZAP_TUNNEL_API_KEY}" ]] || die "Set OPENAI_API_KEY or CONTROL_PLANE_API_KEY privately in the launching environment. Do not pass a key as an argument."
export MCP_ZAP_TUNNEL_API_KEY
key_ref=env:MCP_ZAP_TUNNEL_API_KEY

client="${MCP_ZAP_TUNNEL_CLIENT:-tunnel-client}"
command -v "${client}" >/dev/null 2>&1 || die "Install the full OpenAI tunnel-client: brew install openai/tools/tunnel-client"
tunnel_dir="${MCP_ZAP_DATA_DIR%/}/tunnel"
profile="${tunnel_dir}/chatgpt-local.yaml"
[[ ! -L "${tunnel_dir}" && ( ! -e "${tunnel_dir}" || -d "${tunnel_dir}" ) ]] || die "Refusing linked or invalid tunnel storage."
[[ ! -L "${profile}" && ( ! -e "${profile}" || -f "${profile}" ) ]] || die "Refusing linked or invalid tunnel profile."
mkdir -p "${tunnel_dir}"
[[ -O "${tunnel_dir}" ]] || die "Tunnel storage must belong to the current user."
chmod 700 "${tunnel_dir}"

# This app owns its profile and endpoint; unrelated tunnel-client selections
# from the caller's shell must not change the target or create another channel.
unset TUNNEL_CLIENT_CONFIG TUNNEL_CLIENT_PROFILE TUNNEL_CLIENT_PROFILE_FILE
unset CONTROL_PLANE_TUNNEL_ID MCP_COMMAND
export MCP_API_KEY

if [[ "${action}" == init ]]; then
  if [[ -e "${profile}" && "${replace}" == 0 ]]; then
    die "Existing tunnel profile preserved. Use tunnel doctor/run, or tunnel init TUNNEL_ID --replace to reconfigure it."
  fi
  init_args=(
    init --sample sample_mcp_remote_no_auth
    --profile chatgpt-local --profile-dir "${tunnel_dir}"
    --tunnel-id "${tunnel_id}" --mcp-server-url "${MCP_ZAP_SERVER_URL}"
    --control-plane-api-key-ref "${key_ref}"
    --health-listen-addr 127.0.0.1:0
  )
  [[ "${replace}" == 0 ]] || init_args+=(--force)
  exec "${client}" "${init_args[@]}"
fi

[[ -f "${profile}" ]] || die "No local tunnel profile. Run tunnel init with an existing tunnel ID first."
[[ ! -L "${tunnel_dir}/health-url" ]] || die "Refusing linked tunnel health state."
runtime_args=(
  "${action}" --profile-file "${profile}"
  --control-plane.api-key "${key_ref}"
  --control-plane.base-url https://api.openai.com
  --control-plane.url-path ""
  --mcp.server-url "${MCP_ZAP_SERVER_URL}"
  --mcp.extra-headers 'X-API-Key: env:MCP_API_KEY'
  --mcp.discovery-extra-headers 'X-API-Key: env:MCP_API_KEY'
  --health.listen-addr 127.0.0.1:0
  --health.url-file "${tunnel_dir}/health-url"
  --log.http-raw-unsafe=false
)
[[ "${action}" != doctor ]] || runtime_args+=(--explain)
exec "${client}" "${runtime_args[@]}"
