---
title: "JWT Key Rotation Runbook"
editUrl: false
description: "Rotate the JWT signing secret safely for:"
---
## Purpose

Rotate the JWT signing secret safely for:

- Scheduled security hygiene
- Incident response (suspected key compromise)
- Compliance requirements

## Current Design Notes

- Signing algorithm: `HS256`
- Signing key source: `JWT_SECRET`
- Access token TTL: `JWT_ACCESS_TOKEN_EXPIRATION` (default 1 hour)
- Refresh token TTL: `JWT_REFRESH_TOKEN_EXPIRATION` (default 7 days)
- Revocation backend: `JWT_REVOCATION_STORE_BACKEND` (`in-memory` or `postgres`)

This implementation uses a single active signing key. Rotating `JWT_SECRET` invalidates all existing JWTs signed with the previous secret.

## Preconditions

Before rotation:

1. Confirm all replicas share the same target secret rollout path (K8s secret, ECS task env, Docker Compose env file).
2. Ensure JWT revocation backend is healthy (`postgres` recommended for multi-replica).
3. Confirm `/actuator/health` is green on all MCP replicas.
4. Prepare rollback secret (current key) in secure secret manager.

## Planned Rotation Procedure

### Step 1: Generate New Secret

```bash
openssl rand -base64 32
```

Store it in your secret manager. Do not commit it.

### Step 2: Rollout New Secret to All Replicas

Apply the new `JWT_SECRET` and restart/roll pods so all replicas converge to the same key.

- Kubernetes/Helm: update secret + `helm upgrade`
- Docker Compose: update `.env` + `docker compose up -d --force-recreate`

### Step 3: Validate Post-Rotation

1. Request a token via `/auth/token`.
2. Call `/auth/validate` with new access token (expect `valid=true`).
3. Attempt an old token (issued before rotation) and confirm rejection (`401` or invalid).
4. Verify `/auth/refresh` returns a rotated refresh token and rejects replay on second use.

## Emergency Rotation (Suspected Compromise)

Use immediate rotation when a key leak is suspected:

1. Generate and deploy new `JWT_SECRET` immediately.
2. Scale rollout quickly to avoid mixed-key behavior between replicas.
3. Expect forced re-authentication for all clients.
4. Review logs for unusual `/auth/refresh` and `/auth/revoke` patterns after cutover.

## Rollback Procedure

If post-rotation auth failures are systemic:

1. Re-deploy previous `JWT_SECRET` from secure backup.
2. Re-run validation on `/auth/token` and `/auth/validate`.
3. Open an incident report and schedule a corrected rotation window.

## Operational Checks

After each rotation, confirm:

- Token issuance success rate remains stable
- `401` rate spikes only briefly during cutover
- No sustained auth failure trend in logs/alerts
- Multi-replica deployments show consistent behavior across pods

## Cloud-Specific Notes (AWS/GCP)

- Keep JWT secrets in managed secret stores (AWS Secrets Manager, GCP Secret Manager).
- Inject secrets at runtime (Kubernetes Secrets/External Secrets), not as plaintext in Helm values.
- Coordinate rollout to minimize mixed-version windows.
