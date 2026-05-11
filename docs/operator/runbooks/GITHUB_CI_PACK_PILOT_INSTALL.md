# GitHub CI Pack Pilot Install Runbook

Use this runbook when turning the bundled GitHub Actions security gate into a
pilot install for another repository.

The goal is not to prove every enterprise workflow. The goal is to give a team
one copyable GitHub path that starts the gateway, runs a ZAP-backed scan through
MCP, emits evidence, and fails or warns on net-new findings.

## What Ships Today

- Composite action: `.github/actions/zap-security-gate`
- Copyable workflow: `examples/github-actions/zap-security-gate.yml`
- Validation workflow: `.github/workflows/zap-security-gate-juice-shop.yml`
- Optional webhook callback action: `.github/actions/zap-webhook-callback`
- Artifact contracts: `ci_gate_result/v1`, `ci_gate_findings_snapshot/v1`,
  `ci_gate_findings_diff/v1`, and `ci_gate_artifact_manifest/v1`

## Preflight

1. Choose a target URL that the ZAP container can reach.
2. Choose whether the action starts the bundled stack or points at an existing
   MCP server.
3. Choose a release image tag or digest for `mcp-server-image`.
4. Decide whether the first run should be crawl-only:
   `run-active-scan: "false"` is safer for onboarding.
5. Decide whether the first run should block pull requests:
   `fail-on-new-findings: "false"` is better for the first baseline run.
6. Use `baseline-mode: seed` until the first baseline is reviewed. Switch to
   `baseline-mode: enforce` when the gate is meant to block on missing or
   mistyped baseline evidence.

Do not use mutable image tags such as `latest`, `dev`, `main`, `nightly`,
`edge`, or `canary`. The action rejects them because a pilot gate must be
reproducible.

## 30-Minute Pilot Checklist

Use this as the first-pass install checklist. If you cannot finish these steps
without custom engineering, the pack is not ready for that pilot yet.

- [ ] Pick one repository and one low-risk target service.
- [ ] Copy `examples/github-actions/zap-security-gate.yml` into
  `.github/workflows/zap-security-gate.yml`.
- [ ] Replace the local action reference with the release action ref, such as
  `dtkmn/mcp-zap-server/.github/actions/zap-security-gate@<release-tag>`,
  or vendor `.github/actions/zap-security-gate` into the pilot repository.
- [ ] Copy `examples/github-actions/docker-compose.app-under-test.yml` if the
  target service should start inside the workflow.
- [ ] Replace the example `app` image with the pilot service image.
- [ ] Set `target-url` to the compose service address, such as `http://app:80`.
- [ ] Pin `mcp-server-image` to a release tag or digest.
  The action rejects the literal `<release-tag>` placeholder.
- [ ] Keep `run-active-scan: "false"` for the first onboarding run.
- [ ] Keep `baseline-mode: seed` until the first baseline is reviewed.
- [ ] Keep `fail-on-new-findings: "false"` until the first baseline is reviewed.
- [ ] Run the workflow manually.
- [ ] Upload or attach `.zap-artifacts` to the pilot issue.
- [ ] Review `current-findings.json`, `gate-metadata.json`, and
  `artifact-manifest.json`.
- [ ] Commit a baseline only after the pilot owner accepts the current security
  state.

## Verify This Repository Before Installing

Run the local pack verification command:

```bash
./bin/github-ci-pack-verify.sh
```

For a heavier proof that also builds the current Docker image:

```bash
./bin/github-ci-pack-verify.sh --with-image-build
```

This command verifies:

- action shell entrypoints parse
- CI helper Python contracts pass
- GitHub CI compose wiring keeps ZAP and MCP on the shared workspace
- the CI stack forces the expert tool surface needed for snapshot and diff
  contracts
- Spring AI `2.0.0-M5` resolves against the managed Spring Boot `4.0.6`
  runtime
- the Dockerfile selects the executable app JAR instead of sidecar artifacts

The Spring AI/Spring Boot check is intentional. Spring AI `2.0.0-M5`
transitively requests Spring Boot `4.1.0-RC1`, while this repository currently
manages Spring Boot to `4.0.6`. That is acceptable only as long as the build,
image packaging, and CI gate proof stay green. Do not hide this mismatch from
operators.

## Install In A Pilot Repository

1. Copy `examples/github-actions/zap-security-gate.yml` into the target
   repository as `.github/workflows/zap-security-gate.yml`.
2. Replace the local action reference with the release tag:

   ```yaml
   uses: dtkmn/mcp-zap-server/.github/actions/zap-security-gate@<release-tag>
   ```

3. Set `mcp-server-image` to the same release tag or a digest.
4. If the app under test is not externally reachable, add a compose override
   and include the app service in `compose-services`. Start from
   `examples/github-actions/docker-compose.app-under-test.yml` and replace the
   example `nginx:1.27-alpine` image with the pilot service image.
5. Start with:

   ```yaml
   run-active-scan: "false"
   baseline-mode: seed
   fail-on-new-findings: "false"
   ```

6. Run the workflow manually and inspect `.zap-artifacts`.
7. Commit the generated `current-findings.json` as the accepted baseline only
   after review.
8. Enable blocking mode only after the baseline and suppressions are agreed:
   set `baseline-mode: enforce` and `fail-on-new-findings: "true"` together.

## Rollback

If the gate blocks unexpectedly:

1. Set `fail-on-new-findings: "false"` to keep evidence generation while
   unblocking delivery.
2. Keep uploading `.zap-artifacts`; do not remove the gate completely unless the
   action cannot start.
3. Pin back to the last known good action release and image tag.
4. Review `gate-metadata.json`, `findings-diff.json`, and
   `artifact-manifest.json` before changing the baseline.

## Evidence To Collect

Attach these to the pilot issue or release note:

- workflow run URL
- `gate-metadata.json`
- `current-findings.json`
- `findings-diff.json` when a baseline was used
- `artifact-manifest.json`
- copied report artifact under `.zap-artifacts/reports`

## Exit Criteria

The GitHub CI pack is credible for a pilot when:

- a fresh repository can copy the workflow and run it without custom glue
- the target is scanned through the MCP-backed gateway, not raw ZAP scripts
- artifacts are uploaded and readable
- the baseline/diff behavior is understood by the pilot operator
- rollback is documented before blocking mode is enabled
