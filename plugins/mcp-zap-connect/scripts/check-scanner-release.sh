#!/usr/bin/env bash
set -euo pipefail

SCANNER_RELEASE_TAG='v0.18.0'
SCANNER_RELEASE_IMAGE="dtkmn/mcp-zap-server:${SCANNER_RELEASE_TAG}"

block() { printf '%s: %s\n' "$1" "$2"; exit 2; }
usage() {
  printf '%s\n' 'Usage: bash check-scanner-release.sh --directory /absolute/scanner-checkout' \
    'Check the official GitHub release tag, local checkout and selected Docker image without changing them.' \
    'Exit 0: COMPATIBLE. Exit 2: blocked; preserve the installation and report the reason.'
}

DIRECTORY=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --directory)
      [[ $# -ge 2 && -z "$DIRECTORY" ]] || block INVALID_ARGUMENT 'Supply one installation directory.'
      DIRECTORY="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) block INVALID_ARGUMENT 'Use --help for the supported arguments.' ;;
  esac
done

[[ "$DIRECTORY" == /* && "$DIRECTORY" != / && "$DIRECTORY" != *$'\n'* && "$DIRECTORY" != *$'\r'* && -d "$DIRECTORY" ]] ||
  block INVALID_DIRECTORY 'Select an absolute, existing scanner checkout, not the filesystem root.'
canonical="$(cd "$DIRECTORY" && pwd -P)"
[[ "$DIRECTORY" == "$canonical" ]] || block INVALID_DIRECTORY 'Use the real checkout path without linked components.'
command -v git >/dev/null 2>&1 || block GIT_MISSING 'Git is required to verify the selected release.'
for variable in GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_REPLACE_REF_BASE GIT_CONFIG_COUNT GIT_CONFIG_PARAMETERS; do
  [[ -z "${!variable:-}" ]] || block ENVIRONMENT_CONFLICT 'A Git environment override changes the selected checkout. Review it without changing the installation.'
done
git_read=(git --no-optional-locks --no-replace-objects -C "$DIRECTORY")
root="$("${git_read[@]}" rev-parse --show-toplevel 2>/dev/null)" || block RELEASE_MISMATCH 'The selected directory is not a Git checkout.'
[[ "$root" == "$DIRECTORY" ]] || block RELEASE_MISMATCH 'Select the checkout root, not a subdirectory.'
head="$("${git_read[@]}" rev-parse --verify HEAD 2>/dev/null)" || block RELEASE_MISMATCH 'The checkout has no verifiable commit.'
tag="$("${git_read[@]}" rev-parse --verify "refs/tags/${SCANNER_RELEASE_TAG}^{commit}" 2>/dev/null)" ||
  block RELEASE_MISMATCH 'The supported release tag is unavailable locally. No fetch or checkout was attempted.'
[[ "$head" == "$tag" ]] || block RELEASE_MISMATCH 'Select the checkout at the supported release tag.'
# A release cannot contain its own commit hash. Resolve the exact tag from the
# official repository independently of the checkout and its Git configuration.
release_url="https://api.github.com/repos/dtkmn/mcp-zap-server/commits/refs%2Ftags%2F${SCANNER_RELEASE_TAG}"
release_commit="$(curl --disable --fail --silent --proto '=https' --connect-timeout 4 --max-time 10 \
  --max-filesize 80 --header 'Accept: application/vnd.github.sha' -- "$release_url" 2>/dev/null)" &&
  [[ "$release_commit" =~ ^[0-9a-f]{40}$ ]] ||
  block RELEASE_UNAVAILABLE 'The official release commit could not be verified. Check curl, GitHub API availability and network access before continuing.'
[[ "$head" == "$release_commit" ]] ||
  block RELEASE_MISMATCH 'The checkout or release tag differs from the supported scanner release. Preserve it and use its own deployment instructions.'

required_files=(
  .env.example docker-compose.yml build.gradle .mcp/server.json
  bin/bootstrap-local.sh bin/self-serve-doctor.sh bin/scanner-readiness.sh
  docs/getting-started/SELF_SERVE_FIRST_RUN.md
)
for directory in bin .mcp docs docs/getting-started; do
  [[ -d "$DIRECTORY/$directory" && ! -L "$DIRECTORY/$directory" ]] ||
    block MODIFIED_SETUP 'A required setup directory is missing or linked.'
done
for relative in "${required_files[@]}"; do
  file="$DIRECTORY/$relative"
  [[ -f "$file" && ! -L "$file" ]] || block MODIFIED_SETUP 'A required release file is missing, linked or not a regular file.'
  expected="$("${git_read[@]}" rev-parse "${release_commit}:${relative}" 2>/dev/null)" || block MODIFIED_SETUP 'A required file is absent from the supported release.'
  actual="$("${git_read[@]}" hash-object --no-filters -- "$file" 2>/dev/null)" || block MODIFIED_SETUP 'A required setup file could not be read.'
  [[ "$actual" == "$expected" ]] || block MODIFIED_SETUP 'Setup files or version metadata differ from the supported release. No files were replaced.'
  [[ "$relative" != bin/*.sh || -x "$file" ]] || block MODIFIED_SETUP 'A required setup helper is not executable.'
done
"${git_read[@]}" diff --cached --no-ext-diff --quiet "$release_commit" -- "${required_files[@]}" ||
  block MODIFIED_SETUP 'Staged setup changes require manual review; the index was not changed.'

for variable in COMPOSE_FILE COMPOSE_ENV_FILES COMPOSE_PROFILES; do
  [[ -z "${!variable:-}" ]] || block CUSTOM_DEPLOYMENT 'An inherited Compose override requires its existing deployment procedure.'
done
for alternate in compose.yaml compose.yml docker-compose.yaml compose.override.yaml compose.override.yml docker-compose.override.yaml docker-compose.override.yml; do
  [[ ! -e "$DIRECTORY/$alternate" && ! -L "$DIRECTORY/$alternate" ]] ||
    block CUSTOM_DEPLOYMENT 'An alternate Compose file exists. Preserve it and use the custom deployment procedure.'
done
env_file="$DIRECTORY/.env"
if [[ -e "$env_file" || -L "$env_file" ]]; then
  [[ -f "$env_file" && ! -L "$env_file" ]] || block CUSTOM_DEPLOYMENT 'The existing .env must be a regular, unlinked file.'
else
  # Bootstrap copies image selection from this verified template when creating settings.
  env_file="$DIRECTORY/.env.example"
fi
if grep -Eq '^[[:space:]]*(export[[:space:]]+)?(COMPOSE_FILE|COMPOSE_ENV_FILES|COMPOSE_PROFILES)[[:space:]]*=' "$env_file"; then
  block CUSTOM_DEPLOYMENT 'Private settings select a custom Compose configuration. No settings were changed.'
fi
command -v docker >/dev/null 2>&1 || block DOCKER_MISSING 'Docker Compose is required to resolve the selected image.'
compose=(docker compose --project-directory "$DIRECTORY" --env-file "$env_file" -f "$DIRECTORY/docker-compose.yml")
images="$("${compose[@]}" config --images mcp-server 2>/dev/null)" ||
  block CONFIGURATION_INVALID 'Compose could not resolve the scanner image. Inspect its diagnostics privately; no startup was attempted.'
image=''; scanner_images=0
while IFS= read -r candidate; do
  case "$candidate" in
    dtkmn/mcp-zap-server:*|dtkmn/mcp-zap-server@*)
      image="$candidate"; scanner_images=$((scanner_images + 1)) ;;
  esac
done <<< "$images"
[[ "$scanner_images" == 1 && "$image" == "$SCANNER_RELEASE_IMAGE" ]] ||
  block IMAGE_MISMATCH 'The effective scanner image differs from the supported release. Preserve the selected version and review the mismatch.'
# A user can change IMAGE_TAG while a container from an older release still
# exists. Include stopped containers, which bootstrap could otherwise recreate.
endpoint="$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null)" ||
  block CHECK_FAILED 'Docker context inspection failed. Check the selected local engine.'
if [[ -n "${DOCKER_HOST:-}" && -z "${DOCKER_CONTEXT:-}" ]]; then endpoint="$DOCKER_HOST"; fi
[[ "$endpoint" == unix://* && "$endpoint" != *$'\n'* ]] ||
  block REMOTE_DOCKER_UNSUPPORTED 'Select the intended local Unix-socket Docker engine before checking this installation.'
scanner_id="$(docker ps -a --no-trunc --filter "label=com.docker.compose.project.working_dir=$DIRECTORY" \
  --filter 'label=com.docker.compose.service=mcp-server' --format '{{.ID}}' 2>/dev/null)" ||
  block CHECK_FAILED 'Could not inspect existing scanner containers. Check the selected local engine.'
# Compose identifies installations by project name. A different directory can
# select that same name even when its existing scanner has been stopped.
project_id="$("${compose[@]}" ps --all --quiet mcp-server 2>/dev/null)" ||
  block CHECK_FAILED 'Could not inspect the scanner in the selected Compose project.'
[[ "$project_id" == "$scanner_id" ]] ||
  block OWNERSHIP_CONFLICT 'The Compose project and selected directory identify different scanner containers. Review their ownership before setup.'
if [[ -n "$scanner_id" ]]; then
  [[ "$scanner_id" =~ ^[a-f0-9]{64}$ ]] ||
    block OWNERSHIP_CONFLICT 'The selected directory has multiple or unidentifiable scanner containers. Review their ownership.'
  installed_image="$(docker inspect --type container --format '{{.Config.Image}}' "$scanner_id" 2>/dev/null)" ||
    block CHECK_FAILED 'The selected scanner container could not be inspected.'
  [[ "$installed_image" == "$SCANNER_RELEASE_IMAGE" ]] ||
    block IMAGE_MISMATCH 'The existing scanner container uses another image. Preserve it and follow the scanner upgrade guide.'
fi
printf 'COMPATIBLE: scanner checkout and image match %s. No installation state was changed.\n' "$SCANNER_RELEASE_TAG"
