#!/usr/bin/env bash
set -euo pipefail

workspace_root="${GITHUB_WORKSPACE:-$PWD}"
action_root="${GITHUB_ACTION_PATH}"

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

python_args=(
  "${action_root}/send_zap_webhook.py"
  "--webhook-url" "${INPUT_WEBHOOK_URL}"
  "--metadata-path" "$(resolve_path "${INPUT_METADATA_PATH}")"
  "--event-name" "${INPUT_EVENT_NAME}"
  "--provider" "${INPUT_PROVIDER}"
  "--timeout-seconds" "${INPUT_TIMEOUT_SECONDS}"
  "--max-attempts" "${INPUT_MAX_ATTEMPTS}"
  "--initial-backoff-seconds" "${INPUT_INITIAL_BACKOFF_SECONDS}"
  "--max-backoff-seconds" "${INPUT_MAX_BACKOFF_SECONDS}"
  "--backoff-multiplier" "${INPUT_BACKOFF_MULTIPLIER}"
  "--allow-failure" "${INPUT_ALLOW_FAILURE}"
)

if [[ -n "${INPUT_SUMMARY_PATH}" ]]; then
  python_args+=("--summary-path" "$(resolve_path "${INPUT_SUMMARY_PATH}")")
fi

if [[ -n "${INPUT_SNAPSHOT_PATH}" ]]; then
  python_args+=("--snapshot-path" "$(resolve_path "${INPUT_SNAPSHOT_PATH}")")
fi

if [[ -n "${INPUT_DIFF_PATH}" ]]; then
  python_args+=("--diff-path" "$(resolve_path "${INPUT_DIFF_PATH}")")
fi

if [[ -n "${INPUT_REPORT_LOCAL_PATH}" ]]; then
  python_args+=("--report-local-path" "$(resolve_path "${INPUT_REPORT_LOCAL_PATH}")")
fi

if [[ -n "${INPUT_SECRET}" ]]; then
  python_args+=("--secret" "${INPUT_SECRET}")
fi

if [[ -n "${INPUT_BEARER_TOKEN}" ]]; then
  python_args+=("--bearer-token" "${INPUT_BEARER_TOKEN}")
fi

if [[ -n "${INPUT_HEADERS_JSON}" ]]; then
  python_args+=("--headers-json" "${INPUT_HEADERS_JSON}")
fi

if [[ -n "${INPUT_OUTPUT_PATH}" ]]; then
  python_args+=("--output-path" "$(resolve_path "${INPUT_OUTPUT_PATH}")")
fi

python3 "${python_args[@]}"
