---
title: "Authenticated Scanning Reference"
editUrl: false
description: "Advanced deployment, migration, validation, and expert guidance for authenticated scans."
---
This is the advanced operator reference. If this is your first target that
requires login, start with
[Optional: Form-Login Target Authentication](../../getting-started/form-login-target-authentication/)
for the local Compose setup and a Cursor-ready example.

Authenticated scans are where most business-critical vulnerabilities live. They are also where teams most often fool themselves.

If authentication is not proven immediately before the crawl or attack, the scan is usually just an unauthenticated scan wearing a nicer label.

## Recommended Path

In `v0.10.0` and later, start with guided auth bootstrap:

- `zap_auth_session_prepare`
- `zap_auth_session_validate`
- `zap_crawl_start` with `authSessionId`
- `zap_attack_start` with `authSessionId`

These tools are available on the default `guided` surface. The lower-level ZAP context and user tools still exist on the `expert` surface, but they are the advanced path now.

The profile contract is available in `v0.10.0` and later. Traditional form-login
support is limited to the HTTP spider and active scan paths; it does not imply
OAuth, SSO, MFA, CAPTCHA, or JavaScript-heavy browser login support.

Treat guided profile contexts as managed state. Do not alter a returned context with expert auth tools; fix the profile and prepare a new session instead.

## Operator Secret Setup

Guided auth uses operator-managed profiles. MCP callers select a profile and a target URL; they cannot choose or recombine its credential, login URL, or allowed origin.

This is a deployment-level trust boundary, not tenant isolation:

- every principal with `zap:auth:session:write` can select every auth profile configured in that deployment
- profile IDs are selectors, not per-caller or per-workspace access controls
- prepared session IDs are sensitive capabilities; do not log, share, or expose them beyond the scan workflow that created them
- run separate MCP ZAP deployments for tenants or teams that must not share credential authority

Keep the profile in external Spring configuration and keep the secret itself in an environment variable or mounted file:

```yaml
mcp:
  server:
    auth:
      bootstrap:
        profiles:
          - id: shop-form
            kind: form
            allowed-origin: https://shop.example.com
            credential-reference: env:TARGET_SCAN_PASSWORD
            login-url: https://shop.example.com/login
            username: zap-scan-user
            zap-user-name: zap-scan-user
            username-field: username
            password-field: password
            logged-in-indicator-regex: ".*Logout.*"
            logged-out-indicator-regex: ".*Sign in.*"
```

Credential references are exact operator-owned values:

- `env:NAME`
- `file:/absolute/path`

Wildcard references and inline secrets are not supported. Use one profile per application, environment, and credential. `allowed-origin` contains only scheme, host, and optional port; paths belong in `targetUrl` and `login-url`.

The ZAP context name is derived from the unique profile ID with an `-auth` suffix. Its scope is the profile's immutable `allowed-origin`, so preparing the same profile for another path cannot rewrite the live scope behind an earlier session or queued job. Different profiles still receive different contexts.

## Upgrade Migration: Profiles Are Explicit

Replace the removed `MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES` and
`MCP_AUTH_BOOTSTRAP_ALLOW_INLINE_SECRETS` settings. The new profile list defaults
to empty. A deployment can therefore report healthy while exposing no guided auth
profiles.

Profiles are compiled only at process startup. After changing profile configuration
or rotating its secret, restart the MCP process or pod and run the real prepare and
validate check below. A health check alone is not migration evidence.

The following non-secret profile file is shared by the executable-JAR and Compose
recipes. Save it as `/etc/mcp-zap/auth-profiles.yml` and adjust the target-specific
values:

```yaml
mcp:
  server:
    auth:
      bootstrap:
        profiles:
          - id: shop-form
            kind: form
            allowed-origin: https://shop.example.com
            credential-reference: ${TARGET_SCAN_CREDENTIAL_REFERENCE:env:TARGET_SCAN_PASSWORD}
            login-url: https://shop.example.com/login
            username: zap-scan-user
            username-field: username
            password-field: password
            logged-in-indicator-regex: ".*Logout.*"
            logged-out-indicator-regex: ".*Sign in.*"
```

### Executable JAR

Inject the password through the existing service manager or secret store. This
foreground example avoids placing it in shell history:

```bash
printf 'Target scan password: ' >&2
IFS= read -r -s TARGET_SCAN_PASSWORD
printf '\n' >&2
export TARGET_SCAN_PASSWORD

./gradlew bootJar
APP_VERSION=$(./gradlew -q properties | awk '/^version:/ {print $2}')
java -Dspring.ai.mcp.server.type=sync \
  -jar "build/libs/mcp-zap-server-${APP_VERSION}.jar" \
  --spring.config.additional-location=file:/etc/mcp-zap/auth-profiles.yml
```

Do not make the additional location optional: a missing profile file should fail
the deployment. Stop and restart the process through its normal supervisor whenever
the profile or secret changes. Prepared session IDs are in memory and do not survive
that restart.

### Docker Compose

Keep the password in a host file outside the repository, readable by Docker, and
use a Compose secret rather than a container environment variable. Reuse the
checked-in
[`auth-profiles.example.yml`](https://github.com/dtkmn/mcp-zap-server/blob/main/examples/authenticated-scanning/auth-profiles.example.yml)
and
[`docker-compose.auth-profile.yml`](https://github.com/dtkmn/mcp-zap-server/blob/main/examples/authenticated-scanning/docker-compose.auth-profile.yml)
instead of maintaining another copy of the override. Copy and edit the profile
outside the repository first.

Set fully expanded absolute paths, validate the merged deployment, and recreate
the MCP container:

```bash
export MCP_ZAP_AUTH_PROFILES_FILE=/etc/mcp-zap/auth-profiles.yml
export MCP_ZAP_FORM_PASSWORD_FILE=/run/operator-secrets/shop-form-password
test -r "$MCP_ZAP_AUTH_PROFILES_FILE" && test -r "$MCP_ZAP_FORM_PASSWORD_FILE"

docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  config --quiet
docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  up -d --build --force-recreate --wait --wait-timeout 180 mcp-server
```

This migration recipe uses the JVM image built by `Dockerfile`; do not add
`docker-compose.prod.yml`. The native build is not a supported migration path in
this revision because `nativeCompile` is not configured and its build inputs are
incomplete. Recreate `mcp-server` after secret-only rotation. The secret target
must be readable, and the `/zap/wrk` bind mount writable, by runtime UID/GID 1000.

### Helm

Create the Kubernetes Secret from a protected file. The key becomes the environment
variable referenced by the profile:

```bash
export NAMESPACE=mcp-zap
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NAMESPACE" create secret generic mcp-zap-auth-profile \
  --from-file=TARGET_SCAN_PASSWORD=/run/operator-secrets/shop-form-password \
  --dry-run=client -o yaml | kubectl apply -f -
```

Merge this into the values used by the deployment as `auth-profile-values.yml`:

```yaml
mcp:
  env:
    - name: SPRING_APPLICATION_JSON
      value: |-
        {
          "mcp": {
            "server": {
              "auth": {
                "bootstrap": {
                  "profiles": [{
                    "id": "shop-form",
                    "kind": "form",
                    "allowedOrigin": "https://shop.example.com",
                    "credentialReference": "env:TARGET_SCAN_PASSWORD",
                    "loginUrl": "https://shop.example.com/login",
                    "username": "zap-scan-user",
                    "usernameField": "username",
                    "passwordField": "password",
                    "loggedInIndicatorRegex": ".*Logout.*",
                    "loggedOutIndicatorRegex": ".*Sign in.*"
                  }]
                }
              }
            }
          }
        }
  envFrom:
    - secretRef:
        name: mcp-zap-auth-profile

networkPolicy:
  zap:
    egress:
      extraEgress:
        - to:
            - ipBlock:
                cidr: 203.0.113.10/32 # REPLACE with the target's approved CIDR
          ports:
            - protocol: TCP
              port: 443
```

`mcp.env` and `mcp.envFrom` are lists, so merge any existing entries rather than
overwriting them. If `SPRING_APPLICATION_JSON` already exists, merge the profile
object into that value; do not define the variable twice. The default ZAP NetworkPolicy
permits DNS only, so target egress is mandatory. Private targets also require the
deployment's explicit URL-policy approval. The chart now defaults to `v0.10.0`,
the first release containing this profile contract. The commands below deliberately
pin the immutable `sha-<40-character commit SHA>` image produced by main CI for the
commit being migrated. Replace the variable placeholder before running them. Do
not use `v0.9.1` or earlier; those images do not contain the profile contract.

The repository does not contain a `production-values.yml`. The example below
uses only the profile values file. If you maintain another values file, add its
real path with another `-f` argument.

Render before applying, then wait for the MCP rollout:

```bash
(
set -euo pipefail
: "${NAMESPACE:?set NAMESPACE}"
MCP_ZAP_IMAGE_TAG=sha-REPLACE_WITH_FULL_MAIN_COMMIT_SHA
[[ "$MCP_ZAP_IMAGE_TAG" =~ ^sha-[0-9a-f]{40}$ ]] || {
  echo "MCP_ZAP_IMAGE_TAG must be the immutable full-SHA image tag from main CI" >&2
  exit 1
}

helm lint helm/mcp-zap-server \
  -f auth-profile-values.yml \
  --set-string "mcp.image.tag=$MCP_ZAP_IMAGE_TAG"
helm template mcp-zap helm/mcp-zap-server \
  --namespace "$NAMESPACE" \
  -f auth-profile-values.yml \
  --set-string "mcp.image.tag=$MCP_ZAP_IMAGE_TAG" \
  --show-only templates/mcp-deployment.yaml \
  | grep -E "^[[:space:]]+image: \"[^\"]+:${MCP_ZAP_IMAGE_TAG}\"$" >/dev/null
helm upgrade --install mcp-zap helm/mcp-zap-server \
  --namespace "$NAMESPACE" \
  -f auth-profile-values.yml \
  --set-string "mcp.image.tag=$MCP_ZAP_IMAGE_TAG" \
  --atomic --wait
kubectl -n "$NAMESPACE" rollout status deployment \
  -l app.kubernetes.io/instance=mcp-zap,app.kubernetes.io/name=mcp-server
)
```

A Secret-only update does not change the pod template. Force the required restart:

```bash
kubectl -n "$NAMESPACE" rollout restart deployment \
  -l app.kubernetes.io/instance=mcp-zap,app.kubernetes.io/name=mcp-server
kubectl -n "$NAMESPACE" rollout status deployment \
  -l app.kubernetes.io/instance=mcp-zap,app.kubernetes.io/name=mcp-server
```

The chart defaults to a ClusterIP service. After the final rollout, unless you
already expose it through an approved ingress, keep this port-forward running in a
second terminal before using the localhost verification URL below:

```bash
MCP_SERVICE=$(kubectl -n "$NAMESPACE" get service \
  -l app.kubernetes.io/instance=mcp-zap,app.kubernetes.io/name=mcp-server \
  -o jsonpath='{.items[0].metadata.name}')
test -n "$MCP_SERVICE"
kubectl -n "$NAMESPACE" port-forward "service/$MCP_SERVICE" 7456:7456
```

### Verify The Migrated Profile

Run this after **every** JAR, Compose, or Helm restart. Use an API key whose client
has `zap:auth:session:write` and `zap:auth:test`; for JWT mode, set
`MCP_AUTH_HEADER="Authorization: Bearer $ACCESS_TOKEN"` with those scopes instead.
The check requires `curl`, `jq`, and standard shell text utilities.

```bash
(
set -euo pipefail
export MCP_URL="${MCP_URL:-http://localhost:7456/mcp}"
export MCP_AUTH_HEADER="${MCP_AUTH_HEADER:-X-API-Key: ${MCP_API_KEY:?set MCP_API_KEY or MCP_AUTH_HEADER}}"
export MCP_PROTOCOL_VERSION="${MCP_PROTOCOL_VERSION:-2025-11-25}"

INITIALIZE_REQUEST=$(jq -nc --arg protocolVersion "$MCP_PROTOCOL_VERSION" \
  '{jsonrpc:"2.0",id:0,method:"initialize",params:{protocolVersion:$protocolVersion,capabilities:{},clientInfo:{name:"auth-profile-check",version:"1.0.0"}}}')
SESSION_ID=$(curl -si --fail-with-body \
  -H "$MCP_AUTH_HEADER" \
  -H 'Accept: application/json,text/event-stream' \
  -H 'Content-Type: application/json' \
  "$MCP_URL" \
  -d "$INITIALIZE_REQUEST" \
  | awk -F': ' 'tolower($1) == "mcp-session-id" {print $2}' | tr -d '\r')
test -n "$SESSION_ID"

normalize_mcp_response() {
  local raw_file=$1
  local data
  data=$(sed -n 's/^data:[[:space:]]*//p' "$raw_file" | tail -n 1)
  if [ -n "$data" ]; then printf '%s' "$data"; else cat "$raw_file"; fi
}

PREPARE_RAW=$(mktemp)
VALIDATE_RAW=$(mktemp)
trap 'rm -f "$PREPARE_RAW" "$VALIDATE_RAW"' EXIT

curl -sS --fail-with-body \
  -H "$MCP_AUTH_HEADER" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "MCP-Protocol-Version: $MCP_PROTOCOL_VERSION" \
  -H 'Accept: application/json,text/event-stream' \
  -H 'Content-Type: application/json' \
  "$MCP_URL" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"zap_auth_session_prepare","arguments":{"profileId":"shop-form","targetUrl":"https://shop.example.com"}}}' \
  >"$PREPARE_RAW"

PREPARE_JSON=$(normalize_mcp_response "$PREPARE_RAW")
printf '%s' "$PREPARE_JSON" | jq -e '.result.isError == false' >/dev/null
PREPARE_TEXT=$(printf '%s' "$PREPARE_JSON" | jq -er '
  .result.content[] | select(.type == "text") | .text |
  if startswith("\"") and endswith("\"") then fromjson else . end
')
printf '%s\n' "$PREPARE_TEXT" | grep -F 'Guided auth session prepared.'
printf '%s\n' "$PREPARE_TEXT" | grep -F 'Auth Profile: shop-form'
printf '%s\n' "$PREPARE_TEXT" | grep -F 'Authorized Origin: https://shop.example.com'
AUTH_SESSION_ID=$(printf '%s\n' "$PREPARE_TEXT" | sed -n 's/^Session ID: //p')
test -n "$AUTH_SESSION_ID"

VALIDATE_REQUEST=$(jq -nc --arg sessionId "$AUTH_SESSION_ID" \
  '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:"zap_auth_session_validate",arguments:{sessionId:$sessionId}}}')
curl -sS --fail-with-body \
  -H "$MCP_AUTH_HEADER" \
  -H "Mcp-Session-Id: $SESSION_ID" \
  -H "MCP-Protocol-Version: $MCP_PROTOCOL_VERSION" \
  -H 'Accept: application/json,text/event-stream' \
  -H 'Content-Type: application/json' \
  "$MCP_URL" -d "$VALIDATE_REQUEST" >"$VALIDATE_RAW"

VALIDATE_JSON=$(normalize_mcp_response "$VALIDATE_RAW")
printf '%s' "$VALIDATE_JSON" | jq -e '.result.isError == false' >/dev/null
VALIDATE_TEXT=$(printf '%s' "$VALIDATE_JSON" | jq -er '
  .result.content[] | select(.type == "text") | .text |
  if startswith("\"") and endswith("\"") then fromjson else . end
')
printf '%s\n' "$VALIDATE_TEXT" | grep -F 'Valid: true'
printf '%s\n' "$VALIDATE_TEXT" | grep -F 'Outcome: authenticated'
printf '%s\n' "$VALIDATE_TEXT" | grep -F 'likelyAuthenticated=true'
)
```

If any assertion fails, the migration is incomplete. Inspect correlated operator
logs, fix the profile, secret injection, URL policy, or ZAP egress, restart, and
repeat. Do not run an authenticated crawl on a health-only or prepare-only signal.

## Form-login Workflow

Use a dedicated least-privilege scan account and a tight target scope.

### 1. Prepare the Auth Session

```json
{
  "tool": "zap_auth_session_prepare",
  "arguments": {
    "profileId": "shop-form",
    "targetUrl": "https://shop.example.com"
  }
}
```

The response should include:

- `Session ID`
- `Auth Profile: shop-form`
- `Auth Kind: form`
- `Authorized Origin: https://shop.example.com`
- `Provider: zap-form-login`
- `Engine Binding: ZAP context/user ready`
- `Context ID`
- `User ID`

It must not include the secret value.

### 2. Validate Before Scanning

```json
{
  "tool": "zap_auth_session_validate",
  "arguments": {
    "sessionId": "auth-session-id-from-prepare"
  }
}
```

Expected evidence:

- `Valid: true`
- `Outcome: authenticated`
- `likelyAuthenticated=true`
- the same context and user IDs from preparation

If validation fails, stop. Do not continue and pretend the scan is authenticated.

### 3. Crawl as the Prepared Session

```json
{
  "tool": "zap_crawl_start",
  "arguments": {
    "targetUrl": "https://shop.example.com",
    "strategy": "http",
    "authSessionId": "auth-session-id-from-prepare"
  }
}
```

Guided authenticated crawl currently accepts prepared form-login sessions on the HTTP spider path. Browser/AJAX crawl with `authSessionId` is not supported in this window.

### 4. Attack as the Prepared Session

```json
{
  "tool": "zap_attack_start",
  "arguments": {
    "targetUrl": "https://shop.example.com",
    "recurse": "true",
    "policy": "Default Policy",
    "authSessionId": "auth-session-id-from-prepare"
  }
}
```

After crawl or attack completion, wait for passive scanning before reading findings or generating reports:

```json
{
  "tool": "zap_passive_scan_wait",
  "arguments": {}
}
```

## Bearer And API-key Sessions

Auth profiles also accept:

- `kind: bearer`
- `kind: api-key`

For those flows, validation proves the credential reference resolves. It does not prove the target accepts the header, and current guided ZAP execution does not automatically inject those headers into crawl or attack execution.

Use bearer and API-key bootstrap for gateway credential-reference preparation today. Do not sell it as authenticated scan execution until header injection is implemented in the engine path.

## Practical Best Practices

1. Use one auth profile and prepared session per application, environment, and auth flow.
2. Keep profile configuration operator-owned and keep raw secrets only in environment variables or mounted files.
3. Bind every profile to one exact `allowed-origin`.
4. Use non-admin scan accounts unless admin coverage is explicitly approved.
5. Validate with `zap_auth_session_validate` before every major scan run.
6. Treat every `likelyAuthenticated` value other than `true` as a hard stop.
7. Exclude logout paths and destructive account-management paths from scan scope.
8. Spider first, then active scan.
9. Keep scan depth and duration conservative until the target is understood.
10. Capture `correlationId`, a SHA-256 fingerprint of the session ID (never the raw ID), `Context ID`, and `User ID` as release evidence.

## Advanced Expert Workflow

Use the raw expert path only when the guided workflow cannot express the setup you need or when you are debugging ZAP context/user state directly.

Expert tools:

- `zap_context_upsert`
- `zap_contexts_list`
- `zap_context_auth_configure`
- `zap_user_upsert`
- `zap_users_list`
- `zap_auth_test_user`
- `zap_spider_as_user`
- `zap_active_scan_as_user`
- `zap_queue_spider_scan_as_user`
- `zap_queue_active_scan_as_user`

This path requires:

```bash
MCP_SERVER_TOOLS_SURFACE=expert
```

Do not paste real passwords into `authCredentialsConfigParams` in prompts, tickets, screenshots, or docs. If you need the expert path in CI, inject secrets from the CI secret store and keep logs redacted.

## Advanced Expert Order

A reliable expert order is:

1. `zap_context_upsert` with narrow include/exclude regexes
2. `zap_contexts_list` to capture `contextId`
3. `zap_context_auth_configure` with form auth method and login indicators
4. `zap_user_upsert` using a runtime-injected secret
5. `zap_auth_test_user`
6. `zap_spider_as_user` or `zap_queue_spider_scan_as_user`
7. `zap_active_scan_as_user` or `zap_queue_active_scan_as_user`
8. status polling, passive scan wait, findings, and report generation

If `zap_auth_test_user` cannot prove the user is authenticated, the scan is not release evidence. Fix auth first.

## Common Pitfalls

- `Unknown auth profile ID`: the requested profile is not configured.
- `targetUrl origin is not authorized for auth profile`: the caller tried to use a profile outside its operator-approved origin.
- `Auth profile ... is invalid`: fix the operator configuration before retrying.
- `likelyAuthenticated` is not `true`: indicators, field names, credentials, target behavior, or ZAP diagnostics are wrong or indeterminate.
- Header session validates but crawl is still unauthenticated: bearer/API-key header injection is not in the guided engine path yet.
- Browser strategy rejects auth: authenticated browser/AJAX guided crawl is not supported yet.

If you are debugging an incident, use the
[Auth Bootstrap Failure Runbook](https://github.com/dtkmn/mcp-zap-server/blob/main/docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md).
