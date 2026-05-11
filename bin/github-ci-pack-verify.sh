#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
VERIFY_ROOT="${REPO_ROOT}/.verify/github-ci-pack"
RENDER_ROOT="${VERIFY_ROOT}/rendered"
SKIP_DOCKER=0
SKIP_GRADLE=0
WITH_IMAGE_BUILD=0

usage() {
  cat <<'EOF'
Usage: ./bin/github-ci-pack-verify.sh [options]

Verifies the GitHub CI security-gate pack without running a full target scan:
- action shell syntax
- Python helper contract tests
- CI compose wiring for ZAP/MCP shared workspace
- Spring AI / Spring Boot dependency resolution notes
- Docker image packaging architecture guard

Options:
  --skip-docker       Skip Docker Compose manifest rendering.
  --skip-gradle       Skip Gradle dependency and architecture checks.
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

assert_file_contains() {
  local file="$1"
  local needle="$2"
  if ! grep -Fq "$needle" "$file"; then
    echo "Expected ${file} to contain: ${needle}" >&2
    exit 1
  fi
}

assert_bind_count() {
  local file="$1"
  local needle="$2"
  local expected="$3"
  local actual
  actual="$(grep -F -c "$needle" "$file" || true)"
  if [[ "$actual" -ne "$expected" ]]; then
    echo "Expected ${needle} to appear ${expected} time(s) in ${file}, found ${actual}." >&2
    exit 1
  fi
}

assert_exact_line_count() {
  local file="$1"
  local line="$2"
  local expected="$3"
  local actual
  actual="$(grep -F -x -c "$line" "$file" || true)"
  if [[ "$actual" -ne "$expected" ]]; then
    echo "Expected exact line '${line}' to appear ${expected} time(s) in ${file}, found ${actual}." >&2
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-docker)
      SKIP_DOCKER=1
      shift
      ;;
    --skip-gradle)
      SKIP_GRADLE=1
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
require_command grep
require_command sed

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  require_command docker
fi

mkdir -p "${VERIFY_ROOT}" "${RENDER_ROOT}"

log_step "Validate GitHub action shell syntax"
bash -n "${REPO_ROOT}/.github/actions/zap-security-gate/run-gate.sh"
bash -n "${REPO_ROOT}/.github/actions/zap-webhook-callback/run-webhook.sh"
pass "Composite action shell entrypoints parse"

log_step "Run CI helper Python tests"
(
  cd "${REPO_ROOT}"
  python3 -m unittest \
    tests.python.test_mcp_zap_gate \
    tests.python.test_ci_image_ref_validation \
    tests.python.test_ci_tool_surface_defaults \
    tests.python.test_send_zap_webhook
)
pass "CI helper Python contracts pass"

if [[ "${SKIP_DOCKER}" -eq 0 ]]; then
  log_step "Render GitHub CI compose stack"
  export LOCAL_ZAP_WORKSPACE_FOLDER="${VERIFY_ROOT}/zap-workspace"
  export ZAP_API_KEY="verify-zap-api-key"
  export MCP_API_KEY="verify-mcp-api-key"
  export MCP_SERVER_IMAGE="mcp-zap-server:verify-ci-pack"
  export ZAP_IMAGE="zaproxy/zap-stable:2.17.0"
  mkdir -p "${LOCAL_ZAP_WORKSPACE_FOLDER}/reports" "${LOCAL_ZAP_WORKSPACE_FOLDER}/automation" "${LOCAL_ZAP_WORKSPACE_FOLDER}/zap-home"
  docker compose -f "${REPO_ROOT}/.github/actions/zap-security-gate/docker-compose.ci.yml" config > "${RENDER_ROOT}/github-ci-compose.yaml"
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose.yaml" "        source: ${LOCAL_ZAP_WORKSPACE_FOLDER}" 2
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose.yaml" "        target: /zap/wrk" 2
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose.yaml" "        source: ${LOCAL_ZAP_WORKSPACE_FOLDER}/zap-home" 1
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose.yaml" "        target: /home/zap/.ZAP" 1
  assert_file_contains "${RENDER_ROOT}/github-ci-compose.yaml" "MCP_SERVER_TOOLS_SURFACE: expert"
  pass "CI compose stack keeps ZAP and MCP on the shared workspace"

  log_step "Render GitHub CI compose stack with example app"
  docker compose \
    -f "${REPO_ROOT}/.github/actions/zap-security-gate/docker-compose.ci.yml" \
    -f "${REPO_ROOT}/examples/github-actions/docker-compose.app-under-test.yml" \
    config > "${RENDER_ROOT}/github-ci-compose-with-example-app.yaml"
  assert_file_contains "${RENDER_ROOT}/github-ci-compose-with-example-app.yaml" "app:"
  assert_file_contains "${RENDER_ROOT}/github-ci-compose-with-example-app.yaml" "image: nginx:1.27-alpine"
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose-with-example-app.yaml" "        source: ${LOCAL_ZAP_WORKSPACE_FOLDER}" 2
  assert_exact_line_count "${RENDER_ROOT}/github-ci-compose-with-example-app.yaml" "        target: /zap/wrk" 2
  pass "Example app-under-test compose override renders with the CI stack"
fi

if [[ "${SKIP_GRADLE}" -eq 0 ]]; then
  log_step "Record Spring AI / Spring Boot dependency resolution"
  (
    cd "${REPO_ROOT}"
    ./gradlew dependencyInsight --dependency spring-ai-starter-mcp-server-webflux --configuration runtimeClasspath --no-daemon > "${VERIFY_ROOT}/spring-ai-runtime-dependency.txt"
    ./gradlew dependencyInsight --dependency spring-boot --configuration runtimeClasspath --no-daemon > "${VERIFY_ROOT}/spring-boot-runtime-dependency.txt"
  )
  assert_file_contains "${VERIFY_ROOT}/spring-ai-runtime-dependency.txt" "org.springframework.ai:spring-ai-starter-mcp-server-webflux:2.0.0-M5"
  assert_file_contains "${VERIFY_ROOT}/spring-boot-runtime-dependency.txt" "org.springframework.boot:spring-boot:4.0.6"
  assert_file_contains "${VERIFY_ROOT}/spring-boot-runtime-dependency.txt" "org.springframework.boot:spring-boot-starter:4.1.0-RC1 -> 4.0.6"
  pass "Spring AI 2.0.0-M5 resolves against the managed Spring Boot 4.0.6 runtime"

  log_step "Run Docker image packaging architecture guard"
  (
    cd "${REPO_ROOT}"
    ./gradlew test --tests mcp.server.zap.architecture.DockerImagePackagingArchitectureTest --no-daemon --stacktrace
  )
  pass "Dockerfile selects the executable application JAR explicitly"
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
