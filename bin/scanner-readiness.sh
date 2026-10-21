#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
DIRECTORY="$(cd "$SCRIPT_DIR/.." && pwd -P)"
TIMEOUT_SECONDS=15

usage() {
  cat <<'EOF'
Usage: ./bin/scanner-readiness.sh [--directory /absolute/installation]
                                [--timeout-seconds 1..60]

Read-only preflight for an existing local Docker scanner. Checks the selected
engine, installation ownership, private credentials, container health and MCP
authentication/tool discovery. Does not call scanner tools, start Docker or
containers, download images, or change any settings.

Exit 0: READY. Verify the requested client's connection with one native
zap_passive_scan_status call before beginning its requested scan workflow.
Exit 2: a blocking CODE and recovery guidance. Explain the issue and obtain
approval for any recovery changes; never reset keys or replay a failed scan.
EOF
}

block() { printf '%s: %s\n' "$1" "$2"; exit 2; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --directory)
      [[ $# -ge 2 ]] || block CONFIGURATION_INVALID 'The installation directory is required.'
      DIRECTORY="$2"; shift 2 ;;
    --timeout-seconds)
      [[ $# -ge 2 && "$2" =~ ^[0-9]{1,2}$ ]] || block CONFIGURATION_INVALID 'Docker timeout must be between 1 and 60 seconds.'
      TIMEOUT_SECONDS=$((10#$2))
      (( TIMEOUT_SECONDS >= 1 && TIMEOUT_SECONDS <= 60 )) || block CONFIGURATION_INVALID 'Docker timeout must be between 1 and 60 seconds.'
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) block CONFIGURATION_INVALID 'Unknown argument. Use --help for supported options.' ;;
  esac
done

# A private output file avoids a hung plugin retaining a command-substitution
# pipe. Job-control groups let the watchdog stop Docker and any plugin children.
docker_read() (
  set -m
  output="$(mktemp /tmp/mcp-zap-readiness.XXXXXX)" || exit 1
  child=''; watchdog=''
  trap '[[ -z "$child" ]] || kill -KILL -- "-$child" 2>/dev/null || true; [[ -z "$watchdog" ]] || kill -KILL -- "-$watchdog" 2>/dev/null || true; rm -f "$output"' EXIT
  docker "$@" >"$output" 2>/dev/null &
  child=$!
  (
    sleep "$TIMEOUT_SECONDS"
    kill -TERM -- "-$child" 2>/dev/null || exit 0
    sleep 1
    kill -KILL -- "-$child" 2>/dev/null || true
  ) </dev/null >/dev/null 2>&1 &
  watchdog=$!
  status=0
  wait "$child" 2>/dev/null || status=$?
  # A plugin may outlive its CLI parent or ignore TERM. Clean the entire owned
  # group before cancelling the watchdog, even when the parent exited first.
  kill -KILL -- "-$child" 2>/dev/null || true
  child=''
  kill -KILL -- "-$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
  watchdog=''
  cat "$output"
  exit "$status"
)

[[ "$DIRECTORY" == /* && "$DIRECTORY" != / && "$DIRECTORY" != *$'\n'* && "$DIRECTORY" != *$'\r'* ]] ||
  block CONFIGURATION_INVALID 'Select an absolute, existing scanner installation directory.'
[[ -d "$DIRECTORY" ]] || block INSTALLATION_MISSING 'The selected installation is missing. Locate its existing settings and reports before approving new setup.'
canonical="$(cd "$DIRECTORY" && pwd -P)"
[[ "$canonical" == "$DIRECTORY" ]] || block CONFIGURATION_INVALID 'Select the real installation directory without linked path components.'
[[ -z "${COMPOSE_FILE:-}" ]] || block ENVIRONMENT_CONFLICT 'An inherited COMPOSE_FILE changes the deployment. Review the selected configuration without changing it.'
command -v docker >/dev/null 2>&1 || block DOCKER_MISSING 'Docker is unavailable. Install or locate the existing local Docker engine, then check again.'

# Inspect the selected context before contacting its engine. Never switch it.
endpoint="$(docker_read context inspect --format '{{.Endpoints.docker.Host}}')" ||
  block CHECK_FAILED 'The selected Docker context could not be inspected. Review it without switching contexts automatically.'
if [[ -n "${DOCKER_HOST:-}" && -z "${DOCKER_CONTEXT:-}" ]]; then endpoint="$DOCKER_HOST"; fi
[[ "$endpoint" == unix://* && "$endpoint" != *$'\n'* ]] ||
  block REMOTE_DOCKER_UNSUPPORTED 'The selected engine is not a local Unix-socket engine. Ask the user to choose the intended local Docker context.'
docker_read info >/dev/null || block DOCKER_UNAVAILABLE 'The selected Docker engine is stopped, unreachable or timed out. Ask the user to start it, wait until ready, then check again.'
docker_read compose version >/dev/null || block COMPOSE_MISSING 'Docker Compose is unavailable or timed out. Resolve that prerequisite before attempting recovery.'

inventory="$(docker_read ps -a --no-trunc --format '{{.ID}} {{.Image}}')" ||
  block CHECK_FAILED 'Docker inventory could not be read. No recovery action was taken.'
scanner_ids=()
count=0
while read -r container_id image; do
  [[ -n "$container_id" ]] || continue
  [[ "$container_id" =~ ^[a-f0-9]{12,64}$ && -n "$image" ]] || block CHECK_FAILED 'Docker returned an unsupported inventory. Review it privately.'
  count=$((count + 1))
  [[ "$count" -le 256 ]] || block CHECK_FAILED 'Docker inventory is too large to establish ownership safely.'
  if [[ "$image" =~ ^dtkmn/mcp-zap-server(:|@) ]]; then scanner_ids+=("$container_id"); fi
done <<< "$inventory"
[[ ${#scanner_ids[@]} -le 1 ]] || block DUPLICATE_SCANNERS 'Multiple scanner installations were found. Select their intended ownership before continuing.'

ENV_FILE="$DIRECTORY/.env"
[[ ! -L "$ENV_FILE" ]] || block CONFIGURATION_INVALID 'The private environment file is linked. Review the installation manually.'
[[ -f "$ENV_FILE" ]] || block CREDENTIALS_MISSING 'The existing private .env is missing. Restore its settings and credentials; do not generate replacement keys as a repair.'
[[ -O "$ENV_FILE" ]] || block CONFIGURATION_INVALID 'The private .env is not owned by the current user. Review it without changing ownership automatically.'
if [[ "$(uname -s)" == Darwin ]]; then mode="$(stat -f '%Lp' "$ENV_FILE")"; else mode="$(stat -c '%a' "$ENV_FILE")"; fi
[[ "$mode" =~ ^[0-7]{3,4}$ ]] || block CONFIGURATION_INVALID 'The private environment permissions could not be checked.'
(( (8#$mode & 077) == 0 )) || block CONFIGURATION_INVALID 'The private .env is readable by other users. Explain the permission repair before changing it.'

# Read dotenv values as data, never source or evaluate the file.
read_setting() {
  local name="$1" value
  value="$(sed -n "s/^$name=//p" "$ENV_FILE" | tail -n 1)"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then value="${value:1:${#value}-2}"
  elif [[ "$value" == \'*\' && "$value" == *\' ]]; then value="${value:1:${#value}-2}"; fi
  printf '%s' "$value"
}
file_key="$(read_setting MCP_API_KEY)"
[[ -n "$file_key" && "$file_key" != *$'\r'* && "$file_key" != *$'\n'* ]] || block CREDENTIALS_MISSING 'The existing private MCP credential is missing or invalid. Resolve it privately without rotating keys automatically.'
configured_binding="$(read_setting MCP_ZAP_BIND_ADDRESS)"
[[ -z "$configured_binding" || "$configured_binding" == 127.0.0.1 ]] || block UNSAFE_BINDING 'The private .env selects a non-loopback binding. Preserve it and review that deployment manually before recovery.'
for inherited in MCP_API_KEY MCP_ZAP_API_KEY; do
  value="${!inherited:-}"
  [[ -z "$value" || "$value" == "$file_key" ]] || block CREDENTIAL_CONFLICT 'An inherited credential differs from the selected installation. Explain the mismatch without exposing or replacing a key.'
done
selected_project="$(read_setting COMPOSE_PROJECT_NAME)"
if [[ -z "$selected_project" ]]; then selected_project="$(basename "$DIRECTORY" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_-]//g; s/^[^a-z0-9]*//')"; fi
for setting in COMPOSE_PROJECT_NAME IMAGE_TAG ZAP_IMAGE MCP_ZAP_BIND_ADDRESS LOCAL_ZAP_WORKPLACE_FOLDER ZAP_API_KEY JWT_ENABLED MCP_SECURITY_ENABLED MCP_SECURITY_MODE; do
  inherited="${!setting:-}"
  [[ -n "$inherited" ]] || continue
  configured="$(read_setting "$setting")"
  if [[ "$setting" == COMPOSE_PROJECT_NAME ]]; then configured="$selected_project"; fi
  if [[ "$setting" == MCP_ZAP_BIND_ADDRESS && -z "$configured" ]]; then configured=127.0.0.1; fi
  [[ "$inherited" == "$configured" ]] || block ENVIRONMENT_CONFLICT 'An inherited scanner or Compose setting differs from the selected private configuration. Resolve the override without changing settings automatically.'
done

if [[ ${#scanner_ids[@]} -eq 0 ]]; then
  listeners="$(docker_read ps --filter publish=7456 --no-trunc --format '{{.ID}}')" || block CHECK_FAILED 'Docker port ownership could not be read.'
  [[ -z "$listeners" ]] || block PORT_CONFLICT 'Another container publishes the scanner port. Preserve it and resolve ownership before recovery.'
  block SCANNER_MISSING 'The scanner container is absent from this context. It may have been removed or the context changed. Confirm the intended context and intact settings before approving recreation.'
fi
scanner_id="${scanner_ids[0]}"
format='{{.Config.Image}}
{{index .Config.Labels "com.docker.compose.project.working_dir"}}
{{index .Config.Labels "com.docker.compose.project"}}
{{index .Config.Labels "com.docker.compose.service"}}
{{index .Config.Labels "com.docker.compose.project.config_files"}}
{{.State.Running}}
{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}
{{range $port, $bindings := .HostConfig.PortBindings}}{{range $bindings}}{{printf "%s=%s:%s;" $port .HostIp .HostPort}}{{end}}{{end}}
{{range .Config.Env}}{{if eq (index (split . "=") 0) "MCP_API_KEY"}}{{.}}{{end}}{{end}}
{{range .Config.Env}}{{if eq (index (split . "=") 0) "MCP_SECURITY_ENABLED"}}{{.}}{{end}}{{end}}
{{range .Config.Env}}{{if eq (index (split . "=") 0) "MCP_SECURITY_MODE"}}{{.}}{{end}}{{end}}
{{range .Config.Env}}{{if eq (index (split . "=") 0) "ZAP_API_KEY"}}{{.}}{{end}}{{end}}'
metadata="$(docker_read inspect --format "$format" "$scanner_id")" || block CHECK_FAILED 'The scanner changed or timed out during inspection. Check again before attempting recovery.'
fields=()
while IFS= read -r value; do fields+=("$value"); done <<< "$metadata"
[[ ${#fields[@]} -eq 12 ]] || block CONFIGURATION_INVALID 'The scanner metadata is unsupported. Review it privately without making changes.'
owner="${fields[1]}"; project="${fields[2]}"
[[ "$owner" == "$DIRECTORY" && "${fields[3]}" == mcp-server && "$project" =~ ^[a-z0-9][a-z0-9_-]*$ ]] ||
  block OWNERSHIP_CONFLICT 'The scanner belongs to another installation or lacks expected ownership. Preserve it and select the intended installation.'
[[ -z "${fields[4]}" || "${fields[4]}" == "$DIRECTORY/docker-compose.yml" ]] || block CONFIGURATION_INVALID 'The scanner uses a customized Compose layout. Use its existing deployment instructions.'
[[ "${fields[7]}" == '7456/tcp=127.0.0.1:7456;' ]] || block UNSAFE_BINDING 'This local workflow requires scanner port 7456 bound only to 127.0.0.1. Preserve and review the existing binding.'
[[ "${fields[9]}" == MCP_SECURITY_ENABLED=true && "${fields[10]}" == MCP_SECURITY_MODE=api-key ]] || block AUTH_UNSUPPORTED 'The scanner uses another authentication configuration. Use its existing connection instructions.'
for setting in MCP_SECURITY_ENABLED MCP_SECURITY_MODE; do
  configured="$(read_setting "$setting")"
  if [[ "$setting" == MCP_SECURITY_ENABLED ]]; then effective="${fields[9]#*=}"; else effective="${fields[10]#*=}"; fi
  [[ -z "$configured" || "$configured" == "$effective" ]] || block ENVIRONMENT_CONFLICT 'The private .env and running scanner authentication settings differ. Resolve the selected configuration without automatic changes.'
  inherited="${!setting:-}"
  [[ -z "$inherited" || "$inherited" == "$effective" ]] || block ENVIRONMENT_CONFLICT 'An inherited authentication setting differs from the running scanner. Resolve that override without changing settings automatically.'
done
[[ "${fields[8]}" == "MCP_API_KEY=$file_key" ]] || block CREDENTIAL_CONFLICT 'The effective scanner credential differs from its private .env. Resolve the mismatch privately; no key was changed.'
zap_key="$(read_setting ZAP_API_KEY)"
[[ -n "$zap_key" && "${fields[11]}" == "ZAP_API_KEY=$zap_key" ]] || block CREDENTIAL_CONFLICT 'The scanner and private .env disagree about the ZAP credential. Diagnose the selected deployment without rotating keys.'
project_ids="$(docker_read ps -a --no-trunc --filter "label=com.docker.compose.project=$project" --format '{{.ID}}')" || block CHECK_FAILED 'Compose ownership could not be read.'
zap_found=0
zap_running=false; zap_health=missing
while read -r container_id; do
  [[ -n "$container_id" ]] || continue
  [[ "$container_id" =~ ^[a-f0-9]{12,64}$ ]] || block CHECK_FAILED 'Unsupported Compose inventory.'
  member="$(docker_read inspect --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}
{{index .Config.Labels "com.docker.compose.service"}}
{{.State.Running}}
{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' "$container_id")" || block CHECK_FAILED 'Compose inventory changed during inspection.'
  parts=()
  while IFS= read -r value; do parts+=("$value"); done <<< "$member"
  [[ ${#parts[@]} -eq 4 && "${parts[0]}" == "$DIRECTORY" ]] || block OWNERSHIP_CONFLICT 'Another installation shares this Compose project. Preserve both and resolve ownership.'
  if [[ "${parts[1]}" == zap ]]; then
    zap_found=$((zap_found + 1))
    zap_running="${parts[2]}"; zap_health="${parts[3]}"
  fi
done <<< "$project_ids"
[[ "$zap_found" -eq 1 ]] || block SCANNER_MISSING 'The selected scanner engine is missing or duplicated. Review this context and deployment before recovery.'
if [[ "${fields[5]}" != true || "$zap_running" != true ]]; then
  [[ "$project" == "$selected_project" ]] || block ENVIRONMENT_CONFLICT 'The stopped scanner uses a different Compose project than startup would select. Review that deployment manually.'
  block SCANNER_STOPPED 'The existing scanner or its engine is stopped. Offer to resume the confirmed standard installation with approval, preserving its .env, credentials and reports.'
fi
[[ "${fields[6]}" == healthy && "$zap_health" == healthy ]] || block SCANNER_UNHEALTHY 'The scanner or its engine is running but not healthy. Diagnose it without automatic restart or recreation.'

# Only the trusted helper beside this script is executed. Its native tool probe
# is disabled: the agent must verify the actual requested client separately.
if ! MCP_API_KEY="$file_key" /usr/bin/env -u BASH_ENV -u ENV -u SHELLOPTS -u BASHOPTS -u CDPATH -u IFS \
  /bin/bash "$SCRIPT_DIR/self-serve-doctor.sh" --env-file "$ENV_FILE" --skip-docker --skip-tool-call >/dev/null 2>&1; then
  block DOCTOR_FAILED 'Authenticated readiness or tool discovery failed. Review the selected installation privately; do not start a scan or repair client settings automatically.'
fi
printf '%s\n' 'READY: Scanner readiness passed without calling a scanner tool. Verify one native passive-status call in the requested client, then follow only its authorized workflow.'
