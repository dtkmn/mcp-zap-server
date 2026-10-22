# Release Notes and Upgrade Guide - Version 0.17.1

This source selects MCP ZAP Server `v0.17.1`. Verify the matching
[GitHub release](https://github.com/dtkmn/mcp-zap-server/releases), Desktop
downloads and Linux AMD64/ARM64 image publication before installing it. A tag,
source version or published GitHub release alone does not establish that its
images or MCP Registry version are available. Use a release with verified
available images until the matching distribution is ready.

The published `v0.17.0` release lacks the Desktop files and its image-release
workflow did not complete. This patch carries forward its product behavior and
adds a draft-release preflight and explicit asset-first publishing instructions.
It does not resolve the remaining zlib finding. See the
[dependency security guide](../src/content/docs/reference/dependency-security.md)
for affected packages and deployment considerations.

## Upgrade Requirements

Use image `v0.17.1`, chart `0.17.1` and source metadata from the same published
release. Obtain that version's actual registry digest when pinning deployments;
do not reuse another version's digest. Source defaults select `v0.17.1` for a
fresh installation. Existing `.env` image choices, Helm overrides, credentials,
client settings, database data and workspaces are preserved. Change an existing
installation's selected version deliberately after verifying publication.

The [0.17.0 upgrade requirements](RELEASE_NOTES_0.17.0.md#upgrade-from-v0160)
also apply: explicit operator target denies are authoritative, findings reads
describe recorded evidence without proving scan completion, and guided next
actions respect the caller's tool scopes. Preserve existing explicit target
restrictions and custom Compose `env_file` settings. Avoid blank `environment`
overrides that erase restrictions supplied through `env_file`.

Flyway migrations remain **V1–V8**. Gateway Core and its WebFlux adapter remain
`0.11.0`; this patch adds no database migration or workspace-layout change.
MCP ZAP Connect plugin `0.5.0`, Desktop connector `0.1.0` and scanner `0.17.1`
are independently versioned. The Go `1.27.3`, Spring and Expat source updates
described in the previous guide are retained.

## Install And Connect

Follow [Self-Serve First Run](../getting-started/SELF_SERVE_FIRST_RUN.md) for
Docker setup and the [agent-guided setup instructions](../../plugins/mcp-zap-connect/README.md)
for the selected client. Confirm both image availability and your client's
authenticated connection separately. Readiness and an empty findings list do
not prove that an authorized crawl or scan has completed.

### Claude Desktop Downloads

The normal scanner release must contain this complete matching set:

```text
mcp-zap-connect-claude-desktop-0.1.0.mcpb
mcp-zap-connect-claude-desktop-0.1.0.mcpb.sha256
mcp-zap-connect-claude-desktop-0.1.0-setup.md
mcp-zap-connect-claude-desktop-0.1.0-setup.md.sha256
```

Download the four files from the same release. Check the bundle and guide
against their adjacent SHA256 files before following the
[Desktop setup guide](../../packaging/claude-desktop/SETUP.md). The `0.1.0`
bundle built from this source selects scanner `v0.17.1` for fresh installations;
an existing installation retains its selected image and settings.

### Publishing From Source

Follow the [publishing procedure](PUBLISHING.md): save the release as a draft,
attach the tagged Desktop build, review the four downloaded files and run the
read-only preflight before publishing. Publication locks the assets and starts
the image workflow; verify its result and image availability separately. Registry
publication remains a separate step.
