---
title: "Multi-Replica Deployment Requirements"
editUrl: false
description: "State, session affinity, and recovery requirements for deployments with multiple MCP replicas."
---
The public repository provides a single-node local Compose stack and a Helm
chart. It does not include a runnable local HA Compose simulation: the legacy
`ha.sh` helper depends on `docker-compose.ha.yml` and an nginx template that are
not distributed here. Do not use that helper as an installation path.

For a local evaluation, use the [first-run guide](../../getting-started/self-serve-first-run/).
For Kubernetes deployment, use the [Helm guide](https://github.com/dtkmn/mcp-zap-server/blob/main/helm/mcp-zap-server/README.md).

## Requirements Before Scaling

Multiple MCP replicas require deployment-specific configuration and validation:

- Use shared Postgres-backed scan-job state and apply its migrations before
  starting workers. Worker claims govern dispatch and recovery; coordinator
  leadership alone does not provide shared queue state.
- Keep worker node identities unique and choose a claim lease suitable for the
  deployment. See [Queue Coordinator and Worker Claims](../queue-coordinator-leader-election/).
- Configure ingress affinity for Streamable HTTP sessions, which are stored in
  memory on each MCP replica. Shared queue state does not make these sessions
  portable between replicas.
- Use shared JWT revocation state when JWT authentication must remain consistent
  across replicas. See the [JWT rotation runbook](../../security-modes/jwt-key-rotation-runbook/).
- Use durable scan history when evidence must survive a restart or worker loss.
  See [Scan History Ledger](../scan-history-ledger/).
- Keep one ZAP runtime per trust boundary. Adding MCP replicas does not replicate
  ZAP's scanner state or remove ZAP as a single point of failure.

## Recovery Validation

Before accepting a multi-replica deployment, verify that a worker can recover
an existing scan after another worker loses its claim, without starting a
second scan. Also test session affinity, authentication, evidence retention,
and the deployment's database and ZAP recovery procedures.

Use the [Production Readiness Checklist](../production-checklist/) and
[Production Simulation Runbook](../production-simulation-runbook/) to record
those checks. The default local Compose stack does not validate this topology.
