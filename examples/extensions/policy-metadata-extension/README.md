# Sample Policy Metadata Extension

This minimal extension adds metadata to policy dry-run summaries.

It does not add a scanner engine, MCP tool, secret reader, report generator, or
tenant model. It only demonstrates the extension pattern:

- depend on the dedicated extension API artifact
- implement a public extension API contract
- register through Spring Boot auto-configuration
- package extension classes and metadata separately from the gateway runtime

The sample lives inside the repository. A third-party extension should live in its own
repository and compile against the dedicated extension API artifact, not the
full gateway runtime.

## What It Implements

The sample implements `PolicyBundleAccessBoundary`.

When loaded by an application context and explicitly enabled, it enriches policy
dry-run response summaries with metadata such as:

- provider id
- extension mode
- observed policy label keys

It does not deny requests or enforce access control. That is deliberate. A
sample extension should teach the shape of the contract without pretending to
be a production governance provider.

## Build The Sample

From the repository root:

```bash
./gradlew extensionApiJar samplePolicyMetadataExtensionJar
```

The API and sample packages are written to:

```text
build/libs/mcp-zap-extension-api-*.jar
build/libs/*-sample-policy-metadata-extension.jar
```

The sample JAR should contain only:

- `mcp/server/zap/examples/extensions/policy/**`
- `META-INF/mcp-zap/extensions/sample-policy-metadata.properties`
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

It should not contain gateway runtime classes, enterprise classes, or the
application entrypoint. It should also not contain the extension API classes;
those belong in the separate API artifact.

## Enablement Contract

The sample configuration is guarded by:

```text
mcp.server.sample.policy-metadata-extension.enabled=true
```

The sample uses Spring Boot auto-configuration metadata so it can wire like an
external JAR on the runtime classpath. It also uses
`@ConditionalOnMissingBean(PolicyBundleAccessBoundary.class)` so it does not
replace a real access-boundary provider when one already exists.

<a id="what-this-proves"></a>

## Packaging And Loading

The sample uses these extension mechanisms:

- public extensions can compile against the extension API artifact
- extension artifacts can be packaged separately
- extension artifacts can register through Spring Boot auto-configuration
- extension metadata can describe how the provider loads
- the sample has no dependency on enterprise code

<a id="target-external-developer-path"></a>

## External Developer Setup

For your own extension:

1. create a standalone extension repository
2. depend on the small `mcp-zap-extension-api` artifact
3. implement one public extension contract
4. package a separate extension JAR
5. register extension beans through Spring Boot auto-configuration
6. include that JAR on the gateway runtime classpath

External builders should not need to edit this repository's `build.gradle`.
The API artifact now exists as an experimental build output, but it is not a
binary compatibility promise yet.

Do not assume classpath presence alone is enough. A third-party package outside
the gateway application package will not be discovered by component scanning
unless the runtime has a supported registration mechanism.

<a id="what-this-does-not-prove"></a>

## Limitations

This sample does not provide:

- runtime plugin discovery
- third-party binary compatibility
- multi-engine support
- tenant isolation
- enterprise policy enforcement

Do not use this metadata provider as an access-control or tenant-isolation
mechanism.
