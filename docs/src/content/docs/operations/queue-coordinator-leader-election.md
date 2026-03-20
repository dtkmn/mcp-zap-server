---
title: "Queue Coordinator and Worker Claims"
editUrl: false
description: "Understand claim-based HA queue execution and the optional coordinator leadership signals."
---
## Objective

Guarantee safe multi-replica queue execution by using durable worker claims for dispatch ownership while keeping the coordinator backend available for node identity, optional maintenance leadership, and observability.

## Default Behavior

- `single-node` backend: single-process runtime with a stable local worker identity
- `postgres-lock` backend: replicas still compete for a Postgres advisory lock, but normal queue dispatch no longer depends on that lock

That last point matters. If you still think only one leader can dispatch scans, your mental model is behind the code.

## Configuration

```yaml
zap:
  scan:
    queue:
      claim-lease-ms: 15000
      coordinator:
        backend: single-node
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

- `ZAP_SCAN_QUEUE_CLAIM_LEASE_MS`
- `ZAP_SCAN_QUEUE_COORDINATOR_BACKEND`
- `ZAP_SCAN_QUEUE_COORDINATOR_NODE_ID`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_URL`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_USERNAME`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_PASSWORD`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_ADVISORY_LOCK_KEY`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_HEARTBEAT_INTERVAL_MS`
- `ZAP_SCAN_QUEUE_COORDINATOR_POSTGRES_FAIL_FAST`

## Runtime Semantics

- any replica can admit queued jobs into shared durable `scan_jobs` state
- any replica can serve durable read paths and job mutations
- any replica can claim queued work, start scans, and poll running progress once it owns the durable claim
- running scans keep a renewable claim lease
- if a worker disappears and the lease expires, another replica can recover polling ownership from the stored ZAP scan ID
- if a late start result arrives after ownership moved, the stray scan is stopped instead of being adopted twice
- queue status responses surface claim owner and claim expiry

## Observability

Metrics:

- `asg.queue.leadership.is_leader`
- `asg.queue.leadership.transitions`
- `asg.queue.leadership.failures`
- `asg.queue.claim.events`

Queue claim events include:

- `queued_claimed`
- `running_recovered`
- `expired_claim_recovered`
- `renewed`
- `conflict`
- `late_result_cleanup`

## Failure Test Scenario

1. start 2 replicas with shared queue state
2. submit queued jobs and confirm no duplicate scan starts
3. terminate the worker currently polling a running job
4. wait for the claim lease to expire
5. verify another replica recovers polling without starting a duplicate scan

If you use `postgres-lock`, also verify the coordinator leadership metrics still behave as expected.
