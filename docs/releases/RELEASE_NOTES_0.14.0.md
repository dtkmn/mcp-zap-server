# Release Notes - Version 0.14.0

These notes describe **MCP ZAP Server `v0.14.0`**. Preparing or merging this
version does not publish its release or container images. Check
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and confirm
that the release workflow has published the versioned images before deploying.

## Highlights

- Require explicitly registered API keys and remove the legacy single-key fallback and shipped placeholder credential.
- Keep PostgreSQL authoritative for JWT revocation and refresh-token consumption during database failures. Preserve revocations through the existing token-validation clock-skew window.
- Bound Automation Framework YAML parsing and expansion, and validate declared plan destinations before writing a plan or calling ZAP.
- Validate OpenAPI target overrides for URL and file imports, and correct Helm MCP ingress when no peers are permitted.
- Bound HTTP metric series and adopt Gateway Core and the Spring WebFlux adapter `0.11.0`, with shared governance audits and active-tool permission validation.

This minor version changes accepted configuration and inputs. Review the
following migration steps before upgrading from `v0.13.0`.

## Upgrade Notes

### Register every API key

Remove nonblank `mcp.server.apiKey`, `mcp.server.api-key`, and
`MCP_SERVER_API_KEY` settings from configuration, environment variables, and
command-line overrides. They now fail startup, even when security is disabled.
Register keys in `mcp.server.auth.apiKeys` instead.

**`MCP_API_KEY` remains supported** for the packaged default client; it is not
the removed `MCP_SERVER_API_KEY` setting. Overriding the whole `auth.apiKeys` list
replaces that default entry. Preserve explicit `clientId: legacy-client` and
`workspaceId: legacy-client` if policies or existing records depend on the old
fallback's identities. See the
[API-key migration example](../src/content/docs/security-modes/index.md#registered-api-keys-in-v0140).

Packaged application and Compose defaults no longer provide a placeholder key.
API-key and JWT modes require a configured key. Recognized placeholder values
require explicit `allowPlaceholderApiKey=true` and at least one active profile,
with every profile among `local`, `dev`, and `test`. Production and mixed profile
sets reject them. JWT mode still accepts registered API keys.

### Prepare the PostgreSQL JWT backend

When `JWT_REVOCATION_STORE_BACKEND=postgres`, the revocation database is
authoritative; the server no longer falls back to local memory. Invalid backend
configuration or an inaccessible revocation table prevents startup.
`JWT_REVOCATION_STORE_POSTGRES_FAIL_FAST` now defaults to `true`.

Database failures return sanitized HTTP `503` responses for protected JWT
requests and `/auth/refresh`, `/auth/revoke`, and `/auth/validate`. Setting
`failFast=false` only relaxes expired-record cleanup; required lookups and
writes must still succeed. `/auth/token` and registered API-key authentication
do not depend on the revocation database.

A failed or uncertain write returns no token pair or success acknowledgment.
Retry an unsuccessful revocation after recovery. A refresh may have consumed
its token without delivering a new pair; if retry returns `401`, exchange the
registered API key for a new pair. See
[failure and recovery behavior](../src/content/docs/security-modes/jwt-authentication.md#postgresql-failure-behavior-in-v0140).

### Upgrade the entire JWT fleet

The existing **60-second clock-skew allowance is unchanged**. Revocations and
used refresh-token records now remain effective through `exp + 60 seconds`,
including that boundary. JWTs without `exp` are rejected; server-issued tokens
already include it. Stored expiration values and response fields are unchanged.
This correction requires no schema migration or expiry backfill.

Stop all older replicas, token writers, and cleanup processes before treating
the fleet as corrected. Older processes can still delete required records, and
the upgrade cannot reconstruct records already lost. If revocation state is
missing or untrusted, hold traffic, stop older processes, rotate the shared
`JWT_SECRET`, and obtain new token pairs through registered API keys.

A 60-second pause is **not** a general recovery strategy for lost revocation
state. The narrowly scoped expired-row cleanup case and the full-token-lifetime
alternative are explained in the
[JWT lifetime upgrade guidance](../src/content/docs/security-modes/jwt-authentication.md#jwt-expiration-and-revocation-lifetime-in-v0140).

### Check Automation Framework plans

Inline YAML and workspace files share the same checks before materialization
and engine dispatch. Input and normalized output are limited to **1 MiB UTF-8**,
one YAML document, and a mapping root. Expansion is bounded to 10,000 node
occurrences, depth 50, 1,048,576 scalar characters, and 50 collection aliases.
Cyclic aliases and collection mapping keys are rejected.

Inline plans also remain subject to the MCP HTTP body limit, **256 KiB by
default**, including their JSON envelope and escaping. Use a workspace plan
file when the inline request exceeds that configured limit.

Plans must declare unique literal context names and nonempty URL lists. Context,
requestor, scan, form/JSON login, and authentication polling URLs use the ordinary
scan destination policy. Destination/context substitutions, arbitrary
`includePaths`, engine proxy/configuration overrides, and unsupported jobs or
authentication types are rejected. Variables remain available for credentials
and request bodies.

Use the dedicated import tools before a supported plan. Plans requiring ZAP's
full native job set must be run directly by an operator outside this MCP runner.
Review the [YAML limits and supported plan contract](../src/content/docs/scanning/automation-framework.md#yaml-limits).
Declared destination checks do not isolate ZAP's runtime network access; keep
engine egress controls in place for redirects, crawling, DNS changes, and
external references.

### Replace ambiguous OpenAPI target overrides

`hostOverride` now passes the configured destination policy for both URL and
file imports. Full HTTP(S) targets, explicit authorities with optional paths,
and scheme-relative authorities remain supported. Authority forms are checked
under both HTTP and HTTPS, then forwarded unchanged to preserve ZAP's scheme
and path inheritance. Replace scheme-only or path-only overrides with a full
target URL.

Omitting the override retains definition-derived target resolution. The MCP
server does not parse embedded servers or external references, so use trusted
definitions and ZAP egress controls. File paths still refer to **ZAP's
filesystem**; attached definition contents and uploads to a remote ZAP host are
not added by this release. See [OpenAPI target policy](../src/content/docs/scanning/api-schema-imports.md#openapi-target-policy).

### Review Helm ingress peers

With `networkPolicy.mcp.allowSameNamespace=false` and no
`networkPolicy.mcp.extraIngress` peers, the chart now renders `ingress: []`,
denying MCP ingress rather than permitting all sources on its port. Configure
the peers you intend to allow before upgrading that combination. The default
same-namespace access remains enabled.

Enforcement depends on the cluster's network-policy support and other policies
selecting the pod. This change does not automatically restrict ZAP egress. See
[Helm network policy guidance](../../helm/mcp-zap-server/README.md#network-policy).

### Update metric and audit queries

`mcp.zap.http.requests` uses matched route patterns or `/unmatched` instead of
raw request paths. Extension methods use `other`. The timer is limited to
1,024 series, including overflow series that retain request counts and
durations. Raw paths remain in structured completion logs.

Gateway `0.11.0` integration validates active tools and their permissions
together, uses atomic rate-limit decisions, and emits shared governance audits
once per signal. Authentication, policy, and tool-completion audits retain their
application-owned details.

Update governance audit queries to read the principal instead of
`data.clientId`. Authorization includes `reason` and both scope lists, even
when empty. Protection uses outcome `rejected` and a separate `data.errorCode`;
`data.toolFamily` is removed while its metric tag remains. Invalid-request and
adapter diagnostics use outcome `rejected` and omit unavailable identities.
Audit storage remains bounded and in memory; this integration adds no durable
delivery guarantee. See the [observability migration guide](../src/content/docs/operations/observability.md#gateway-0110-audit-schema-in-v0140).

Guided and expert findings-read schemas now mark nonblank `baseUrl` as required,
matching existing runtime validation. Refresh cached tool schemas if necessary.
Existing target visibility checks and optional filters are retained.

### Match deployment versions

Helm chart `0.14.0` defaults to image `v0.14.0`. Application, MCP Registry, and
current installation examples use the same version. Update an explicit
`MCP_SERVER_VERSION` override if present, and publish Registry metadata only
after its referenced images are available.

Java **25** and the configured ZAP **2.17.0** baseline are unchanged. No new
database migration is required when upgrading from `0.13.0`; the migration
inventory remains V1 through V8 and execution remains opt-in. Earlier upgrades
must still apply their required migrations.

The extension API remains `experimental-local`, with local proof version
`0.14.0`. It is not published to Maven Central by the release workflow and does
not carry a stable compatibility guarantee.

## Maintenance and Remaining Limits

Runtime dependency updates include Jackson **3.2.3**, Netty **4.2.18.Final**, and
Logback **1.6.4**. Build maintenance includes Gradle **9.8.0**, CycloneDX plugin
**3.4.1**, documentation dependencies, and pinned CI actions. Build plugins have
their own dependency resolution; the runtime Jackson update does not upgrade
every plugin's Jackson dependency.

Dependency scans still report advisories in documentation/build dependencies
and the Debian runtime base. This release does not remediate every upstream
advisory, and a successful scan workflow does not mean it has no findings.

Documentation updates correct JWT deployment settings, header authentication,
correlation tracing, Client Spider and findings workflows, and generate selected
website pages from their canonical repository documents. The shared ZAP engine
and findings remain shared: client authentication and tool scopes do not provide
separate engine sessions or isolated findings for each client.
