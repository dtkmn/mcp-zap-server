# Release Notes - Version 0.11.1

These notes describe **MCP ZAP Server `v0.11.1`**. See
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for its
publication status and date. Before deploying, verify that the release workflow
has successfully published the versioned image to your registry.

## Highlights

- Fixes [#227](https://github.com/dtkmn/mcp-zap-server/issues/227): unknown tools and tools disabled by the selected surface receive the same generic unknown-tool response instead of a misleading permission error.
- Integrates `mcp-gateway-core` and `mcp-gateway-spring-webflux` `0.10.0`, using ZAP's actual registered callbacks and existing descriptors for tool availability.
- Updates Spring Boot to `4.1.1`, Spring AI to `2.0.1`, Gradle to `9.7.1`, and documentation/build dependencies.

## Tool-Call Behavior

After authentication in API-key or JWT mode, a call with a valid request ID to an
unknown or disabled tool returns HTTP `200` with a JSON-RPC error:

```json
{"jsonrpc":"2.0","id":7,"error":{"code":-32602,"message":"Unknown tool"}}
```

The original request ID is preserved. The response contains no authentication
challenge, tool name, disabled status, or required scopes. Clients must inspect
the JSON-RPC body rather than treating HTTP `200` alone as successful execution.
When authorization is enforced, an enabled tool still returns HTTP `403` if the
caller lacks its required scope.

Availability checks also apply in authorization `off`/`warn` and security `none`
modes. A wildcard scope does not enable a disabled tool. Calls without an ID
receive HTTP `202` with no body and do not execute; explicit null, fractional, or
non-string/non-integer IDs receive HTTP `400` with JSON-RPC `-32600`.

## Compatibility And Upgrade

- No tool names, input schemas, authentication configuration, or database schema changes are introduced by this patch.
- Clients that treated unknown-tool permission errors as a reason to request broader scopes should instead handle the JSON-RPC unknown-tool error.
- Guided/expert selection remains startup configuration. Restart after changing the selected surface; runtime tool addition/removal is not supported by this integration.
- Helm chart `0.11.1` defaults to image `v0.11.1`. Match the chart and image version when upgrading; `v0.11.0` does not include this fix.
- MCP Registry metadata references `v0.11.1`; publish it only after both referenced release images are available.
- The extension API remains `experimental-local`. Its local publication and sample dependency version are `0.11.1`; the release workflow does not publish this API to Maven Central.

## Release Availability

Main CI builds and tests the application and container but does not publish
release images. The release workflow publishes versioned AMD64 and ARM64 images
to GHCR and Docker Hub from an immutable GitHub Release whose tag matches the
project version and whose source is reachable from `main`.

Publishing gateway `0.10.0` or merging ZAP code does not upgrade an existing ZAP
installation. Deploy the corresponding ZAP release image to receive this fix.
