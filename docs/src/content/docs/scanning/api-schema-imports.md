---
title: "API Schema Imports"
editUrl: false
description: "Import OpenAPI, GraphQL, and SOAP definitions through guided or expert MCP tools."
---
MCP ZAP Server supports schema-driven API imports across three common families:

- OpenAPI / Swagger
- GraphQL
- SOAP / WSDL

## Guided Versus Expert

Default guided import:

- `zap_target_import`

Expert raw import tools:

- `zap_import_openapi_spec_url`
- `zap_import_openapi_spec_file`
- `zap_import_graphql_schema_url`
- `zap_import_graphql_schema_file`
- `zap_import_soap_wsdl_url`
- `zap_import_soap_wsdl_file`

Use guided import when you want one stable entrypoint.

Use expert import when you want the raw family-specific tools. That requires `MCP_SERVER_TOOLS_SURFACE=expert`.

## OpenAPI / Swagger

Expert tools:

- `zap_import_openapi_spec_url`
- `zap_import_openapi_spec_file`

Parameters:

- `apiUrl` or `filePath`
- `hostOverride` optional

### OpenAPI Target Policy

The definition source and the API target can be different addresses. URL imports
validate `apiUrl` before ZAP downloads the definition. Both URL and file imports
also validate any supplied `hostOverride` through the configured scan URL policy
before calling ZAP.

Supported overrides must name a host:

- Full HTTP(S) target: `https://api.example.com/v1`
- Authority only: `api.example.com:9090`
- Authority and path: `api.example.com/v1/`
- Scheme-relative authority and path: `//api.example.com/v1/`

Authority-only overrides retain ZAP's scheme and path inheritance. The server
checks the host with both HTTP and HTTPS before forwarding the unchanged override.
Scheme-only (`https://`) and path-only (`/v1/`) overrides are rejected because their
effective host depends on the definition. Replace them with a full target URL.
User info, query strings, fragments, malformed hosts, and invalid ports are rejected.

Omitting the override retains ZAP's definition-derived target resolution. The MCP
server does not download or parse the definition to validate its embedded servers
or external references. Use only trusted definitions and enforce egress controls
on the ZAP host/container. Overrides do not constrain external references,
redirects, DNS changes between validation and engine use, or every request ZAP may
send. Keep ZAP's filesystem access restricted too: imported files may reference
other files. Disabling URL validation is an explicit operator opt-out from
destination checks; override syntax checks still apply.

For file imports, `filePath` (or guided `source` with `sourceKind=file`) refers to
ZAP's filesystem, such as `/zap/wrk/openapi.yaml`. A path on the AI client's
computer is insufficient. These tools do not accept attached file contents or
upload a definition to a remote ZAP host.

Use this when:

- you already have an OpenAPI or Swagger description
- you want ZAP to import REST paths directly
- you need a host override for a schema copied from another environment

## GraphQL

Expert tools:

- `zap_import_graphql_schema_url`
- `zap_import_graphql_schema_file`

Parameters:

- `endpointUrl`
- `schemaUrl` or `filePath`

Important:

- GraphQL import needs both the schema source and the runtime endpoint URL
- there is no OpenAPI-style `hostOverride`

## SOAP / WSDL

Expert tools:

- `zap_import_soap_wsdl_url`
- `zap_import_soap_wsdl_file`

Parameters:

- `wsdlUrl` or `filePath`

## Guided Import Example

```json
{
  "tool": "zap_target_import",
  "arguments": {
    "definitionType": "openapi",
    "sourceKind": "url",
    "source": "https://example.com/openapi.yaml",
    "hostOverride": "https://api.example.com/v1"
  }
}
```

## After Import

Schema import prepares ZAP with known API structure. It does not replace the rest of the scan workflow.

Typical follow-up flow:

1. import the schema
2. run the appropriate crawl or attack path
3. run `zap_passive_scan_wait`
4. collect findings or generate reports

## Add-on Requirements

If you bring your own ZAP deployment, make sure the matching add-ons are installed before using the related tools:

- `graphql`
- `soap`
- `openapi` for OpenAPI/Swagger imports
- `spiderAjax` when your next step uses AJAX Spider crawling
