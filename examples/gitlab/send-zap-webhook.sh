#!/usr/bin/env bash
set -euo pipefail

workspace_root="${CI_PROJECT_DIR:-$PWD}"

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

if [[ -z "${ZAP_WEBHOOK_URL:-}" ]]; then
  echo "ZAP_WEBHOOK_URL is not set. Skipping webhook delivery." >&2
  exit 0
fi

metadata_path="$(resolve_path "${ZAP_WEBHOOK_METADATA_PATH:-.zap-artifacts/gate-metadata.json}")"
summary_path="$(resolve_path "${ZAP_WEBHOOK_SUMMARY_PATH:-.zap-artifacts/findings-summary.md}")"
snapshot_path="$(resolve_path "${ZAP_WEBHOOK_SNAPSHOT_PATH:-.zap-artifacts/current-findings.json}")"
diff_path="$(resolve_path "${ZAP_WEBHOOK_DIFF_PATH:-.zap-artifacts/findings-diff.txt}")"
report_local_path="$(resolve_path "${ZAP_WEBHOOK_REPORT_LOCAL_PATH:-}")"
output_path="$(resolve_path "${ZAP_WEBHOOK_OUTPUT_PATH:-.zap-artifacts/webhook-delivery.json}")"
allow_failure="$(to_bool "${ZAP_WEBHOOK_ALLOW_FAILURE:-false}")"

python_args=(
  "${workspace_root}/.github/actions/zap-webhook-callback/send_zap_webhook.py"
  "--webhook-url" "${ZAP_WEBHOOK_URL}"
  "--metadata-path" "${metadata_path}"
  "--summary-path" "${summary_path}"
  "--snapshot-path" "${snapshot_path}"
  "--diff-path" "${diff_path}"
  "--event-name" "${ZAP_WEBHOOK_EVENT_NAME:-zap_security_gate.completed}"
  "--provider" "gitlab"
  "--timeout-seconds" "${ZAP_WEBHOOK_TIMEOUT_SECONDS:-15}"
  "--max-attempts" "${ZAP_WEBHOOK_MAX_ATTEMPTS:-4}"
  "--initial-backoff-seconds" "${ZAP_WEBHOOK_INITIAL_BACKOFF_SECONDS:-2}"
  "--max-backoff-seconds" "${ZAP_WEBHOOK_MAX_BACKOFF_SECONDS:-30}"
  "--backoff-multiplier" "${ZAP_WEBHOOK_BACKOFF_MULTIPLIER:-2}"
  "--output-path" "${output_path}"
  "--allow-failure" "${allow_failure}"
)

if [[ -n "${report_local_path}" ]]; then
  python_args+=("--report-local-path" "${report_local_path}")
fi

if [[ -n "${ZAP_WEBHOOK_SECRET:-}" ]]; then
  python_args+=("--secret" "${ZAP_WEBHOOK_SECRET}")
fi

if [[ -n "${ZAP_WEBHOOK_BEARER_TOKEN:-}" ]]; then
  python_args+=("--bearer-token" "${ZAP_WEBHOOK_BEARER_TOKEN}")
fi

if [[ -n "${ZAP_WEBHOOK_HEADERS_JSON:-}" ]]; then
  python_args+=("--headers-json" "${ZAP_WEBHOOK_HEADERS_JSON}")
fi

python3 "${python_args[@]}"
