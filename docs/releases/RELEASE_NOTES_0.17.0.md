# Release Notes and Upgrade Guide - Version 0.17.0

> Distribution notice: the published `v0.17.0` release has no Desktop download
> assets, and its image-release workflow did not complete. Use `v0.17.1` only
> after verifying its images and downloads; see the
> [0.17.1 upgrade guide](RELEASE_NOTES_0.17.1.md). The feature and migration
> descriptions below retain this version's source behavior.

This guide describes MCP ZAP Server `v0.17.0`. Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and the
release-image workflow before installing it. Source metadata, a tag or a local
build does not establish that images, registry metadata or Desktop downloads
are available. Use a version with verified available images before deploying.

Use Linux AMD64 or ARM64 images from GHCR or Docker Hub only after the matching
release has published them. Obtain the actual registry digest for `v0.17.0`
when pinning a deployment; do not reuse a previous version's digest.

## What Changes For Users

- Empty findings describe currently recorded alerts. An empty list does not
  establish that a crawl or attack finished, passive analysis drained, coverage
  was complete, or the target is safe. Useful findings reads remain available
  while work is in progress.
- Explicit operator target denies take precedence over optional localhost or
  private-network allowances and overlapping allow rules. Hostname rules are
  case-insensitive, and equivalent numeric IP literals are compared as addresses.
- Guided next-action suggestions respect the caller's effective tool scopes.
  Tool permissions remain separate from authorization to scan a target.
- MCP ZAP Connect `0.5.0` adds readiness checks before a requested crawl or scan,
  recovery guidance and local Claude Code support alongside Codex and Cursor.
  Readiness checks do not start a scan or repair the environment automatically.
- Claude Desktop connector `0.1.0` provides local prerequisite, setup and
  readiness tools around the shared Docker scanner. Installation alone does not
  start containers. The bundle preserves an existing installation's settings,
  credentials and selected scanner version.
- The healthcheck build uses Go `1.27.3`, and the standalone extension example
  aligns with the application's patched Boot `4.1.1` / Spring `7.0.9` baseline.
  The Java 25 / Debian 13 base includes Expat `2.9.0-1~deb13u1` for Linux AMD64
  and ARM64; the separate zlib finding remains.
  See [dependency security guidance](../src/content/docs/reference/dependency-security.md)
  for package scope and remaining upstream findings.

## Upgrade From v0.16.0

Use the server image, chart `0.17.0` and source metadata from the same published
release. New source defaults select `v0.17.0`; existing `.env` image choices,
Helm overrides and copied client settings are preserved. Change them
deliberately after verifying publication.

Preserve API keys, private MCP client settings, report workspaces and database
data. Flyway migrations remain **V1–V8**; this version adds no database migration
or new workspace layout. Gateway Core and its WebFlux adapter remain `0.11.0`.
The plugin (`0.5.0`), Desktop connector (`0.1.0`) and scanner (`0.17.0`) have
independent versions.

### Review Explicit Target Denies

With URL validation enabled, `ZAP_URL_BLACKLIST` rules win over
`ZAP_ALLOW_LOCALHOST`, `ZAP_ALLOW_PRIVATE_NETWORKS` and `ZAP_URL_WHITELIST`.
The explicit blacklist is blank by default. Independent address guardrails
still require opt-ins for localhost/private targets and reject unspecified,
multicast and link-local addresses. Disabling URL validation retains its
existing behavior of bypassing these checks.

An explicitly supplied blacklist remains authoritative, including a safety list
copied from an older default. To permit a development target, enable the
appropriate opt-in and remove only the explicit deny entries you intend to
lift. Do not discard custom restrictions. The property
`zap.scan.url.blacklist` and Helm value `mcp.scan.url.blacklist` follow the same
precedence.

For Compose, `.env` interpolation alone does not forward `ZAP_URL_BLACKLIST` to
the MCP container. Keep your service `env_file` or explicit `environment`
override. A blank `environment` value overrides the corresponding `env_file`
value and can erase operator restrictions. Check the effective configuration
with `docker compose config` before starting the stack. See the
[security policy](../../SECURITY.md#url-validation--access-control) for an example.
URL preflight validation does not provide engine-side network isolation.

### Interpret Findings And Reports

Wait for the requested crawl or attack to finish, then wait for passive analysis
to drain before generating a final report. Findings counts are observations of
currently recorded evidence, not lifecycle or safety assertions. Existing
caller/workspace/target evidence restrictions remain in effect.

This version retains the scanner-aware findings and CI contracts introduced in
`v0.16.0`: findings snapshots are v3 and gate results are v2. Upgrades from older
versions must still follow the
[v0.16.0 baseline migration](RELEASE_NOTES_0.16.0.md#migrate-ci-evidence-and-baselines).
Matching scanner identity does not prove equal crawl coverage or authentication.

## Install And Connect

Use [Self-Serve First Run](../getting-started/SELF_SERVE_FIRST_RUN.md) for Docker
setup, then the client's own connection guide. Confirm the published image is
available before using the `v0.17.0` default. The curated guided quick-start
uses the `*` tool grant; it includes active testing, authentication setup and API
imports. Request only work you are authorized to perform, and use per-client
scope restrictions when appropriate. Existing grants are preserved.

### Claude Desktop Downloads

When available, the normal scanner release contains these four files:

```text
mcp-zap-connect-claude-desktop-0.1.0.mcpb
mcp-zap-connect-claude-desktop-0.1.0.mcpb.sha256
mcp-zap-connect-claude-desktop-0.1.0-setup.md
mcp-zap-connect-claude-desktop-0.1.0-setup.md.sha256
```

Download the matching bundle, setup guide and checksums from that specific
release. Verify both checksums before installation, and follow the
[Desktop setup guide](../../packaging/claude-desktop/SETUP.md). A bundle embeds
its own scanner setup resources and fresh-install default; downloading it from
a newer release does not rewrite an existing installation's selected version.
The `0.1.0` bundle in this source selects `v0.17.0` for fresh installations.

Codex, Cursor and Claude Code source-plugin installation remains separate from
Desktop downloads and public directory approval. Use the
[agent-guided setup instructions](../../plugins/mcp-zap-connect/README.md) for
the selected client. The optional unsigned Apple Silicon CLI preview has its
own [installation and availability guidance](../getting-started/MACOS_PACKAGE.md);
Desktop downloads do not imply a signed or notarized Mac scanner package.
