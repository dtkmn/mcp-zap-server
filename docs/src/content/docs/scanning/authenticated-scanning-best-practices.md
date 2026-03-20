---
title: "Authenticated Scanning Best Practices"
editUrl: false
description: "This guide explains the recommended workflow for authenticated scanning with MCP ZAP Server using:"
---
This guide explains the recommended workflow for authenticated scanning with MCP ZAP Server using:

- `zap_context_upsert`
- `zap_contexts_list`
- `zap_context_auth_configure`
- `zap_user_upsert`
- `zap_users_list`
- `zap_auth_test_user`
- `zap_spider_as_user`
- `zap_active_scan_as_user`

This page requires the `expert` surface. Set `MCP_SERVER_TOOLS_SURFACE=expert` if you want these tools exposed.

## Why This Matters

Authenticated scans are where most business-critical vulnerabilities live.

If you skip context and user setup, scans are usually limited to public pages and results are noisy.

## Recommended Workflow

### 1. Create a Tight Context Scope

Use explicit include and exclude regexes first.  
Do not start with broad catch-all patterns.

```json
{
  "tool": "zap_context_upsert",
  "arguments": {
    "contextName": "shop-prod-auth",
    "includeRegexes": [
      "https://shop.example.com/.*"
    ],
    "excludeRegexes": [
      "https://shop.example.com/logout.*",
      "https://shop.example.com/static/.*"
    ],
    "inScope": true
  }
}
```

### 2. Resolve Context ID

```json
{
  "tool": "zap_contexts_list",
  "arguments": {}
}
```

Capture the `contextId` for next steps.

### 3. Configure Authentication for the Context

Set auth method and reliable login indicators.

```json
{
  "tool": "zap_context_auth_configure",
  "arguments": {
    "contextId": "1",
    "authMethodName": "formBasedAuthentication",
    "authMethodConfigParams": "loginUrl=https://shop.example.com/login&loginRequestData=username={%username%}&password={%password%}",
    "loggedInIndicatorRegex": ".*Logout.*",
    "loggedOutIndicatorRegex": ".*Sign in.*"
  }
}
```

### 4. Create a Dedicated Scan User

Use a least-privilege test account.

```json
{
  "tool": "zap_user_upsert",
  "arguments": {
    "contextId": "1",
    "userName": "zap-scan-user",
    "authCredentialsConfigParams": "username=zap-scan-user&password=StrongPassword123!",
    "enabled": true
  }
}
```

### 5. Verify the User Is Present and Enabled

```json
{
  "tool": "zap_users_list",
  "arguments": {
    "contextId": "1"
  }
}
```

### 6. Test Authentication Before Scanning

Do this before every major scan run.

```json
{
  "tool": "zap_auth_test_user",
  "arguments": {
    "contextId": "1",
    "userId": "7"
  }
}
```

Expected: `likelyAuthenticated` should be `true` (or at least not `false`).

### 7. Crawl as Authenticated User

Run spider first to map authenticated paths.

```json
{
  "tool": "zap_spider_as_user",
  "arguments": {
    "contextId": "1",
    "userId": "7",
    "targetUrl": "https://shop.example.com",
    "maxChildren": "10",
    "recurse": "true",
    "subtreeOnly": "false"
  }
}
```

### 8. Run Active Scan as Authenticated User

```json
{
  "tool": "zap_active_scan_as_user",
  "arguments": {
    "contextId": "1",
    "userId": "7",
    "targetUrl": "https://shop.example.com",
    "recurse": "true",
    "policy": "Default Policy"
  }
}
```

If you need durable queue lifecycle instead of direct scans, use the queue-managed expert equivalents:

- `zap_queue_spider_scan_as_user`
- `zap_queue_active_scan_as_user`

### 9. Monitor Progress

Use existing status tools:

- `zap_spider_status`
- `zap_active_scan_status`
- `zap_scan_job_status` for queued authenticated scans

## Practical Best Practices

1. Use one context per application and environment (`shop-dev-auth`, `shop-stg-auth`, `shop-prod-auth`).
2. Keep include regexes narrow and explicit.
3. Always exclude logout endpoints and non-app domains.
4. Use a non-admin scan account unless admin coverage is explicitly required.
5. Validate authentication with `zap_auth_test_user` before scans.
6. Spider first, then active scan.
7. Keep credentials out of prompts/logs where possible.
8. Rotate scan user credentials on a schedule.
9. Start with conservative scan depth and duration, then tune up.
10. Store `contextId` and `userId` as CI variables for repeatable runs.

## Common Pitfalls

1. `likelyAuthenticated=false`: indicators are wrong or credentials are invalid.
2. Scan only sees login page: context include regex is too narrow, or auth not actually applied.
3. Unexpected targets scanned: include/exclude regex rules are too broad.
4. User exists but scan fails: user disabled or wrong `contextId` + `userId` pairing.
5. Frequent session expiry: target app session timeout is short, retest auth right before scans.

## CI/CD Pattern

A reliable pipeline order is:

1. `zap_context_upsert`
2. `zap_context_auth_configure`
3. `zap_user_upsert`
4. `zap_auth_test_user`
5. `zap_spider_as_user`
6. `zap_active_scan_as_user`
7. status polling and report/finding collection

If auth test fails, stop pipeline immediately and fail fast.
