# Publish a scanner release

Publish in this order:

**Reviewed commit → tag → draft release → Desktop attachment → downloaded-file
preflight → publish → verify images and Registry separately.**

**Do not click Publish release after creating the tag.** Save a draft first.
The **Release** workflow runs after publication and checks that the Desktop files
already exist. It does not package or attach them. A successful Desktop preview
build produces Actions artifacts; it does not attach public release downloads.

## Prerequisites

- The final release commit is on `main`, reviewed, and has passed its required
  checks. Use its approved full commit SHA throughout these steps.
- Scanner/application, chart, Registry-source and fresh-install defaults match
  the scanner release version. Desktop and plugin versions are independent.
- GitHub immutable releases are enabled. GitHub CLI is already authenticated
  with access to the repository and its draft releases; Python 3 is available.
- The release notes explain supported behavior, compatibility, upgrade steps and
  known limitations. Do not claim images, Registry entries or downloads are
  available before verifying each publication.

Run commands from a repository checkout containing the approved commit. Replace
the commit placeholder with the approved full SHA. The creation, workflow and
publication commands below change remote state; run each only when ready for
that step.

```bash
REPOSITORY=dtkmn/mcp-zap-server
RELEASE_TAG=v0.17.1
RELEASE_COMMIT=REPLACE_WITH_APPROVED_FULL_COMMIT_SHA
```

## 1. Create the tag and save a draft

Verify the selected commit and required checks before creating a new tag. Check
the Git remote points to the intended repository. Do not move or reuse an
existing published tag.

```bash
[[ "$RELEASE_COMMIT" =~ ^[0-9a-f]{40}$ ]] &&
git tag "$RELEASE_TAG" "$RELEASE_COMMIT" &&
git push origin "refs/tags/$RELEASE_TAG"
```

Create the release as a **draft**, using that existing tag:

```bash
gh release create "$RELEASE_TAG" --repo "$REPOSITORY" --verify-tag --draft \
  --title "$RELEASE_TAG" \
  --notes-file "docs/releases/RELEASE_NOTES_${RELEASE_TAG#v}.md"
```

In the GitHub web interface, the equivalent is **Save draft**, not **Publish
release**. Use the prerelease setting for a scanner prerelease; the independently
versioned Desktop connector being a preview does not require a stable scanner
release to be marked as a prerelease.

## 2. Attach the tagged Desktop package

Dispatch **Claude Desktop MCPB** from **main**, providing the existing draft's
scanner tag in `draft_release_tag`:

```bash
gh workflow run claude-desktop-mcpb.yml --repo "$REPOSITORY" --ref main \
  --raw-field "draft_release_tag=$RELEASE_TAG"
```

Open that run in GitHub Actions. Wait for **both** the build and **Attach Desktop
assets to existing scanner draft** jobs to succeed. Check the run summary shows
the selected tag and approved commit. The workflow runs trusted workflow code
from `main` and packages the tagged source; the tag's scanner version must match
`build.gradle` and its source must be reachable from `main`.

The draft must contain all four files, using the Desktop version from the tagged
package metadata. For connector `0.1.0`, they are:

```text
mcp-zap-connect-claude-desktop-0.1.0.mcpb
mcp-zap-connect-claude-desktop-0.1.0.mcpb.sha256
mcp-zap-connect-claude-desktop-0.1.0-setup.md
mcp-zap-connect-claude-desktop-0.1.0-setup.md.sha256
```

**Stop if any file is missing, empty or incomplete.** A preview artifact in
Actions is not an attached release asset. The attachment workflow requires an
existing mutable draft and refuses to overwrite assets. If a failed run left
partial files, inspect that draft and deliberately resolve the conflicting files
before retrying. It does no automatic cleanup or replacement.

## 3. Review downloads and run the preflight

Review the attached setup guide for the selected scanner version and inspect the
packaging run's source and outputs. Immediately before publication, run:

```bash
python3 scripts/verify-release-draft.py --repo "$REPOSITORY" \
  --tag "$RELEASE_TAG" --commit "$RELEASE_COMMIT"
```

The helper makes only GitHub GET requests. It verifies the tag, approved commit,
ancestry from `main`, tagged scanner and Desktop versions, mutable draft state,
and all four complete asset records. It downloads all four files into a private
temporary directory, checks their sizes, available GitHub digests and both exact
filename checksums, then removes the downloads. It rechecks source, draft state
and asset metadata to detect changes during verification.

**Any `STOP` or nonzero result blocks publication.** Inspect and fix the draft
while it is still mutable, then rerun the check. A `PASS` is a point-in-time
asset check, not a source review, a reproducible-build attestation, or proof that
images and the Registry are published. It cannot prevent someone from bypassing
this procedure through GitHub's Publish button.

## 4. Publish only through the successful preflight

After reviewing the guide and packaging run, use this guarded command so a
failed preflight cannot continue to publication:

```bash
python3 scripts/verify-release-draft.py --repo "$REPOSITORY" \
  --tag "$RELEASE_TAG" --commit "$RELEASE_COMMIT" &&
gh release edit "$RELEASE_TAG" --repo "$REPOSITORY" --draft=false
```

Publication makes the assets immutable and triggers **Release**. Verify that run
finishes its tests, SBOM generation and AMD64/ARM64 image publication to both
GHCR and Docker Hub. Check actual versioned image manifests and public downloaded
checksums before announcing installation availability. MCP Registry publication
is a separate operation; verify its version and image references independently.

## If an incomplete release is already published

Published immutable releases cannot receive missing assets. Rerunning **Release**
or the draft attachment workflow cannot repair that condition. Preserve the tag
and prepare a new scanner version, then follow the draft-first sequence above.
Do not weaken the asset guard or disable immutability to work around it. Deleting
an immutable release does not make its tag name reusable. See
[GitHub's immutable release rules](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).

See the [Desktop package guide](../../packaging/claude-desktop/README.md#prepare-downloads-for-a-normal-scanner-release)
for packaging details and the [release archive](README.md) for version-specific
upgrade guides.
