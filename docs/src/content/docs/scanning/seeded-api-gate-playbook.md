---
title: "Seeded API Gate Playbook"
description: "Adopt seeded API traffic, reviewed baselines, and enforcement without turning CI security into noise."
---
Use this playbook when an API path matters more than a crawler-discovered page.
Spiders do not invent JSON `POST` bodies, login flows, tenant IDs, or business
payloads. Seed requests teach the CI gate which representative traffic must pass
through ZAP before findings and reports are captured.

The goal is not to prove the whole app is secure. The goal is to create a
reviewable, repeatable security gate for important API behavior.

## Adoption Flow

1. Start in evidence mode.

   ```yaml
   baseline-mode: seed
   fail-on-new-findings: "false"
   run-active-scan: "false"
   ```

   This proves the workflow, target, seed traffic, report copy, and artifact
   upload without blocking delivery.

2. Review the uploaded artifacts.

   Download `zap-artifacts` and inspect:

   - `seed-requests-results.json`
   - `current-findings.json`
   - `findings-summary.md`
   - `gate-summary.md`
   - `gate-metadata.json`
   - `artifact-manifest.json`
   - `reports/zap-report.html`

3. Commit the reviewed baseline.

   Copy the accepted `current-findings.json` into the path configured by
   `baseline-file`, for example:

   ```text
   .zap/baselines/main-findings.json
   ```

   This says "we understand and accept this current finding state for now." It
   does not say the application is secure.

4. Turn on enforcement.

   ```yaml
   baseline-mode: enforce
   fail-on-new-findings: "true"
   ```

   Enforcement should block net-new findings and missing baseline evidence. A
   gate that passes because the baseline vanished is not a gate.

5. Improve seeds deliberately.

   Add seed requests when they increase meaningful coverage. Review every seed
   change the same way you would review a test that can block release.

## Good Seed Checklist

A good seed request is boring, stable, and tied to product behavior.

- It hits an important path, such as checkout, upload, search, bid request,
  tenant-scoped read, auth failure, or policy decision.
- It uses a realistic payload shape.
- It has an explicit expected status, such as `200`, `204`, `400`, `401`, or
  `403`.
- It does not require production secrets.
- It does not depend on public third-party services.
- It avoids random IDs unless the app requires them and the result remains
  stable.
- It exercises a path a normal crawler would miss.
- The reason for the seed is obvious from the `name`.

Examples:

- `valid-bid-request` expects `200`
- `privacy-filtered-bid-request` expects `204`
- `missing-device-rejected` expects `400`
- `unauthenticated-profile-read` expects `401`
- `cross-tenant-report-denied` expects `403`

## Bad Seed Smells

Do not add seeds that make the gate look stronger while proving little.

- Health endpoints only.
- Fake payloads the app would never accept in normal use.
- Requests that need real customer data.
- Requests with bearer tokens, cookies, passwords, or API keys in the seed file.
- Requests that pass or fail depending on timing, external vendors, or random
  background state.
- Seeds that create findings nobody can explain.
- Seeds added only to make coverage numbers look better.

Blunt rule: if the team cannot answer "what did this seed prove?", the seed is
theatre.

## Seed File Shape

Keep seed files in the caller repository, usually under `.zap/seed-requests/`.
Prefer internal CI service URLs such as `http://app:8080`. If a seed must use
HTTPS through ZAP, configure ZAP CA trust deliberately in the app/client path.
Do not assume HTTPS traffic was meaningfully inspected just because the request
was attempted through the proxy.

```json
{
  "requests": [
    {
      "name": "valid-api-request",
      "method": "POST",
      "url": "http://app:8080/api/resource",
      "headers": {
        "Content-Type": "application/json"
      },
      "body": {
        "example": true
      },
      "expectedStatus": [200, 201, 204]
    }
  ]
}
```

Then wire it into the GitHub action:

```yaml
with:
  target-url: http://app:8080
  seed-requests-file: .zap/seed-requests/main.json
  baseline-file: .zap/baselines/main-findings.json
  baseline-mode: seed
  fail-on-new-findings: "false"
```

The helper sends the real request through the ZAP proxy. The
`seed-requests-results.json` contract does not include request headers or
bodies, and its stored request/proxy URLs are sanitized by removing userinfo and
reducing query strings to `?redacted`.

The full uploaded artifact bundle is still review-required. `current-findings.json`
and `reports/zap-report.html` can contain target URLs, alert evidence, response
details, and app-specific information. Do not attach raw artifacts to a
customer-facing package until someone has reviewed them.

## Artifact Review Checklist

Before accepting a baseline, check the artifact like a release reviewer, not a
tourist.

- `seed-requests-results.json`
  - all expected seed requests are present
  - every important seed has `ok: true`
  - `failure_count` is `0`
  - URLs do not expose secrets
- `current-findings.json`
  - target and finding counts make sense
  - findings are not only from a health endpoint unless this is explicitly a
    wiring smoke
  - noisy findings are understood before suppressing them
- `gate-summary.md`
  - baseline mode matches the intended run
  - seed request count is non-zero when this is an API proof
  - active scan status matches your rollout choice
- `zap-report.html`
  - report target is the app under test
  - report is not empty or from the wrong service
- `artifact-manifest.json`
  - expected files are present
  - report artifact path is included when report generation succeeds

## What A Failure Means

Do not treat every red run as "the tool is broken." Classify the failure first.

| Signal | Likely Meaning | First Response |
| --- | --- | --- |
| Seed request returns unexpected status | App behavior changed or seed expectation is wrong | Reproduce the request locally, then fix app or seed |
| Seed request cannot connect | Compose/network/service readiness issue | Check service names, ports, and health checks |
| Missing baseline in `enforce` mode | Baseline path is wrong or was not committed | Restore baseline or rerun in `seed` mode intentionally |
| New findings appear | Better coverage, real regression, or ZAP rule change | Review `findings-diff.json` before changing the baseline |
| Report missing | ZAP report path or shared workspace issue | Check `artifact-manifest.json` and action logs |
| Artifact contains wrong target | Workflow points at the wrong service | Fix `target-url`, seed URLs, or compose service names |

## Updating Seeds And Baselines

Seed changes and baseline changes should be separate review decisions.

- Add or edit seed requests in one pull request.
- Run in `seed` mode and inspect the new artifacts.
- If new findings are real but accepted, commit a baseline update in a reviewed
  follow-up.
- If a seed is flaky, remove or fix it before enabling enforcement.
- If a finding is temporarily accepted, prefer a documented suppression over
  hiding it in an unexplained baseline rewrite.

Better seeds can reveal more findings. That is not bad. The problem is accepting
new findings without understanding why they appeared.

## When To Enable Active Scan

Start with passive evidence and seeded traffic. Turn on active scan only when:

- the target environment is disposable or explicitly approved for attack traffic
- the app can tolerate aggressive requests
- the team understands the runtime cost
- seed and baseline behavior is already stable
- rollback is documented

Active scan is powerful, but using it too early makes adoption noisy. Noise is
how security gates get removed.

## Minimum Team Process

For a team rollout, require these rules:

- one owner for seed files
- one owner for baseline acceptance
- pull request review for seed and baseline changes
- artifacts attached to the first pilot issue or release note
- `seed` mode before `enforce`
- `fail-on-new-findings: "false"` before `true`
- no secrets in seed files
- no mutable image tags for the MCP server image

The product value is the loop: representative traffic, reviewed evidence,
accepted baseline, and regression blocking. ZAP is the engine. The gate is the
operating model.
