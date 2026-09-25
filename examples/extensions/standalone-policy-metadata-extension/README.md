# Standalone Policy Metadata Extension

This is the smallest external-builder shape for an extension.

It is intentionally separate from the root build. It does not depend on the
gateway application, core services, enterprise packages, or ZAP-native APIs.
It depends on only:

- `io.github.dtkmn:mcp-zap-extension-api` from the local staged public-preview
  publication
- Spring Boot auto-configuration annotations

## Build

The optional root task stages the local extension API and compiles the
standalone sample:

```bash
./gradlew standalonePolicyMetadataExtensionJar
```

To stage the API and build the standalone sample separately, use:

```bash
./gradlew publishExtensionApiPublicPreviewPublicationToExtensionApiPublicPreviewStagingRepository
./gradlew -p examples/extensions/standalone-policy-metadata-extension build
```

Local extension API dependency example (version `0.12.0`, resolved from a locally
staged Maven repository): the sample reads the artifact from
`build/extension-api-public-preview-publication`. The optional root build task
passes the current project version explicitly. Outside this repository, pass
`-PextensionApiVersion=<version>` and
`-PextensionApiRepositoryUrl=<maven-repository-url>` when you want to target a
specific API release.

Normal builds do not stage or verify the Maven publication. The commands above
perform local build operations, not custom publication-verification gates or
remote uploads.

This is still an experimental path. It proves the decoupled extension shape,
not a public binary compatibility promise.

The planned public-preview coordinate is already used in the staged repository.
That artifact is not published yet; this sample should keep using the staged
repository until the release policy marks public preview as available.
