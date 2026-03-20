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
    "hostOverride": "api.example.com"
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
- `ajaxSpider` when your next step depends on browser crawling
