# Standalone Policy Metadata Extension

This is the smallest external-builder shape for an extension.

It is intentionally separate from the root build. It does not depend on the
gateway application, core services, enterprise packages, or ZAP-native APIs.
It depends on only:

- `mcp.server.zap:mcp-zap-extension-api` from the local staged publication
- Spring Boot auto-configuration annotations
- test libraries

## Build

First verify the local experimental API publication from the repository root:

```bash
./gradlew verifyExtensionApiPublication
```

Then build the standalone sample:

```bash
./gradlew -p examples/extensions/standalone-policy-metadata-extension build
```

Inside this repository, the sample derives the default API version from the
root `build.gradle` and reads the API artifact from
`build/extension-api-publication` so the compatibility proof tracks the current
project version without relying on stale `mavenLocal()` state. Outside this
repository, pass `-PextensionApiVersion=<version>` and
`-PextensionApiRepositoryUrl=<maven-repository-url>` when you want to target a
specific API release.

This is still an experimental path. It proves the decoupled extension shape,
not a public binary compatibility promise.

The planned public-preview coordinate is
`io.github.dtkmn:mcp-zap-extension-api`. That artifact is not published yet;
this sample should keep using the staged repository until the release policy
marks public preview as available.
