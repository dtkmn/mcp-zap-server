#!/usr/bin/env bash
set -euo pipefail

dockerfile="${1:-Dockerfile}"
distroless_image="$(
  awk '$1 == "FROM" && $2 ~ /^gcr.io\\/distroless\\// { print $2; exit }' "${dockerfile}"
)"

cosign verify \
  --certificate-oidc-issuer https://accounts.google.com \
  --certificate-identity keyless@distroless.iam.gserviceaccount.com \
  "${distroless_image}" >/dev/null
