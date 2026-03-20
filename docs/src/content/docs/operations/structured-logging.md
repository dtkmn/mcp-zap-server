---
title: "Structured Logging"
editUrl: false
description: "Understand correlation IDs, request completion logs, and log troubleshooting flow."
---
MCP ZAP Server assigns a stable correlation ID to every HTTP request and includes it in structured logs and bounded error responses.

## Correlation ID Contract

Every request receives `X-Correlation-Id`.

Behavior:

- if the client sends `X-Correlation-Id`, the server reuses it
- if the client only sends legacy `X-Request-Id`, the server upgrades that into the correlation ID contract
- if neither header is present, the server generates a UUID

The server then:

- echoes `X-Correlation-Id` on the response
- includes `correlationId` in bounded JSON error bodies
- emits the same ID in `request.completed` logs

Client-supplied IDs are intentionally constrained to short safe characters so logs and headers stay predictable.

## Error Response Contract

`correlationId` is included in the machine-readable error bodies used by:

- auth failures
- scope denials
- `429` abuse-protection rejections
- validation and unexpected errors

## Request Completion Logs

The default completion log line includes:

- `correlationId`
- `clientId`
- `workspaceId`

Example:

```text
request.completed correlationId=corr-123 method=POST path=/mcp status=200 durationMs=41 signal=ON_COMPLETE clientId=ci-gate workspaceId=shared-prod
```

This is the fastest way to join ingress behavior with later app logs.

## Operator Guidance

- encourage clients and CI systems to send `X-Correlation-Id`
- preserve that header through reverse proxies
- start support triage from the response `correlationId`
- treat `X-Request-Id` as legacy compatibility only
