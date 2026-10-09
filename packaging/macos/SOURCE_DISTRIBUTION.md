# Source delivery for the macOS preview

The binary package and its matching `-sources.tar.gz` companion are one release
set. Publish both archives and both SHA-256 checksum files together, with equally
accessible downloads from the same release. The source companion is not needed
to run the server. It supplies source and build materials for the redistributed
components identified in `SOURCE_MANIFEST.json`.

## What is supplied

The source companion preserves upstream source materials for the shipped components. Its manifest records
exact upstream locations, SHA-256 checksums, applicable components and licence
choices. Archives marked `source_only` omit compiled build dependencies and the
obsolete coverage-tool bundle;
`omit_files` lists excluded optional-component binaries and test/font assets.
Source/build materials and notices for the shipped libraries remain. `DELIVERED_SOURCES.json`
records delivered archive names/checksums and every omitted entry, separately from
the checksums of the downloaded upstream inputs. It includes:

- The matching OpenJDK source tree, Temurin build scripts and upstream metadata
  for the actual macOS Java runtime. The build sources also contain the Mozilla
  certificate data used for the runtime's trust store.
- Version-specific source materials for the server's EPL components.
- Source trees and build materials for the retained ZAP components identified
  in the manifest, including components with GPL, LGPL and MPL requirements.
- Supplemental copyright and licence notices in `DISTRIBUTION_NOTICES.md`, plus
  the server dependency notices in `SERVER_THIRD_PARTY_NOTICES.md`.

The ZAP extension source trees retain their Gradle wrapper and shared build
files for Fuzzer, Reports and Bruteforce. Unrelated optional-component binary
dependencies are omitted; this companion does not promise a build of every
other add-on in those repository snapshots. Component development tools and
normal build dependencies still need to be obtained separately. The manifest
records component-specific rebuild guidance.

`BINARY_PACKAGE.json` in the source companion identifies the corresponding
binary archive and its checksum. `SOURCE_MANIFEST.json` in both packages is
identical. The generated binary package's `COMPONENTS.json` identifies its source
companion and the manifest checksum.

MCP ZAP Server and the launcher remain Apache-2.0 components. Their public source
repository is <https://github.com/dtkmn/mcp-zap-server>. The source companion also
includes the packaging scripts used to assemble this preview. Third-party
components retain their own terms; the package's licence does not replace them.

## Library modification and replacement

Stop the packaged services before modifying libraries. The Java runtime and
ZAP's library/add-on files remain separate, accessible files in the extracted
package. Use the relevant upstream source and build instructions to rebuild a
component. ZAP library JARs can be replaced in `zap/lib/`; libraries embedded in
an add-on require rebuilding that add-on and replacing its `zap/plugin/*.zap`
archive. Keep the component's licence and notices with modified distributions.
The packaged checksums identify the original release; a modified copy will have
different checksums.

## Maintainer review

The reviewed Java and ZAP input hashes, retained ZAP archive inventory and
source-bearing server library hashes are pinned in the source manifest. The
builder rejects changes to that reviewed scope. When versions, included add-ons
or source-bearing libraries change, review the source mapping, build materials
and notices before updating those pins.

The review records source provenance and delivery materials. It does not claim
a reproducible rebuild of every upstream component. Do not publish only the
binary archive or substitute a generic upstream homepage for the matching
source companion. Public download, clean-environment installation and optional
Apple signing/notarization have their own verification steps.
