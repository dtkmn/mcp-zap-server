---
title: "Production Readiness Checklist"
editUrl: false
description: "Use this checklist before exposing MCP ZAP Server outside a single-user development setup."
---
Use this checklist before exposing MCP ZAP Server outside a single-user development setup.

## 1. Image and Release Control

- [ ] Pin `zaproxy/zap-stable` to a full release tag or digest.
- [ ] Pin the MCP server image to an explicit application version.
- [ ] Verify required add-ons are installed explicitly for the features you plan to use.
- [ ] Stop pretending `latest` is a release strategy.

## 2. Network Boundaries

- [ ] Keep the ZAP API on private networking only.
- [ ] Expose the MCP server through a controlled ingress or internal load balancer.
- [ ] Add network rules so only trusted clients can reach `/mcp`.

## 3. Authentication and Secrets

- [ ] Enable authentication on the MCP server.
- [ ] Use JWT for shared or internet-reachable deployments.
- [ ] Keep `MCP_SECURITY_AUTHORIZATION_MODE=enforce`.
- [ ] Define per-client scope sets instead of sharing one broad key.
- [ ] Disable wildcard scopes once migration is complete.
- [ ] Replace placeholder values for `ZAP_API_KEY`, `MCP_API_KEY`, and `JWT_SECRET`.
- [ ] Rotate API keys and JWT secrets on a schedule and after incidents.

## 4. Scan Scope and Safety

- [ ] Leave `ZAP_URL_VALIDATION_ENABLED=true`.
- [ ] Keep `ZAP_ALLOW_LOCALHOST=false` and `ZAP_ALLOW_PRIVATE_NETWORKS=false` outside isolated lab environments.
- [ ] Set `ZAP_URL_WHITELIST` or enforce scope through ZAP contexts.
- [ ] Use dedicated ZAP instances per trust boundary.

## 5. Capacity and Isolation

- [ ] Keep ZAP as a single stateful replica. Scale the MCP layer horizontally instead.
- [ ] Size ZAP for real scans, not for demo optimism.
- [ ] Keep concurrency limits conservative until you have target-specific data.
- [ ] Keep `MCP_PROTECTION_ENABLED=true`.
- [ ] Tune workspace quotas and backpressure to match one real ZAP runtime.
- [ ] Persist `/zap/wrk`.
- [ ] If you use automation tools, provide a shared automation workspace and set `ZAP_AUTOMATION_LOCAL_DIRECTORY` plus `ZAP_AUTOMATION_ZAP_DIRECTORY`.

## 6. HA and State Management

- [ ] Use durable queue state for multi-replica MCP deployments.
- [ ] Use Postgres-backed scan history when scan evidence must survive restart, failover, or release handoff.
- [ ] Use Postgres-backed scan-job state when queued jobs are part of release or pilot evidence.
- [ ] Set a sane queue claim lease with `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`.
- [ ] If you expose streamable HTTP through multiple replicas, enable sticky ingress or equivalent client affinity.
- [ ] Test failover by terminating a worker with a claimed running job and confirming another replica recovers polling after lease expiry.
- [ ] Validate that no duplicate scan starts occur during failover or restart.

## 7. Observability and Operations

- [ ] Monitor `/actuator/health`, queue depth, scan durations, and ZAP availability.
- [ ] Keep `/actuator/metrics`, `/actuator/prometheus`, and `/actuator/auditevents` on private or authenticated access paths only.
- [ ] Monitor `mcp.zap.http.requests`, `mcp.zap.auth.events`, `mcp.zap.authorization.decisions`, `mcp.zap.tool.executions`, `mcp.zap.queue.jobs`, and `mcp.zap.audit.events`.
- [ ] Monitor `mcp.protection.rate_limited`, `mcp.protection.workspace_quota_rejections`, and `mcp.protection.backpressure_rejections`.
- [ ] Alert on repeated scan retries, stuck `RUNNING` jobs, and authentication failures.
- [ ] Alert on sustained `429` rates so you can distinguish client abuse from capacity saturation.
- [ ] Preserve `X-Correlation-Id` through reverse proxies.
- [ ] Keep structured logs for both the MCP service and ZAP.
- [ ] Retain scan history long enough to cover release sign-off, pilot support, and incident review.

## 8. Pre-Go-Live Validation

- [ ] Run `docker compose config` or `helm template` in CI.
- [ ] Smoke-test crawl, attack, report generation, and authenticated scanning against a staging target.
- [ ] Run `zap_scan_history_list`, `zap_scan_history_release_evidence`, and `zap_scan_history_customer_handoff` after the smoke test. Attach raw JSON only to the internal record, and attach the curated summary to customer-facing packages.
- [ ] Confirm the deployed MCP endpoint requires auth and the ZAP endpoint is not reachable from untrusted networks.
- [ ] Re-run this checklist whenever you change image tags, add-ons, exposure model, or queue backend.
