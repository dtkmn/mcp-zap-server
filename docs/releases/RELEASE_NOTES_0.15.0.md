# Release Notes - Version 0.15.0

These notes describe **MCP ZAP Server `v0.15.0`**. Preparing or merging this
version does not publish a release, container image or MCP Registry entry.
Check [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) for
publication status and dates, and confirm the corresponding
[release image workflow](https://github.com/dtkmn/mcp-zap-server/actions/workflows/release.yml)
succeeded before deploying its image. Publish Registry metadata only after its
referenced images are available.

This version adds client-supplied OpenAPI content import and complete paged report
retrieval. It also corrects HTTP crawl limits, reduces shared-session information
in target-scoped reports and reapplies required ZAP settings after engine recovery.

## New and Corrected Behavior

### Import OpenAPI content supplied by an MCP client

Guided `zap_target_import` now accepts `definitionType=openapi` with
`sourceKind=content`. The client supplies the actual UTF-8 JSON/YAML definition
in `source` and a full HTTP(S) `hostOverride` allowed by the configured destination
policy. MCP replaces embedded server declarations with that explicit target.
An attachment name or client-local path is not the definition: the MCP client
must read the attachment and pass its contents.

The feature is **disabled by default**. Configure a dedicated staging directory
shared by MCP and ZAP, separate from report and Automation Framework directories.
It supports one MCP writer on a local POSIX filesystem with OS file locking,
mode `0750` directories and mode `0640` definitions. The supplied layouts give
ZAP read-only staging access. Retention is bounded and configurable.

Swagger 2.0, OpenAPI 3.0.x and 3.1.x are supported as a single self-contained
document. Validation rejects external/file/network references, YAML aliases,
duplicate keys and unsupported constructs before staging or engine dispatch.
Raw and normalized definitions are each limited to **1 MiB UTF-8**, with structural
and reference limits. The separate MCP body limit defaults to **256 KiB** including
the JSON envelope; it can reject a definition below the content limit.

Import can send requests and changes the ZAP session; it does not start an active
scan. Existing URL/file imports remain available. Multi-file content import,
GraphQL/SOAP content import, remote file transfer and distributed staging writers
are outside this feature's contract. See the
[API import guide](../src/content/docs/scanning/api-schema-imports.md#client-supplied-openapi-content)
for accepted definitions, configuration, retention and destination boundaries.

### Retrieve a complete report through MCP

Both guided and expert surfaces expose `zap_report_read_chunk` under the existing
`zap:report:read` permission. Append content exactly, use the returned `nextOffset`
and pass the first page's artifact hash as
`expectedSha256` on subsequent calls. A changed artifact is rejected rather than
silently combining pages from different files.

Offsets count **Unicode code points**, and pages do not split a UTF-16 surrogate
pair. Reports must be valid UTF-8 and at most **50 MiB**. Each call streams the
complete file to verify its hash and length while retaining only the requested
page; many small pages therefore require repeated file processing.

`zap_report_read` remains a bounded preview. A truncated preview is not complete
retrieval. Both read tools enforce the caller's report directory, reject unsafe
link paths and prevent access to report-generation staging files. See the
[report retrieval contract](../src/content/docs/scanning/findings-and-reports.md#preview-or-complete-retrieval).

### Keep target-scoped reports within their documented format boundary

MCP-generated reports with `baseUrl`, or expert reports with a nonblank `sites`
filter, omit shared-session insights, statistics, sequences and automation
diagnostics. Scoped JSON is filtered before the artifact is published. Scoped
HTML uses the standard template; full-session HTML retains theme selection.
The reviewed traditional JSON, HTML and Markdown templates are supported for
scoped expert reports; unsupported scoped templates are rejected.

A target/path-prefix filter is **not a scan-ID boundary**. Omitting the target
filter explicitly requests the full session. Native Automation Framework report
jobs use ZAP's own templates and do not pass through this MCP sanitization path.
These changes do not isolate one shared ZAP engine between independent tenants;
use separate sessions or engines where that boundary is required. See
[Findings and Reports](../src/content/docs/scanning/findings-and-reports.md#report-artifacts).

### Apply HTTP depth and child limits independently

`ZAP_SPIDER_MAX_DEPTH` now configures ZAP's actual HTTP depth setting.
`ZAP_SPIDER_MAX_CHILDREN` independently configures children per page. Both default
to **10**, and **0** means unlimited. Compose and Helm forward both values.
This covers direct and queued HTTP crawls, including authenticated defaults.
Client Spider and Automation Framework plans keep their own settings.

In `v0.14.0`, depth was passed as a child-count limit; review both values when
upgrading. ZAP's HTTP depth setting is engine-wide. Child limits do not guarantee
an exact request count because queued sibling links can still be fetched. See
[HTTP Crawl Limits](../src/content/docs/scanning/scan-execution-modes.md#http-crawl-limits).

### Reconcile live ZAP settings after delayed startup or restart

Health checks and outbound operation starts reapply configured ZAP user-agent
and target connection-timeout settings. Supported DNS TTL configuration is also
reconciled; an unsupported optional DNS setting remains compatible. If mandatory
configuration fails, MCP remains unready and a new outbound operation is rejected
before dispatch. Checks retry when ZAP becomes available, including on existing
MCP sessions.

Scan, import, automation and authentication-test starts use this guard; status,
stop and local report reads remain separate. The guard does not make in-flight
scans durable through engine restarts. Helm separates liveness from readiness.
See the
[Helm monitoring guidance](../../helm/mcp-zap-server/README.md#monitoring).

## Deployment and Upgrade Requirements

### Match the chart, metadata and actual image

Chart `0.15.0` defaults to MCP image `v0.15.0`. Align explicit image tags,
Registry metadata and any `MCP_SERVER_VERSION` override with the deployed version.
Changing server metadata does not upgrade the image.

The [EC2 Compose example](../../examples/aws-ec2/) now requires `MCP_ZAP_IMAGE`
in its private `.env` file. Set it to a full public `v0.15.0` image reference
pinned by the digest obtained after successful release publication. Existing
EC2 environment files containing only the two API keys must add this value before
starting the updated example. Do not combine a new version tag with an old image
digest. The example deliberately has no fallback to the older published image.
Follow the [EC2 deployment walkthrough](../operator/runbooks/AWS_EC2_COMPOSE_GUIDE.md)
for image verification, credentials, private access, workspace alignment and
cleanup.

### Configure Helm content staging and preserve existing files

OpenAPI content staging is opt-in. For the supplied Helm layout, configure
distinct sibling `workspace` and `content-imports` directories on the shared
PVC, one MCP replica, disabled autoscaling and the `Recreate` deployment strategy.
Nonroot initialization prepares the directories; MCP writes staging while ZAP
mounts it read-only. `Recreate` avoids overlapping writers and interrupts MCP
service during upgrades. Multi-writer, NFS and EFS staging are outside this layout.

For an existing PVC, stop all writers and back up required report and automation
files before choosing `zap.persistence.workspaceSubPath`. Selecting a subdirectory
does not move files from the old root layout. Follow the documented filesystem
migration and verify existing file access before resuming work. The default root
layout is unchanged when content staging is disabled.

`zap.persistence.automationSubdirectory` also lets operators place automation
plans and artifacts inside the selected client's report workspace; its default
remains unchanged. See the
[shared storage guidance](../../helm/mcp-zap-server/README.md#shared-report-and-automation-storage)
and [content staging migration](../../helm/mcp-zap-server/README.md#openapi-content-import-storage).

### Provide the cluster and engine network boundaries

The [private EKS guide](../../helm/mcp-zap-server/README.md#first-private-eks-deployment)
documents EBS CSI/Pod Identity prerequisites, an appropriate storage class,
enforcing CNI policies and explicit ZAP web egress. DNS-only egress does not allow
scanning, add-on downloads or required background passive-rule requests.

NetworkPolicy requires an enforcing CNI and does not provide complete host/node
isolation. Standard CNI startup can allow traffic before policies attach; strict
startup needs the necessary bootstrap allowances. Hosting-node services require
their own verified boundary when the threat model requires it. Review the
[network policy limitations](../../helm/mcp-zap-server/README.md#network-policy)
for the actual cluster. The deployment examples are operator references, not a
high-availability or production certification.

### Keep earlier upgrade requirements

No new application database migration is added relative to `v0.14.0`; the source
migration inventory remains **V1 through V8**. Earlier-version upgrades still
need their existing migrations and configuration changes. Migration execution
remains opt-in. Review the
[previous upgrade notes](./RELEASE_NOTES_0.14.0.md) and
[Helm upgrade guidance](../../helm/mcp-zap-server/README.md#upgrading), especially
before changing claims or adopting an existing PVC.

Java **25**, the configured ZAP **2.17.0** baseline and published Gateway
Core/Spring WebFlux **0.11.0** dependencies are unchanged. The extension API stays
`experimental-local`; its local proof version follows `0.15.0`, and this release
workflow does not publish it to Maven Central or promise stable compatibility.

## Build and Dependency Maintenance

Separate Gradle buildscript BOMs align Spring Boot tooling's Jackson family to
**3.1.7** and CycloneDX tooling's family to **2.22.3**. Application runtime Jackson
remains **3.2.3**, and plugin versions are unchanged. This targets build-tool
dependency resolution rather than changing the application's runtime Jackson
selection.

Documentation dependency updates and their audit dispositions are recorded in
the [dependency security status](../src/content/docs/reference/dependency-security.md).
Its dated external scan baseline is separate from release publication and from
the actual release image. Do not treat version preparation, a green workflow or
these dependency changes as proof that all vulnerabilities are resolved. Review
current findings and scan the published image for each supported architecture.
