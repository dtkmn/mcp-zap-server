---
layout: default
title: Queue Coordinator Leader Election
---

# Queue Coordinator Leader Election

## Objective
Guarantee safe queue dispatch in multi-replica deployments by allowing only one dispatcher leader at a time.

## Default Behavior
- `single-node` backend (default): always leader (local/dev or single replica).
- `postgres-lock` backend: replicas compete for a Postgres advisory lock; only lock holder dispatches/mutates queue state.

## Configuration

```yaml
zap:
  scan:
    queue:
      coordinator:
        backend: single-node # or postgres-lock
        node-id: asg-node-1
        postgres:
          url: jdbc:postgresql://postgres:5432/mcp_zap
          username: mcp
          password: secret
          advisory-lock-key: 861001
          heartbeat-interval-ms: 5000
          fail-fast: false
```

Environment variables:
- `ZAP_SCAN_QUEUE_COORDINATOR_BACKEND`
- `ZAP_SCAN_QUEUE_COORDINATOR_NODE_ID`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_URL`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_USERNAME`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_PASSWORD`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_ADVISORY_LOCK_KEY`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_HEARTBEAT_INTERVAL_MS`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_FAIL_FAST`

## Runtime Semantics
- Only leader dispatches and mutates queue state (`enqueue/cancel/retry/dead-letter replay`).
- Follower replicas return a clear mutation error: "not queue leader".
- On leadership acquisition, replica restores queue state snapshot before processing.
- On leader loss (heartbeat failure), replica stops dispatch immediately.

## Observability
Metrics emitted:
- `asg.queue.leadership.is_leader` (gauge)
- `asg.queue.leadership.transitions{event=acquired|lost}` (counter)
- `asg.queue.leadership.failures{type=acquire|heartbeat}` (counter)

Logs emitted:
- leadership acquired/released
- acquire/heartbeat failure warnings

## Failure Test Scenario (2+ replicas)
1. Start 2 replicas with shared queue store + `postgres-lock` coordinator.
2. Verify only one replica reports leader gauge as `1`.
3. Submit queue jobs; confirm no duplicate dispatch/start.
4. Terminate current leader process.
5. Verify follower acquires leadership within heartbeat window and processing resumes.

## AWS Quick Reference

Recommended baseline for AWS:

1. Run `mcp-zap-server` on EKS with at least 2 replicas.
2. Use Amazon RDS PostgreSQL for shared scan-job state and advisory-lock coordination.
3. Run Flyway-managed schema migrations before MCP replicas scale out.
4. Set:
   - `ZAP_SCAN_JOBS_STORE_BACKEND=postgres`
   - `ZAP_SCAN_QUEUE_COORDINATOR_BACKEND=postgres-lock`
5. Provide a unique node id per pod:
   - `ZAP_SCAN_QUEUE_COORDINATOR_NODE_ID=$(HOSTNAME)`
6. Verify leader failover by deleting leader pod and observing transition metrics/logs.

Helm users can start from `helm/mcp-zap-server/values-ha.yaml` and deploy with:

```bash
helm upgrade --install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap-prod --create-namespace \
  --values /tmp/mcp-zap-values-ha.yaml
```

## Troubleshooting
- MCP replicas fail with missing-schema errors:
  - run Flyway migrations before scaling replicas
  - verify the expected shared tables exist (`scan_jobs`, `jwt_token_revocation`)
- No leader elected:
  - verify coordinator backend and Postgres connectivity
  - verify lock key consistency across replicas
- Frequent leader flaps:
  - increase heartbeat interval
  - inspect Postgres network stability
- Mutation rejected as follower:
  - route writes to leader replica
  - or retry via load balancer with leader-aware routing
