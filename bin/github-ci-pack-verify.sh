#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERIFY_ROOT="${REPO_ROOT}/.verify/github-ci-pack"
RENDER_ROOT="${VERIFY_ROOT}/rendered"
SKIP_DOCKER=0
WITH_IMAGE_BUILD=0

usage() {
  cat <<'EOF'
Usage: ./bin/github-ci-pack-verify.sh [options]

Verifies the GitHub CI security-gate pack without running a full target scan:
- GitHub and GitLab gate shell syntax
- Python helper behavior tests
- GitHub and GitLab Compose wiring for the ZAP/MCP shared workspace

Options:
  --skip-docker       Skip Docker Compose manifest rendering.
  --with-image-build  Build the local Docker image as an additional proof.
  --help, -h          Show this help message.
EOF
}

log_step() {
  printf '\n==> %s\n' "$1"
}

pass() {
  printf 'PASS %s\n' "$1"
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

verify_gate_compose_model() {
  local model_file="$1"
  local workspace_root="$2"
  local stack_label="$3"
  python3 - "${model_file}" "${workspace_root}" "${stack_label}" <<'PY'
import json
from pathlib import Path
import sys

model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
workspace = str(Path(sys.argv[2]).resolve())
stack_label = sys.argv[3]
services = model["services"]

actual_mounts = sorted(
    (
        service_name,
        volume.get("type"),
        volume.get("source"),
        volume.get("target"),
        bool(volume.get("read_only", False)),
    )
    for service_name in ("zap", "mcp-server")
    for volume in services[service_name].get("volumes", [])
)
expected_mounts = sorted([
    ("zap", "bind", workspace, "/zap/wrk", False),
    ("zap", "bind", f"{workspace}/zap-home", "/home/zap/.ZAP", False),
    ("mcp-server", "bind", workspace, "/zap/wrk", False),
])
if actual_mounts != expected_mounts:
    raise SystemExit(f"Unexpected {stack_label} CI mounts: {actual_mounts}")

surface = services["mcp-server"].get("environment", {}).get("MCP_SERVER_TOOLS_SURFACE")
if surface != "expert":
    raise SystemExit(f"{stack_label} CI MCP server must use expert surface, got {surface!r}")
PY
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-docker)
      SKIP_DOCKER=1
      shift
      ;;
    --with-image-build)
      WITH_IMAGE_BUILD=1
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

require_command bash
require_command python3

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  require_command docker
fi

mkdir -p "${VERIFY_ROOT}" "${RENDER_ROOT}"

log_step "Validate CI gate shell syntax"
bash -n "${REPO_ROOT}/.github/actions/zap-security-gate/run-gate.sh"
bash -n "${REPO_ROOT}/.github/actions/zap-webhook-callback/run-webhook.sh"
bash -n "${REPO_ROOT}/examples/gitlab/run-zap-security-gate.sh"
pass "GitHub and GitLab gate shell entrypoints parse"

log_step "Run CI helper Python tests"
(
  cd "${REPO_ROOT}"
  python3 -m unittest discover -s tests/python -p 'test_*.py'
)
pass "CI helper Python behaviors pass"

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  log_step "Render GitHub CI compose stack"
  export LOCAL_ZAP_WORKSPACE_FOLDER="${VERIFY_ROOT}/zap-workspace"
  export ZAP_API_KEY="verify-zap-api-key"
  export MCP_API_KEY="verify-mcp-api-key"
  export MCP_SERVER_IMAGE="mcp-zap-server:verify-ci-pack"
  export ZAP_IMAGE="zaproxy/zap-stable:2.17.0"
  mkdir -p "${LOCAL_ZAP_WORKSPACE_FOLDER}/reports" "${LOCAL_ZAP_WORKSPACE_FOLDER}/automation" "${LOCAL_ZAP_WORKSPACE_FOLDER}/zap-home"
  docker compose \
    -f "${REPO_ROOT}/.github/actions/zap-security-gate/docker-compose.ci.yml" \
    config --format json > "${RENDER_ROOT}/github-ci-compose.json"
  verify_gate_compose_model \
    "${RENDER_ROOT}/github-ci-compose.json" \
    "${LOCAL_ZAP_WORKSPACE_FOLDER}" \
    "GitHub"
  pass "CI compose stack keeps ZAP and MCP on the shared workspace"

  log_step "Render GitHub CI compose stack with example app"
  docker compose \
    -f "${REPO_ROOT}/.github/actions/zap-security-gate/docker-compose.ci.yml" \
    -f "${REPO_ROOT}/examples/github-actions/docker-compose.app-under-test.yml" \
    config --format json > "${RENDER_ROOT}/github-ci-compose-with-example-app.json"
  verify_gate_compose_model \
    "${RENDER_ROOT}/github-ci-compose-with-example-app.json" \
    "${LOCAL_ZAP_WORKSPACE_FOLDER}" \
    "GitHub example-app"
  python3 - "${RENDER_ROOT}/github-ci-compose-with-example-app.json" <<'PY'
import json
from pathlib import Path
import sys

model = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
services = model["services"]

if services.get("app", {}).get("image") != "nginx:1.27-alpine":
    raise SystemExit("Example app override did not resolve the expected app image")
PY
  pass "Example app-under-test compose override renders with the CI stack"

  log_step "Render GitLab CI compose stack"
  docker compose \
    -f "${REPO_ROOT}/examples/gitlab/docker-compose.gitlab-ci.yml" \
    config --format json > "${RENDER_ROOT}/gitlab-ci-compose.json"
  verify_gate_compose_model \
    "${RENDER_ROOT}/gitlab-ci-compose.json" \
    "${LOCAL_ZAP_WORKSPACE_FOLDER}" \
    "GitLab"
  pass "GitLab CI compose stack keeps ZAP and MCP on the shared workspace"
fi

if [[ "${WITH_IMAGE_BUILD}" -eq 1 ]]; then
  if [[ "${SKIP_DOCKER}" -eq 1 ]]; then
    echo "--with-image-build cannot be used with --skip-docker" >&2
    exit 1
  fi
  log_step "Build local MCP server image"
  docker build --progress=plain -t "mcp-zap-server:ci-pack-verify" "${REPO_ROOT}"
  pass "Docker image builds from the current checkout"
fi

printf '\nGitHub CI pack verification completed successfully.\n'
printf -- '- Evidence directory: %s\n' "${VERIFY_ROOT}"
