#!/usr/bin/env bash
set -euo pipefail

action_root="${GITHUB_ACTION_PATH}"
workspace_root="${GITHUB_WORKSPACE:-$PWD}"

to_bool() {
  local value="${1:-false}"
  case "${value,,}" in
    true|1|yes|y|on) echo "true" ;;
    false|0|no|n|off|"") echo "false" ;;
    *)
      echo "Unsupported boolean value: $value" >&2
      return 1
      ;;
  esac
}

resolve_path() {
  local value="$1"
  if [[ -z "$value" ]]; then
    return 0
  fi
  if [[ "$value" = /* ]]; then
    printf '%s\n' "$value"
  else
    printf '%s/%s\n' "$workspace_root" "$value"
  fi
}

random_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
  fi
}

ensure_writable_workspace_dirs() {
  local dir
  for dir in "$@"; do
    if [[ -z "${dir}" ]]; then
      continue
    fi
    if [[ ! -d "${dir}" ]]; then
      continue
    fi
    if ! chmod 0777 "${dir}"; then
      echo "Warning: failed to relax permissions on ${dir}. Containerized ZAP may not be able to write there." >&2
    fi
  done
}

ensure_immutable_image_ref() {
  local ref="$1"
  local label="$2"
  local image_name tag

  if [[ -z "$ref" ]]; then
    echo "${label} must be set to an immutable image tag or digest." >&2
    exit 1
  fi

  if [[ "$ref" == *"<"* || "$ref" == *">"* || "$ref" == *"release-tag"* ]]; then
    echo "${label} still contains placeholder text (${ref}). Replace <release-tag> with a real release tag or digest." >&2
    exit 1
  fi

  if [[ "$ref" == *@sha256:* ]]; then
    return 0
  fi

  image_name="${ref##*/}"
  if [[ "$image_name" != *:* ]]; then
    echo "${label} must include an explicit release tag or digest. Bare image refs are not allowed." >&2
    exit 1
  fi

  tag="${image_name##*:}"
  case "$tag" in
    latest|dev|main|nightly|edge|canary)
      echo "${label} must not use the mutable :${tag} tag. Use a release tag or digest instead." >&2
      exit 1
      ;;
  esac
}

start_stack="$(to_bool "${INPUT_START_STACK:-true}")"
run_active_scan="$(to_bool "${INPUT_RUN_ACTIVE_SCAN:-true}")"
fail_on_new_findings="$(to_bool "${INPUT_FAIL_ON_NEW_FINDINGS:-true}")"
baseline_mode="${INPUT_BASELINE_MODE:-enforce}"
case "${baseline_mode}" in
  enforce|seed) ;;
  *)
    echo "baseline-mode must be 'enforce' or 'seed'." >&2
    exit 1
    ;;
esac

local_workspace_folder="$(resolve_path "${INPUT_LOCAL_ZAP_WORKSPACE_FOLDER:-.zap-work}")"
output_dir="$(resolve_path "${INPUT_OUTPUT_DIR:-.zap-artifacts}")"
baseline_file=""
if [[ -n "${INPUT_BASELINE_FILE:-}" ]]; then
  baseline_file="$(resolve_path "${INPUT_BASELINE_FILE:-}")"
fi
suppressions_file=""
if [[ -n "${INPUT_SUPPRESSIONS_FILE:-}" ]]; then
  suppressions_file="$(resolve_path "${INPUT_SUPPRESSIONS_FILE:-}")"
fi

mkdir -p "${local_workspace_folder}/reports" "${local_workspace_folder}/automation" "${local_workspace_folder}/zap-home" "${output_dir}"
ensure_writable_workspace_dirs \
  "${local_workspace_folder}" \
  "${local_workspace_folder}/reports" \
  "${local_workspace_folder}/automation" \
  "${local_workspace_folder}/zap-home"

export LOCAL_ZAP_WORKSPACE_FOLDER="${local_workspace_folder}"
export ZAP_API_KEY="${INPUT_ZAP_API_KEY:-}"
export MCP_API_KEY="${INPUT_MCP_API_KEY:-}"
export MCP_SERVER_IMAGE="${INPUT_MCP_SERVER_IMAGE:-}"
export ZAP_IMAGE="${INPUT_ZAP_IMAGE:-zaproxy/zap-stable:2.17.0}"

if [[ -z "${ZAP_API_KEY}" ]]; then
  ZAP_API_KEY="$(random_hex)"
  export ZAP_API_KEY
fi

if [[ -z "${MCP_API_KEY}" ]]; then
  MCP_API_KEY="$(random_hex)"
  export MCP_API_KEY
fi

compose_args=(-f "${action_root}/docker-compose.ci.yml")
if [[ -n "${INPUT_COMPOSE_OVERRIDE_FILE:-}" ]]; then
  override_file="$(resolve_path "${INPUT_COMPOSE_OVERRIDE_FILE:-}")"
  if [[ ! -f "${override_file}" ]]; then
    echo "Compose override file does not exist: ${override_file}" >&2
    exit 1
  fi
  compose_args+=(-f "${override_file}")
fi

project_name="mcpzapgate-${GITHUB_RUN_ID:-local}-${GITHUB_JOB:-job}"
project_name="${project_name//[^a-zA-Z0-9_-]/-}"

cleanup() {
  local status=$?
  if [[ "${start_stack}" == "true" ]]; then
    if [[ "${status}" -ne 0 ]]; then
      docker compose "${compose_args[@]}" -p "${project_name}" logs --no-color --tail=200 zap mcp-server || true
    fi
    docker compose "${compose_args[@]}" -p "${project_name}" down --remove-orphans || true
  fi
}

wait_for_health() {
  local url="$1"
  local attempts="$2"
  local sleep_seconds="$3"
  for ((i = 1; i <= attempts; i++)); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${sleep_seconds}"
  done
  return 1
}

if [[ "${start_stack}" == "true" ]]; then
  ensure_immutable_image_ref "${MCP_SERVER_IMAGE}" "mcp-server-image"

  trap cleanup EXIT

  read -r -a compose_services <<< "${INPUT_COMPOSE_SERVICES:-zap mcp-server}"
  docker compose "${compose_args[@]}" -p "${project_name}" up -d "${compose_services[@]}"

  if ! wait_for_health "http://localhost:7456/actuator/health" 60 5; then
    echo "Timed out waiting for MCP server health check" >&2
    exit 1
  fi
fi

python_args=(
  "${action_root}/mcp_zap_gate.py"
  "--server-url" "${INPUT_MCP_SERVER_URL:-http://localhost:7456/mcp}"
  "--api-key" "${MCP_API_KEY}"
  "--target-url" "${INPUT_TARGET_URL:-}"
  "--output-dir" "${output_dir}"
  "--report-template" "${INPUT_REPORT_TEMPLATE:-traditional-html-plus}"
  "--report-theme" "${INPUT_REPORT_THEME:-light}"
  "--run-active-scan" "${run_active_scan}"
  "--baseline-mode" "${baseline_mode}"
  "--fail-on-new-findings" "${fail_on_new_findings}"
  "--max-new-findings" "${INPUT_MAX_NEW_FINDINGS:-0}"
  "--poll-interval-seconds" "${INPUT_POLL_INTERVAL_SECONDS:-5}"
  "--passive-timeout-seconds" "${INPUT_PASSIVE_TIMEOUT_SECONDS:-180}"
  "--spider-timeout-seconds" "${INPUT_SPIDER_TIMEOUT_SECONDS:-600}"
  "--active-timeout-seconds" "${INPUT_ACTIVE_TIMEOUT_SECONDS:-1200}"
  "--report-root-container" "/zap/wrk/reports"
  "--report-root-local" "${local_workspace_folder}/reports"
)

if [[ -n "${baseline_file}" ]]; then
  python_args+=("--baseline-file" "${baseline_file}")
fi

if [[ -n "${suppressions_file}" ]]; then
  python_args+=("--suppressions-file" "${suppressions_file}")
fi

if [[ -n "${INPUT_SCAN_POLICY:-}" ]]; then
  python_args+=("--scan-policy" "${INPUT_SCAN_POLICY:-}")
fi

python3 "${python_args[@]}"
