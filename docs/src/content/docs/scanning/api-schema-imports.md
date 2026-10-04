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

The override validation below is introduced in `v0.14.0` and is not included in
`v0.13.0`. Check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases)
for availability.

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
computer is insufficient. URL/file import behavior is unchanged by the content
mode below; the expert URL/file tools do not upload attached files.

Use this when:

- you already have an OpenAPI or Swagger description
- you want ZAP to import REST paths directly
- you need a host override for a schema copied from another environment

### Client-Supplied OpenAPI Content — Unreleased

Guided `zap_target_import` adds `sourceKind=content` for `definitionType=openapi`
after `v0.14.0`. This feature is **Unreleased** and disabled by default. Use a
build that includes it; check the release notes before choosing a published image.

`source` must contain the actual UTF-8 JSON/YAML definition text. If a file is
attached to a chat, the AI client must read it and pass its contents to this tool.
An attachment name, client-local path, or inaccessible resource URI does not
provide those contents. Automatic attachment access across clients, multi-file
uploads, GraphQL/SOAP content imports, and transfer to a remote ZAP host are not
supported. MCP and ZAP must share the configured staging filesystem.

Supply a **full HTTP(S) `hostOverride`**, such as `https://api.example.com/v1`,
allowed by the configured destination policy. Content imports replace embedded
server declarations with this explicit target. Import may send requests and
changes the ZAP session; it does not start an active scan. Continue to use only
authorized targets and enforce ZAP egress controls for redirects and DNS changes.
Shared staging does not isolate the shared ZAP engine between tenants.

Supported definitions are Swagger **2.0**, OpenAPI **3.0.x**, and OpenAPI
**3.1.x**, with one self-contained document. Internal fragment references,
including cyclic schema references, are allowed without expanding schema cycles.
Duplicate keys, YAML aliases, unsafe tags, external/file/network references,
schema rebasing identifiers or dialect declarations, callbacks, webhooks, Link
operation references and Link server overrides are rejected by the initial
conservative contract. Discriminator mappings must use existing internal JSON
Pointer references; bare names and external mappings are rejected.
Path parameters must use schemas; content-valued or object-valued path parameters
are rejected, including object structures nested inside array items.

Limits apply before writing or engine dispatch: **1 MiB UTF-8** for both raw and
normalized content, **10,000 nodes**, structural depth **50**, internal reference
chains **50**, combined reference traversal nesting **100**, and **1,000
operations**. The whole MCP request is separately limited to **256 KiB by default**
(`MCP_REQUEST_MAX_BODY_BYTES=262144`), including the JSON envelope and escaped
definition text. A definition below 1 MiB can therefore exceed the transport
limit. Review that server limit deliberately if larger inline requests are needed;
the content limits still apply.

#### Configure the Shared Staging Directory

Content import supports one MCP instance per dedicated **local POSIX** staging
root, on a filesystem that provides OS file locking. The writer lock protects
that supported topology; NFS/EFS locking and distributed HA are unvalidated and
outside this contract. The root
must be pre-provisioned, owned by the MCP process UID, with mode **0750** and a
group that ZAP can read and traverse. Staged definitions use **0640** permissions.
Keep this root outside report and Automation Framework directories; symlink
staging paths and unsupported filesystems are rejected. Protect the root's
ancestors against replacement by other UIDs. MCP uses workspace-scoped,
server-generated paths.

For the packaged Compose stack, provision a sibling directory on a Linux host:

```bash
mkdir -p ./zap-workplace/content-imports
sudo chown 1000:1000 ./zap-workplace/content-imports
sudo chmod 0750 ./zap-workplace/content-imports
```

Adjust the host path for `LOCAL_ZAP_WORKPLACE_FOLDER` and the owner/group for your
actual container identities. The packaged MCP process uses UID 1000; verify ZAP
can read the shared group without granting it write access. Do not rely on Compose
to create the directory with suitable permissions.

Set these values in `.env`:

```dotenv
ZAP_OPENAPI_CONTENT_ENABLED=true
ZAP_OPENAPI_CONTENT_LOCAL_DIRECTORY=/zap/imports
ZAP_OPENAPI_CONTENT_ZAP_DIRECTORY=/zap/imports
ZAP_OPENAPI_CONTENT_MAX_RETAINED_IMPORTS=64
ZAP_OPENAPI_CONTENT_RETENTION_MINUTES=60
```

Start the base stack with the optional staging override:

```bash
docker compose -f docker-compose.yml -f docker-compose.openapi-content.yml up -d --build
```

The override binds the pre-existing host `content-imports` directory at
`/zap/imports` in both containers, read-only in ZAP. It does not create a missing
host directory. For another MCP/ZAP deployment or Helm, provision an equivalent
dedicated local shared mount with the same single-instance and permission
contract, and configure both absolute paths explicitly. They can
differ, for example MCP `/srv/mcp-imports` and ZAP `/zap/imports`, but must map the
same files. A local write is not an upload to an unrelated remote ZAP filesystem.

#### Complete Content Call

Send this `tools/call` request over your authenticated MCP connection, substituting
an authorized target. The JSON definition is escaped inside the `source` string:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "zap_target_import",
    "arguments": {
      "definitionType": "openapi",
      "sourceKind": "content",
      "source": "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"Health API\",\"version\":\"1.0\"},\"paths\":{\"/health\":{\"get\":{\"responses\":{\"200\":{\"description\":\"Healthy\"}}}}}}",
      "hostOverride": "https://api.example.com/v1"
    }
  }
}
```

#### Outcome and Cleanup

At most **four imports** run concurrently per staging service. The retained-import
limit defaults to **64**, including uncertain/crash leftovers; exhausted capacity
rejects another import rather than retaining unlimited content. After a synchronous
ZAP response, staging is removed. Responses report warning/error counts and
partial or unconfirmed outcomes where applicable; raw warning text is withheld
because it can echo definition contents, credentials, or staging paths. An import
response is not proof of scan coverage or successful active testing.

An explicit unreadable-file, invalid-parameter or invalid-definition API rejection
removes staging and reports a sanitized failure. An unreadable file points to a
shared-mount, path-mapping or reader-permission problem; a parameter rejection can
also indicate a target-override problem. Other engine exceptions, including an
API timeout, leave completion **uncertain**.
The definition is retained for the configured retention period, **60 minutes** by
default. Cleanup skips active imports and reclaims expired crash leftovers. Check
ZAP before retrying an uncertain import to avoid repeating changes. Retention
expiry deletes staging; it does **not** cancel ZAP or confirm the import completed.

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
