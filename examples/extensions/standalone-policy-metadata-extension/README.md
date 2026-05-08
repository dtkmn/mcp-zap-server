# Standalone Policy Metadata Extension

This is the smallest external-builder shape for an extension.

It is intentionally separate from the root build. It does not depend on the
gateway application, core services, enterprise packages, or ZAP-native APIs.
It depends on only:

- `mcp.server.zap:mcp-zap-extension-api`
- Spring Boot auto-configuration annotations
- test libraries

## Build

First publish the local experimental API artifact from the repository root:

```bash
./gradlew publishExtensionApiPublicationToMavenLocal
```

Then build the standalone sample:

```bash
./gradlew -p examples/extensions/standalone-policy-metadata-extension build
```

Inside this repository, the sample derives the default API version from the
root `build.gradle` so the compatibility proof tracks the current project
version. Outside this repository, pass `-PextensionApiVersion=<version>` when
you want to target a specific API release.

This is still an experimental path. It proves the decoupled extension shape,
not a public binary compatibility promise.
