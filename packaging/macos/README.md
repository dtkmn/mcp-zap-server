# Build and release the macOS preview

Maintainer instructions for the unsigned Apple Silicon HTTP preview. User
installation and client setup are in the [Mac package guide](../../docs/getting-started/MACOS_PACKAGE.md).

## Build locally

Use an Apple Silicon Mac with JDK 25, Python 3 and the project's Gradle wrapper:

```bash
./gradlew bootJar --no-daemon --stacktrace
python3 packaging/macos/build-package.py --server-jar build/libs/mcp-zap-server-VERSION.jar
```

Replace `VERSION` with the version in `build.gradle`. The builder verifies the
pinned binary/source inputs, runtime dependency notices, retained ZAP inventory
and vendor Java signature. Review the corresponding sources and notices before
changing the component or dependency pins. See [source delivery](SOURCE_DISTRIBUTION.md).

`--cache-dir` reuses downloads after checksum verification. `--output-dir`
selects a fresh output directory; existing package outputs are not overwritten.
The default output directory is `build/macos/`.

The release set contains exactly four files:

- `mcp-zap-server-VERSION-macos-arm64-preview.tar.gz`
- `mcp-zap-server-VERSION-macos-arm64-preview.tar.gz.sha256`
- `mcp-zap-server-VERSION-macos-arm64-preview-sources.tar.gz`
- `mcp-zap-server-VERSION-macos-arm64-preview-sources.tar.gz.sha256`

The source companion identifies the binary archive's checksum and includes
matching source/build materials and notices. Publish all four files together.
The optional ZAP Import/Export add-on remains excluded until its corresponding
source mapping is reviewed; do not add it without updating the distribution inputs.

## GitHub Actions

The **macOS Package** workflow builds this set on an Apple Silicon `macos-15`
runner for relevant pull requests. Once merged to `main`, it is also available
under **Actions → macOS Package → Run workflow**.

Leave `draft_release_tag` empty for a build of the selected branch without
release attachment. The resulting Actions artifact contains a ZIP wrapper
around the four original files and expires after 14 days. Download access
requires GitHub access; public onboarding uses GitHub Releases.

The workflow runs the package builder's validation. Normal CI owns application
tests. A hosted packaging build does not establish desktop service behavior or
a clean browser-downloaded installation.

## Attach before publishing

This repository uses immutable releases. Prepare the assets before publication:

1. Merge the reviewed package and version changes into `main`. Create the
   matching `vVERSION` tag at that commit and save a draft release for the
   existing tag. A draft's uncreated tag cannot be used by this workflow.
2. Run **macOS Package** from **main**, setting `draft_release_tag` to that tag.
   The workflow verifies project version, main ancestry and mutable draft
   identity, builds the tagged commit and attaches the four files. It creates
   no tag or release and does not publish.
3. Download the final set, verify both checksums and complete the distribution
   checks below. If attachment fails midway, inspect the draft before retrying;
   existing asset names are refused rather than overwritten.
4. Publish the draft yourself after review. The existing **Release** workflow
   handles container images after publication.

A published immutable release cannot receive new assets. Use a new reviewed
version/tag for newer source. See [GitHub's immutable release guidance](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).

## Distribution checks

Before publishing an unsigned preview:

- Keep the matching sources and dependency notices with the release, and label
  its unsigned status and supported capabilities accurately.
- Verify the browser-downloaded archive on a clean Apple Silicon Mac without
  Docker or system Java, following macOS's normal security controls.
- Verify authenticated startup, an authorized HTTP/passive/report workflow,
  stop/restart and preservation with that final artifact.

Signing and notarization are a separate distribution milestone. A signed edition
needs verification of the complete payload and the downloaded distribution
container, including applicable nested native code. The vendor JRE signature
alone does not sign or notarize MCP ZAP Server.
