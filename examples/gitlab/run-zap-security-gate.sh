#!/usr/bin/env bash
set -euo pipefail

workspace_root="${CI_PROJECT_DIR:-$PWD}"

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
  python3 - <<'PY'
import secrets
print(secrets.token_hex(32))
PY
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

if [[ -z "${ZAP_TARGET_URL:-}" ]]; then
  echo "ZAP_TARGET_URL must be set for the GitLab security gate." >&2
  exit 1
fi

run_active_scan="$(to_bool "${ZAP_RUN_ACTIVE_SCAN:-true}")"
fail_on_new_findings="$(to_bool "${ZAP_FAIL_ON_NEW_FINDINGS:-true}")"
baseline_mode="${ZAP_BASELINE_MODE:-enforce}"
case "${baseline_mode}" in
  enforce|seed) ;;
  *)
    echo "ZAP_BASELINE_MODE must be 'enforce' or 'seed'." >&2
    exit 1
    ;;
esac
local_workspace_folder="$(resolve_path "${ZAP_LOCAL_WORKSPACE_FOLDER:-.zap-work}")"
output_dir="$(resolve_path "${ZAP_OUTPUT_DIR:-.zap-artifacts}")"
baseline_file=""
if [[ -n "${ZAP_BASELINE_FILE:-}" ]]; then
  baseline_file="$(resolve_path "${ZAP_BASELINE_FILE}")"
fi
suppressions_file=""
if [[ -n "${ZAP_SUPPRESSIONS_FILE:-}" ]]; then
  suppressions_file="$(resolve_path "${ZAP_SUPPRESSIONS_FILE}")"
fi
seed_requests_file=""
if [[ -n "${ZAP_SEED_REQUESTS_FILE:-}" ]]; then
  seed_requests_file="$(resolve_path "${ZAP_SEED_REQUESTS_FILE}")"
fi

mkdir -p "${local_workspace_folder}/reports" "${local_workspace_folder}/automation" "${local_workspace_folder}/zap-home" "${output_dir}"
ensure_writable_workspace_dirs \
  "${local_workspace_folder}" \
  "${local_workspace_folder}/reports" \
  "${local_workspace_folder}/automation" \
  "${local_workspace_folder}/zap-home"

export LOCAL_ZAP_WORKSPACE_FOLDER="${local_workspace_folder}"
export ZAP_API_KEY="${ZAP_API_KEY:-$(random_hex)}"
export MCP_API_KEY="${MCP_API_KEY:-$(random_hex)}"
export MCP_SERVER_IMAGE="${MCP_SERVER_IMAGE:-}"
export ZAP_IMAGE="${ZAP_IMAGE:-zaproxy/zap-stable:2.17.0}"

ensure_immutable_image_ref "${MCP_SERVER_IMAGE}" "MCP_SERVER_IMAGE"

compose_args=(-f "${workspace_root}/examples/gitlab/docker-compose.gitlab-ci.yml")
if [[ -n "${ZAP_COMPOSE_OVERRIDE_FILE:-}" ]]; then
  override_file="$(resolve_path "${ZAP_COMPOSE_OVERRIDE_FILE}")"
  if [[ ! -f "${override_file}" ]]; then
    echo "Compose override file does not exist: ${override_file}" >&2
    exit 1
  fi
  compose_args+=(-f "${override_file}")
fi

compose_services="${ZAP_COMPOSE_SERVICES:-zap mcp-server}"
read -r -a compose_service_array <<< "${compose_services}"

project_name="gitlab-zap-${CI_PIPELINE_ID:-local}-${CI_JOB_ID:-job}"
project_name="${project_name//[^a-zA-Z0-9_-]/-}"

cleanup() {
  local status=$?
  if [[ "${status}" -ne 0 ]]; then
    docker compose "${compose_args[@]}" -p "${project_name}" logs --no-color --tail=200 zap mcp-server || true
  fi
  docker compose "${compose_args[@]}" -p "${project_name}" down --remove-orphans || true
}
trap cleanup EXIT

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

docker compose "${compose_args[@]}" -p "${project_name}" up -d "${compose_service_array[@]}"

if ! wait_for_health "${MCP_HEALTH_URL:-http://docker:7456/actuator/health}" 60 5; then
  echo "Timed out waiting for the MCP server health check." >&2
  exit 1
fi

python_args=(
  "${workspace_root}/.github/actions/zap-security-gate/mcp_zap_gate.py"
  "--server-url" "${MCP_SERVER_URL:-http://docker:7456/mcp}"
  "--api-key" "${MCP_API_KEY}"
  "--target-url" "${ZAP_TARGET_URL}"
  "--output-dir" "${output_dir}"
  "--report-template" "${ZAP_REPORT_TEMPLATE:-traditional-html-plus}"
  "--report-theme" "${ZAP_REPORT_THEME:-light}"
  "--run-active-scan" "${run_active_scan}"
  "--baseline-mode" "${baseline_mode}"
  "--fail-on-new-findings" "${fail_on_new_findings}"
  "--max-new-findings" "${ZAP_MAX_NEW_FINDINGS:-0}"
  "--poll-interval-seconds" "${ZAP_POLL_INTERVAL_SECONDS:-5}"
  "--passive-timeout-seconds" "${ZAP_PASSIVE_TIMEOUT_SECONDS:-180}"
  "--spider-timeout-seconds" "${ZAP_SPIDER_TIMEOUT_SECONDS:-600}"
  "--active-timeout-seconds" "${ZAP_ACTIVE_TIMEOUT_SECONDS:-1200}"
  "--report-root-container" "/zap/wrk/reports"
  "--report-root-local" "${local_workspace_folder}/reports"
  "--zap-proxy-url" "${ZAP_PROXY_URL:-http://docker:8090}"
  "--seed-request-timeout-seconds" "${ZAP_SEED_REQUEST_TIMEOUT_SECONDS:-30}"
)

if [[ -n "${baseline_file}" ]]; then
  python_args+=("--baseline-file" "${baseline_file}")
fi

if [[ -n "${suppressions_file}" ]]; then
  python_args+=("--suppressions-file" "${suppressions_file}")
fi

if [[ -n "${seed_requests_file}" ]]; then
  python_args+=("--seed-requests-file" "${seed_requests_file}")
fi

if [[ -n "${ZAP_SCAN_POLICY:-}" ]]; then
  python_args+=("--scan-policy" "${ZAP_SCAN_POLICY}")
fi

python3 "${python_args[@]}"
