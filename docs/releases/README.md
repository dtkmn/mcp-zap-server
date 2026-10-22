# Release Notes Archive

Version-specific release note snapshots live here to keep the repository root focused.
GitHub Releases remains the canonical public release archive:
[github.com/dtkmn/mcp-zap-server/releases](https://github.com/dtkmn/mcp-zap-server/releases).

## Version Notes

These notes describe each version. Check GitHub Releases above for publication
status and dates, and verify that its release workflow has published the image
before deploying it.

## Claude Desktop downloads in the next normal release

The next normal scanner release in this repository is intended to include the
Claude Desktop MCPB, its setup guide and both `.sha256` files. Connector version
`0.1.0` is independent of the scanner tag: for example, a future `v0.17.1`
scanner release can carry the `0.1.0` connector assets. There is no separate
Desktop release or repository. The existing immutable `v0.16.0` release is not
being changed. Check the scanner release's assets for available Desktop downloads.

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
