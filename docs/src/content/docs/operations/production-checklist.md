---
title: "Production Readiness Checklist"
editUrl: false
description: "Use this checklist before exposing MCP ZAP Server outside a single-user development setup."
---
Use this checklist before exposing MCP ZAP Server outside a single-user development setup.

For a first private AWS evaluation, follow [AWS EC2 with Docker Compose](../aws-ec2-compose/). It includes the single-instance example, verification and cleanup; review this checklist before expanding that setup.
For Kubernetes, follow the [private EKS deployment guidance](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#first-private-eks-deployment) and review the chart's storage and egress requirements.

## 1. Image and Release Control

- [ ] Pin `zaproxy/zap-stable` to a full release tag or digest.
- [ ] Pin the MCP server image to an explicit application version.
- [ ] Verify required add-ons are installed explicitly for the features you plan to use.
- [ ] Avoid mutable `latest` tags in production release manifests.

## 2. Network Boundaries

- [ ] Keep the ZAP API on private networking only.
- [ ] Use HTTPS for non-local MCP access, including internal load balancers; verify the certificate and hostname.
- [ ] Expose the MCP server through a controlled TLS ingress or an encrypted private access path.
- [ ] Add network rules so only trusted clients can reach `/mcp`.
- [ ] Verify an enforcing CNI in Kubernetes, and test both allowed MCP access and blocked ZAP API access from a separate workload. Review additive policies and the actual VPC/pod/service/node address ranges.
- [ ] Configure authorized scan-target egress separately from MCP ingress. Account for required ZAP add-on and background update/version requests; inspect the active passive rule when completion stalls. See the [Helm egress guidance](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#zap-web-egress-and-background-requests).

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
- [ ] Size ZAP using measurements from representative scan workloads.
- [ ] Keep concurrency limits conservative until you have target-specific data.
- [ ] Keep `MCP_PROTECTION_ENABLED=true`.
- [ ] Tune workspace quotas and backpressure to match one real ZAP runtime.
- [ ] Persist `/zap/wrk`.
- [ ] Verify both MCP and ZAP see the same report and automation files. Helm shares the PVC by default; RWO co-locates both pods, while multiple MCP replicas require RWX.
- [ ] For Helm, provision any `zap.persistence.existingClaim` and verify its real access mode and UID/GID permissions.
- [ ] If enabling OpenAPI content staging, use the [single-writer storage configuration](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#openapi-content-import-storage), separate staging from report/automation mounts, and use `Recreate` for MCP. Migrate and verify existing files before changing `workspaceSubPath`; do not assume EFS/NFS or multiple writers are supported.
- [ ] Before moving an older chart-managed workspace to `existingClaim` or RWX, identify its actual live PVC and verify `helm.sh/resource-policy=keep` before changing ownership. Quiesce all writers, verify a consistent backup/restore, migrate required files with the destination's real UID/GID permissions, and validate both workloads before retiring the old volume; follow the [Helm workspace preservation procedure](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md#preserve-the-workspace-before-changing-claims).
- [ ] Prove backup/restore and review PVC retention, namespace deletion and uninstall consequences.

## 6. HA and State Management

- [ ] Use durable queue state for multi-replica MCP deployments.
- [ ] Use shared PostgreSQL JWT revocation for multiple replicas or revocations that must survive restart; in Helm configure `mcp.security.jwt.revocation`, not raw duplicate environment entries.
- [ ] Use Postgres-backed scan history when scan evidence must survive restart, failover, or release handoff.
- [ ] Use Postgres-backed scan-job state when queued jobs are part of release or pilot evidence.
- [ ] Set a sane queue claim lease with `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`.
- [ ] If you expose streamable HTTP through multiple replicas, enable sticky ingress or equivalent client affinity.
- [ ] Test failover by terminating a worker with a claimed running job and confirming another replica recovers polling after lease expiry.
- [ ] Validate that no duplicate scan starts occur during failover or restart.

## 7. Observability and Operations

- [ ] Monitor `/actuator/health`, queue depth, scan durations, and ZAP availability.
- [ ] With the unreleased startup fix, confirm readiness only becomes healthy after ZAP's mandatory outbound settings are applied. Test ZAP starting after MCP and restarting without an MCP restart; preserve independent MCP process liveness.
- [ ] Keep `/actuator/metrics`, `/actuator/prometheus`, and `/actuator/auditevents` on private or authenticated access paths only.
- [ ] Monitor `mcp.zap.http.requests`, `mcp.zap.auth.events`, `mcp.zap.authorization.decisions`, `mcp.zap.tool.executions`, `mcp.zap.queue.jobs`, and `mcp.zap.audit.events`.
- [ ] Monitor `mcp.protection.rate_limited`, `mcp.protection.workspace_quota_rejections`, and `mcp.protection.backpressure_rejections`.
- [ ] Alert on repeated scan retries, stuck `RUNNING` jobs, and authentication failures.
- [ ] Alert on sustained `429` rates so you can distinguish client abuse from capacity saturation.
- [ ] Preserve `X-Correlation-Id` through reverse proxies.
- [ ] Keep structured logs for both the MCP service and ZAP.
- [ ] Retain scan history long enough to cover release sign-off, pilot support, and incident review.

## 8. Pre-Go-Live Validation

- [ ] Smoke-test crawl, attack, report generation followed by MCP readback, and authenticated scanning against a staging target.
- [ ] For Helm, test a clean installation and a migration-bearing upgrade against a disposable database; lint/render checks alone do not test hook ordering.
- [ ] Run `zap_scan_history_list`, `zap_scan_history_release_evidence`, and `zap_scan_history_customer_handoff` after the smoke test. Attach raw JSON only to the internal record, and attach the curated summary to customer-facing packages.
- [ ] Confirm the deployed MCP endpoint requires auth and the ZAP endpoint is not reachable from untrusted networks.
- [ ] Re-run this checklist whenever you change image tags, add-ons, exposure model, or queue backend.
