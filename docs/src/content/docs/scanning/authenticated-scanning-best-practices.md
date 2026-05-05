---
title: "Authenticated Scanning Best Practices"
editUrl: false
description: "Prepare, validate, and use guided authenticated scan sessions safely."
---
Authenticated scans are where most business-critical vulnerabilities live. They are also where teams most often fool themselves.

If authentication is not proven immediately before the crawl or attack, the scan is usually just an unauthenticated scan wearing a nicer label.

## Recommended Path

For 0.7.0 and later, start with guided auth bootstrap:

- `zap_auth_session_prepare`
- `zap_auth_session_validate`
- `zap_crawl_start` with `authSessionId`
- `zap_attack_start` with `authSessionId`

These tools are available on the default `guided` surface. The lower-level ZAP context and user tools still exist on the `expert` surface, but they are the advanced path now.

## Operator Secret Setup

Guided auth expects credential references, not raw passwords in prompts.

Recommended baseline:

```bash
MCP_AUTH_BOOTSTRAP_ALLOW_INLINE_SECRETS=false
MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES=env:STAGING_SCAN_PASSWORD,file:/var/run/secrets/mcp-zap/*
```

Allowed credential references use:

- `env:NAME`
- `file:/absolute/path`

File wildcard entries are directory containment rules. Sibling-prefix paths and symlink escapes are rejected intentionally.

Inline secrets are disabled by default. Keep them that way outside local, throwaway workflows.

## Form-login Workflow

Use a dedicated least-privilege scan account and a tight target scope.

### 1. Prepare the Auth Session

```json
{
  "tool": "zap_auth_session_prepare",
  "arguments": {
    "targetUrl": "https://shop.example.com",
    "authKind": "form",
    "credentialReference": "env:STAGING_SCAN_PASSWORD",
    "sessionLabel": "shop-staging-form-auth",
    "contextName": "shop-staging-auth",
    "loginUrl": "https://shop.example.com/login",
    "username": "zap-scan-user",
    "userName": "zap-scan-user",
    "usernameField": "username",
    "passwordField": "password",
    "loggedInIndicatorRegex": ".*Logout.*",
    "loggedOutIndicatorRegex": ".*Sign in.*"
  }
}
```

The response should include:

- `Session ID`
- `Auth Kind: form`
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

`zap_auth_session_prepare` also accepts:

- `authKind=bearer`
- `authKind=api-key`

For those flows, validation proves the credential reference resolves. It does not prove the target accepts the header, and current guided ZAP execution does not automatically inject those headers into crawl or attack execution.

Use bearer and API-key bootstrap for gateway credential-reference preparation today. Do not sell it as authenticated scan execution until header injection is implemented in the engine path.

## Practical Best Practices

1. Use one prepared session per application, environment, and auth flow.
2. Prefer `credentialReference` over inline secrets.
3. Keep `MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES` narrow and operator-owned.
4. Use non-admin scan accounts unless admin coverage is explicitly approved.
5. Validate with `zap_auth_session_validate` before every major scan run.
6. Treat `likelyAuthenticated=false` as a hard stop.
7. Exclude logout paths and destructive account-management paths from scan scope.
8. Spider first, then active scan.
9. Keep scan depth and duration conservative until the target is understood.
10. Capture `correlationId`, `Session ID`, `Context ID`, and `User ID` as release evidence.

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

- `credentialReference is not in the operator allowlist`: the caller selected a secret source operators did not pre-approve.
- `Provide credentialReference or inlineSecret`: no secret source was supplied.
- `loginUrl must share the same origin as targetUrl`: form login is trying to cross an origin boundary.
- `likelyAuthenticated=false`: indicators, field names, credentials, or target behavior are wrong.
- Header session validates but crawl is still unauthenticated: bearer/API-key header injection is not in the guided engine path yet.
- Browser strategy rejects auth: authenticated browser/AJAX guided crawl is not supported yet.

If you are debugging an incident, use the operator runbook at `docs/operator/runbooks/AUTH_BOOTSTRAP_FAILURE_RUNBOOK.md`.
