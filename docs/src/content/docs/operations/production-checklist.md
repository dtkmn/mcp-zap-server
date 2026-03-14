---
title: "Production Readiness Checklist"
editUrl: false
description: "Use this checklist before exposing MCP ZAP Server outside a single-user development setup."
---
Use this checklist before exposing MCP ZAP Server outside a single-user development setup.

## 1. Image and Release Control

- [ ] Pin `zaproxy/zap-stable` to a full release tag or digest. Do not rely on the floating `zap-stable` tag in production.
- [ ] Review ZAP release notes and image refresh cadence monthly.
- [ ] Pin the MCP server image to an explicit application version. Do not use `latest`.
- [ ] Verify required addons are installed explicitly. This repo assumes `ajaxSpider` is present.

## 2. Network Boundaries

- [ ] Keep the ZAP API on private networking only. Do not publish port `8090` to the public internet.
- [ ] Restrict ZAP API callers to explicit internal source ranges. The defaults in this repo now allow loopback plus RFC1918 ranges only.
- [ ] Expose the MCP server through a controlled ingress or an internal load balancer, not by opening all host interfaces directly.
- [ ] Add cluster or host firewall rules so only trusted clients can reach the MCP endpoint.

## 3. Authentication and Secrets

- [ ] Enable authentication on the MCP server. Use JWT for shared or internet-reachable deployments.
- [ ] Set `JWT_ENABLED=true` whenever `MCP_SECURITY_MODE=jwt`.
- [ ] Replace placeholder values for `ZAP_API_KEY`, `MCP_API_KEY`, and `JWT_SECRET` with secrets from your secret manager.
- [ ] Rotate API keys and JWT secrets on a schedule and after any incident.

## 4. Scan Scope and Safety

- [ ] Leave `ZAP_URL_VALIDATION_ENABLED=true`.
- [ ] Keep `ZAP_ALLOW_LOCALHOST=false` and `ZAP_ALLOW_PRIVATE_NETWORKS=false` outside isolated lab environments.
- [ ] Set `ZAP_URL_WHITELIST` or enforce target scope through ZAP contexts for every tenant or environment.
- [ ] Use dedicated ZAP instances per trust boundary. Do not share one scanner across unrelated teams or tenants.

## 5. Capacity and Isolation

- [ ] Keep ZAP as a single stateful replica. Scale the MCP layer horizontally instead.
- [ ] Size ZAP for real scans. Start with at least `2 GiB` request and `4 GiB` limit, then tune from observed load.
- [ ] Keep active-scan and spider concurrency limits conservative until you have target-specific performance data.
- [ ] Persist `/zap/wrk` and retain enough storage for reports, sessions, and addon state.

## 6. HA and State Management

- [ ] Use durable queue state for multi-replica MCP deployments.
- [ ] Use leader election so only one replica dispatches scans to ZAP at a time.
- [ ] If `streamable-http` MCP is exposed through multiple OSS/local replicas, enable sticky ingress or equivalent client affinity at the ingress/load-balancer layer.
- [ ] Test failover by terminating the active leader and confirming a follower takes over cleanly.
- [ ] Validate that no duplicate scan starts occur during failover or restart.

## 7. Observability and Operations

- [ ] Monitor `/actuator/health`, queue depth, scan durations, and ZAP availability.
- [ ] Alert on repeated scan retries, stuck `RUNNING` jobs, and authentication failures.
- [ ] Keep structured logs for both the MCP service and ZAP, with retention that matches your compliance needs.
- [ ] Document an upgrade runbook for ZAP version bumps, addon changes, and rollback.

## 8. Pre-Go-Live Validation

- [ ] Run `docker compose config` or `helm template` in CI to catch bad config before deployment.
- [ ] Smoke-test spider, active scan, report generation, and authenticated scanning against a staging target.
- [ ] Confirm the deployed MCP endpoint requires auth and the ZAP endpoint is unreachable from untrusted networks.
- [ ] Re-run this checklist whenever you change image tags, addon configuration, exposure model, or queue backend.
