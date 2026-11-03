# Release Notes Archive

Version-specific release note snapshots live here to keep the repository root focused.
GitHub Releases remains the canonical public release archive:
[github.com/dtkmn/mcp-zap-server/releases](https://github.com/dtkmn/mcp-zap-server/releases).

## Version Notes

These notes describe each version. Check GitHub Releases above for publication
status and dates, and verify that its release workflow has published the image
before deploying it.

The [0.18.0 release and upgrade guide](RELEASE_NOTES_0.18.0.md) covers
current source after v0.17.1, including tool compatibility, launch-safety storage,
the V9 migration and recovery limits. It does not assign or announce a release.
The root [changelog](../../CHANGELOG.md#unreleased) records notable changes as
they accumulate.

<a id="claude-desktop-downloads-in-the-next-normal-release"></a>

## Claude Desktop Downloads

The published [v0.17.1 release](https://github.com/dtkmn/mcp-zap-server/releases/tag/v0.17.1)
contains the Claude Desktop MCPB, its setup guide and both `.sha256` files for
connector `0.1.0`. That connector version is independent of the scanner tag.
There is no separate Desktop release or repository. Download the matching set
from the scanner release and verify its checksums. Those immutable assets
describe the released build, not subsequent source changes.

The maintainer creates the normal scanner tag and its draft release, dispatches
**Claude Desktop MCPB** from `main` with that tag in `draft_release_tag`, reviews
the four attached assets and their checksums, then publishes the normal release.
Stable and usual scanner prerelease tags are supported. The workflow builds the
tagged source reachable from `main`, checks the scanner Gradle version against
the tag, and requires an existing mutable draft. It does not create or publish a
release or overwrite existing assets. All assets must be attached before
publication because the release becomes immutable. Publication then triggers the
existing scanner Docker release workflow; verify image publication separately.

See the [package release procedure](../../packaging/claude-desktop/README.md#prepare-downloads-for-a-normal-scanner-release)
for exact filenames and package version rules, and the
[user setup guide](../../packaging/claude-desktop/SETUP.md) for installation.
Use the [publishing procedure](PUBLISHING.md) and its read-only preflight before
publishing. A successful Desktop preview build alone does not attach its files
to a release.

## Version Archive

- [0.18.0 release and upgrade guide](./RELEASE_NOTES_0.18.0.md) — migration and recovery for the release candidate
- [0.17.1 release and upgrade guide](./RELEASE_NOTES_0.17.1.md) — distribution requirements, release preflight and inherited upgrade requirements
- [0.17.0 release and upgrade guide](./RELEASE_NOTES_0.17.0.md) — explicit-deny migration, client defaults and publication checks
- [0.16.0 release and upgrade guide](./RELEASE_NOTES_0.16.0.md) — includes immutable image references and upgrade requirements
- [0.15.0](./RELEASE_NOTES_0.15.0.md)
- [0.14.0](./RELEASE_NOTES_0.14.0.md)
- [0.13.0](./RELEASE_NOTES_0.13.0.md)
- [0.12.0](./RELEASE_NOTES_0.12.0.md)
- [0.11.1](./RELEASE_NOTES_0.11.1.md)
- [0.11.0](./RELEASE_NOTES_0.11.0.md)
- [0.10.1](./RELEASE_NOTES_0.10.1.md)
- [0.10.0](./RELEASE_NOTES_0.10.0.md)
- [0.9.1](./RELEASE_NOTES_0.9.1.md)
- [0.9.0](./RELEASE_NOTES_0.9.0.md)
- [0.8.0](./RELEASE_NOTES_0.8.0.md)
- [0.7.0](./RELEASE_NOTES_0.7.0.md)
- [0.6.1](./RELEASE_NOTES_0.6.1.md)
- [0.6.0](./RELEASE_NOTES_0.6.0.md)
- [0.5.0](./RELEASE_NOTES_0.5.0.md)
- [0.4.0](./RELEASE_NOTES_0.4.0.md)
- [0.3.0](./RELEASE_NOTES_0.3.0.md)
