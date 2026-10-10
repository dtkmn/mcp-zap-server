#!/bin/bash
# Build the local checkout with the development Compose override.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building and starting the local development stack."

docker compose --project-directory "$REPO_ROOT" --env-file "$REPO_ROOT/.env" \
  -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.dev.yml" \
  up -d --build --wait --wait-timeout 300
"$REPO_ROOT/bin/self-serve-doctor.sh" --env-file "$REPO_ROOT/.env"

echo "Local development stack is ready."
