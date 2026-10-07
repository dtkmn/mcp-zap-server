# MCP ZAP Server Helm Chart

This Helm chart deploys the MCP ZAP Server (Model Context Protocol server for ZAP) on Kubernetes.

## Chart And Image Versions

Chart `0.15.0` defaults to MCP image `v0.15.0`. Use the chart from the same release
as your image, and confirm that the corresponding release workflow has published
the image before installing. [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases)
is the source for publication status. Release preparation does not publish images.

Version `0.15.0` adds OpenAPI content import and complete report retrieval through
`zap_report_read_chunk`, corrects HTTP crawl depth, and reapplies mandatory ZAP
outbound settings after delayed startup or engine replacement. Content import
requires the single-writer storage configuration below. Review the
[0.15.0 upgrade notes](../../docs/releases/RELEASE_NOTES_0.15.0.md) before rollout.

Version `0.14.0` removes the legacy API-key property, makes PostgreSQL JWT
revocation fail closed, and corrects the empty-peer MCP ingress policy. Review
custom authentication configuration, upgrade every JWT replica and cleanup
process, and check explicit ingress peers before rollout. There is no new
database migration compared with `v0.13.0`; see the
[0.14.0 upgrade notes](../../docs/releases/RELEASE_NOTES_0.14.0.md).

Client Spider and browser authentication profiles were introduced in `0.13.0`.
Deployments upgrading from `0.12.0` must also upgrade all queue workers before
submitting Client Spider jobs and use a 60000 ms ZAP API read timeout for browser
login; see the [0.13.0 upgrade notes](../../docs/releases/RELEASE_NOTES_0.13.0.md).

Upgrading from `v0.11.1` requires V7/V8
migrations for PostgreSQL scan-job storage. Migration execution is disabled by
default. See the [0.12.0 upgrade notes](../../docs/releases/RELEASE_NOTES_0.12.0.md)
for migration, findings snapshot, and timeout changes.

Set `zap.image.digest` to `sha256:` followed by 64 lowercase hexadecimal
characters to use `repository@digest` instead of `zap.image.tag`. Leave it empty
to use the tag. A digest pins the image; startup installation and persisted ZAP
state can still change installed add-ons.

## Architecture

This chart deploys two main components in **separate pods**:

1. **ZAP Proxy Pod** (1 replica, stateful)
   - ZAP security scanner
   - Handles all security scanning operations
   - Persistent storage for scan data
   - Resource-intensive (2-4GB RAM)

2. **MCP Server Pod(s)** (1 replica by default; 2+ only with session affinity)
   - REST API gateway to ZAP
   - Horizontally scalable only when streamable HTTP affinity is configured
   - Lightweight (512MB-1GB RAM)
   - Auto-scaling is opt-in

The MCP image remains a Java 25 application and uses a distroless Java 25
runtime. It intentionally has no shell, `curl`, or package manager. The chart's
startup, readiness, and liveness checks use Kubernetes-native HTTP/TCP probes; they
do not execute commands inside the container.

## Prerequisites

- Kubernetes 1.23+
- Helm 3.8+
- PV provisioner support in the underlying infrastructure (for ZAP persistence)

## Installation

Run the commands below from the repository root. They keep credentials in a
Kubernetes Secret and retain the default single MCP replica.

### Prepare Credentials

For a new local namespace, generate separate credentials:

```bash
kubectl create namespace mcp-zap
kubectl create secret generic mcp-zap-runtime --namespace mcp-zap \
  --from-literal=ZAP_API_KEY="$(openssl rand -hex 32)" \
  --from-literal=MCP_API_KEY="$(openssl rand -hex 32)" \
  --from-literal=JWT_SECRET="$(openssl rand -base64 32)"
```

For an existing environment, have your secret-management pipeline provision
`mcp-zap-runtime` in the release namespace with these three keys. Preserve
existing credentials during upgrades; replacing the signing secret invalidates
existing JWTs. Configure your MCP client with the generated MCP API key through
your normal secret-management process.

### Quick Start (Local Development - kind/minikube)

```bash
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set zap.config.existingSecret.name=mcp-zap-runtime \
  --set mcp.zapClient.existingSecret.name=mcp-zap-runtime \
  --set mcp.security.existingSecret.name=mcp-zap-runtime

kubectl port-forward -n mcp-zap svc/mcp-zap-mcp-zap-server-mcp 7456:7456
```

Connect to `http://localhost:7456/mcp` with the `X-API-Key` header. The default
NetworkPolicy allows ZAP DNS traffic only. Before scanning, configure the
authorized target CIDRs and ports under `networkPolicy.zap.egress.extraEgress`;
private targets also need the matching URL-validation settings.

### HTTP Crawl Limits

Configure depth and children per page independently:

```yaml
mcp:
  scan:
    limits:
      spiderMaxDepth: 10
      spiderMaxChildren: 10
```

Both default to `10`; `0` means unlimited. These values apply to HTTP crawls,
including queued and authenticated crawls. Client Spider uses its own depth
option, while Automation Framework plans configure their own job limits.

MCP `v0.15.0` applies HTTP depth and child limits independently. The `v0.14.0`
image maps HTTP depth to child count and does not recognize the child setting.
Helm values alone cannot correct that older image. Keep depth settings consistent
across MCP replicas sharing one ZAP engine. See the [crawl limits reference](../../docs/src/content/docs/scanning/scan-execution-modes.md#http-crawl-limits-unreleased-correction).

### JWT Deployment

After provisioning the same Secret, enable JWT explicitly:

```bash
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set zap.config.existingSecret.name=mcp-zap-runtime \
  --set mcp.zapClient.existingSecret.name=mcp-zap-runtime \
  --set mcp.security.existingSecret.name=mcp-zap-runtime \
  --set mcp.security.mode=jwt \
  --set mcp.security.jwt.enabled=true
```

These are alternative installs for a new release; use `helm upgrade` with your
saved configuration for an existing release. The JWT example keeps the service
on `ClusterIP`. For production, configure a controlled TLS ingress, allowed
ingress sources and scan-target egress, then complete the
[production checklist](../../docs/src/content/docs/operations/production-checklist.md).

### Custom Values File

Create `custom-values.yaml` using the previously provisioned Secret:

```yaml
mcp:
  replicaCount: 1
  security:
    mode: api-key
    allowPlaceholderApiKey: false
    existingSecret:
      name: mcp-zap-runtime
  zapClient:
    existingSecret:
      name: mcp-zap-runtime
  
  ingress:
    enabled: true
    className: nginx
    hosts:
      - host: mcp-zap.example.com
        paths:
          - path: /
            pathType: Prefix
    tls:
      - secretName: mcp-zap-tls
        hosts:
          - mcp-zap.example.com
  streamableHttp:
    sessionAffinity:
      enabled: true
      provider: ingress-nginx

zap:
  config:
    existingSecret:
      name: mcp-zap-runtime
  persistence:
    size: 20Gi

networkPolicy:
  mcp:
    extraIngress:
      - namespaceSelector:
          matchLabels:
            kubernetes.io/metadata.name: ingress-nginx
```

Replace the example hostname, TLS Secret, and ingress-controller namespace with
your deployment's values. Add approved scan-target egress as described above.

Install with custom values:

```bash
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml
```

## Configuration

### Key Configuration Options

| Parameter | Description | Default |
|-----------|-------------|---------|
| `mcp.replicaCount` | Number of MCP server replicas | `1` |
| `mcp.security.mode` | Authentication mode (none/api-key/jwt) | `api-key` |
| `mcp.security.apiKey` | MCP API key when not using `mcp.security.existingSecret` | `""` |
| `mcp.service.type` | Kubernetes service type | `ClusterIP` |
| `mcp.autoscaling.enabled` | Enable horizontal pod autoscaler | `false` |
| `mcp.autoscaling.maxReplicas` | Maximum replicas for autoscaling | `1` |
| `mcp.security.allowPlaceholderApiKey` | Opt in to placeholder MCP API keys; from `v0.15.0`, requires nonempty active Spring profiles all among `local`, `dev`, and `test` | `false` |
| `mcp.streamableHttp.sessionAffinity.provider` | Sticky-session preset for multi-replica streamable MCP (`aws-nlb`, `ingress-nginx`, `service-client-ip`) | `""` |
| `networkPolicy.mcp.enabled` | Enable MCP ingress and egress NetworkPolicy boundary | `true` |
| `networkPolicy.mcp.egress.extraEgress` | Operator-approved MCP egress rules for Postgres, JWKS, or other dependencies | `[]` |
| `mcp.image.tag` | MCP image tag | chart `appVersion` |
| `mcp.zapClient.url` | ZAP API hostname/service | chart-managed service (`<release>-mcp-zap-server-zap`) |
| `mcp.zapClient.connectTimeoutMs` | MCP to ZAP API connection timeout in milliseconds; must be positive | `5000` |
| `mcp.zapClient.readTimeoutMs` | ZAP API response read inactivity timeout in milliseconds; must be positive | `10000` |
| `mcp.security.existingSecret.name` | Existing Secret for MCP API key / JWT secret | `""` |
| `mcp.zapClient.existingSecret.name` | Existing Secret for the ZAP API key used by MCP | `""` |
| `mcp.zapClient.apiKey` | ZAP API key override used by MCP when not using `mcp.zapClient.existingSecret` | `""` |
| `zap.replicaCount` | Number of ZAP replicas | `1` |
| `zap.image.tag` | ZAP image tag | `2.17.0` |
| `zap.image.digest` | Optional `sha256:` image digest; overrides `zap.image.tag` | `""` |
| `zap.config.apiKey` | ZAP API key | `""` |
| `zap.config.existingSecret.name` | Existing Secret for the ZAP API key | `""` |
| `zap.config.api.allowedAddrRegex` | ZAP API source and Host allowlist regex; custom values must allow both | loopback + RFC1918 + `zap` + chart ZAP service hostname |
| `zap.config.addons` | ZAP addons installed at startup | `["spiderAjax", "client", "graphql", "soap", "automation"]` |
| `zap.persistence.enabled` | Enable persistent storage for ZAP | `true` |
| `zap.persistence.size` | Size of chart-managed persistent volume | `10Gi` |
| `zap.persistence.existingClaim` | Reuse an existing workspace PVC | `""` |
| `zap.persistence.shareWithMcp` | Share reports and automation files with MCP | `true` |
| `zap.persistence.retainOnDelete` | Retain chart-managed PVC on Helm uninstall | `true` |
| `zap.persistence.workspaceSubPath` | Optional PVC directory for reports and automation; migrate existing root-layout files before changing it | `""` |
| `zap.persistence.automationSubdirectory` | Automation directory relative to the shared mount | `automation` |
| `zap.persistence.contentImport.enabled` | Enable shared OpenAPI content staging with one MCP writer and `Recreate` rollouts | `false` |
| `zap.persistence.contentImport.subPath` | Staging directory within the PVC, separate from `workspaceSubPath` | `content-imports` |
| `zap.persistence.contentImport.mountPath` | Dedicated staging mount in both containers, read-only in ZAP | `/zap/imports` |
| `mcp.security.jwt.revocation.backend` | Token invalidation storage; multiple JWT replicas require `postgres` | `in-memory` |
| `mcp.security.jwt.revocation.postgres.url` | Shared revocation JDBC URL | `""` |
| `mcp.security.jwt.revocation.postgres.existingSecret.name` | Optional PostgreSQL credential Secret | `""` |

See [values.yaml](values.yaml) for all available options.
[values-aws.yaml](values-aws.yaml) is a private, single-MCP EKS baseline; use an encrypted tunnel or configure TLS ingress before remote access.
[values-gcp.yaml](values-gcp.yaml) is a single-MCP TLS ingress reference.
For multiple MCP replicas with shared PostgreSQL and TLS ingress, see [values-ha.yaml](values-ha.yaml). These examples require operator-provisioned infrastructure.
Before exposing the service broadly, run the [production checklist](../../docs/src/content/docs/operations/production-checklist.md).

### Shared Report and Automation Storage

By default, both pods mount the ZAP PVC at `zap.persistence.mountPath` (`/zap/wrk`).
An empty `zap.persistence.workspaceSubPath` preserves the existing PVC-root
layout. A nonempty value mounts that directory instead; this changes which files
are visible and requires a planned migration for an existing workspace.
The chart supplies matching report and automation directory settings to MCP.
This lets ZAP read submitted plans and lets MCP read files generated by ZAP;
matching directory names without a shared mount would not provide that flow.
The required `automation` add-on must also be installed before using expert automation tools.

The default automation directory remains `<shared-mount>/automation`. The report
read tools only read inside the authenticated caller's report workspace. For one
default API-key client, configure automation inside that workspace:

```yaml
zap:
  persistence:
    automationSubdirectory: workspaces/default-client/automation
```

Both automation directory settings then become
`/zap/wrk/workspaces/default-client/automation` with the default mount. The value
must be a nonempty relative directory using letters, digits, dots, underscores,
hyphens and `/` separators; absolute paths and `.` or `..` segments are rejected.
Changing `zap.persistence.mountPath` updates both containers' views of the same
claim and both automation paths together.

Match the directory segment to the actual registered client's report workspace.
For a custom client, configure `MCP_CLIENT_ID` through `mcp.env` and use its
matching workspace segment; a configured workspace identity or extension report
boundary can change that segment. Simple client IDs starting with a letter or
digit and containing only letters, digits, dots, underscores and hyphens, up to
81 characters, are used directly; other identities produce hashed report
directory names. See the
[automation workspace guide](../../docs/src/content/docs/scanning/automation-framework.md#align-automation-and-report-workspaces).

The automation directory is a process-wide setting. It does not change with the
authenticated caller or provide isolation between clients. This configuration
serves one trusted client; use separate MCP/ZAP deployments for independent
clients. Keep the report-read boundary in place rather than broadening access to
the whole shared mount.

With default `ReadWriteOnce` storage, both MCP and ZAP carry the chart-owned
`mcp-zap-server.io/workspace` label and required pod self-affinity on
`kubernetes.io/hostname`. Kubernetes permits the first matching workspace pod to
bootstrap when no peer exists; subsequent pods share its node. A replacement
ZAP pod follows a surviving MCP workspace pod, keeping it with the mounted claim.
Multiple MCP replicas with shared storage require `ReadWriteMany` storage.
The HA example references an existing `mcp-zap-workspace` claim; provision a
suitable shared filesystem, such as EFS with its CSI driver, in the release
namespace before installing. The chart does not provision EFS or its driver.
Set `zap.persistence.existingClaim` to reuse your claim and declare its actual
access mode. Verify both UID/GID 1000 processes can read and write the filesystem.
`ReadWriteOncePod` cannot support this two-pod sharing design.

Set `zap.persistence.shareWithMcp=false` only when using an external engine or
an alternative file-transfer/mount arrangement. Without shared storage, ordinary
API scans can work, but submitted automation plans and generated-report readback
need that alternative. Do not override the chart's shared-directory variables
through `mcp.env`; configure `zap.persistence.mountPath` and
`zap.persistence.automationSubdirectory` instead.

ZAP startup add-on downloads also require approved network egress. Verify the
selected image already contains the required add-ons, or configure their download
access. For a prebuilt image containing them, set `zap.config.addons=[]` to avoid
runtime installation. DNS-only egress does not permit downloads or scanning.

### OpenAPI Content Import Storage

MCP `v0.15.0` and chart `0.15.0` support OpenAPI content import. The older
`v0.14.0` image does not provide it. See the [API import guide](../../docs/src/content/docs/scanning/api-schema-imports.md)
for accepted definitions, target validation and retention limits.

For a **new** single-writer deployment using a local POSIX filesystem with OS
file locking, add these values:

```yaml
zap:
  persistence:
    enabled: true
    shareWithMcp: true
    workspaceSubPath: workspace
    contentImport:
      enabled: true
      subPath: content-imports
      mountPath: /zap/imports

mcp:
  replicaCount: 1
  deploymentStrategy:
    type: Recreate
  autoscaling:
    enabled: false
```

The PVC contains sibling `workspace` and `content-imports` directories. Both
pods mount `workspace` at `/zap/wrk`. MCP mounts `content-imports` read/write at
`/zap/imports`; ZAP sees the same files through a read-only mount. The staging
directory is outside the report and automation mounts. The chart supplies the
content enablement and directory environment variables; do not duplicate them
through `mcp.env`, Spring JSON or JVM options.

Nonroot init containers create the directories before application startup,
require staging ownership `1000:1000`, reject symlinks and set staging mode
`0750`. Keep the supported pod/container UID and GID at `1000`, `fsGroup: 1000`
and `fsGroupChangePolicy: OnRootMismatch`; staged files use mode `0640`.
The two subPaths must be distinct single directory names, and the container
mounts must be separate, without nesting. Custom identities are not supported by
this chart preset.

Content staging supports **one MCP writer**. `Recreate` stops the old MCP pod
before starting its replacement, avoiding overlap with the staging writer lock;
expect an MCP service interruption during upgrades. This preset does not establish
multi-writer or NFS/EFS support. Disabling the feature does not delete retained
staging files; apply the documented retention and cleanup policy before removing
its storage.

For an **existing PVC**, do not simply enable this block. Stop all writers and
prevent their controllers from restarting them, back up the workspace and verify
restore, then move the required report and automation files from the PVC root
into the chosen `workspaceSubPath`. Preserve their ownership and permissions;
create a separate staging directory owned by `1000:1000` with mode `0750`.
After the upgrade, verify existing report readback, a fresh report, automation
and a content import before resuming traffic. Retain the backup for rollback;
changing the values does not move existing files. Use the
[workspace preservation procedure](#preserve-the-workspace-before-changing-claims)
when also changing claims or storage providers.

## Secret Management

Production deployments should use secret references instead of committing runtime credentials in values files.

The chart fails to render when required keys or Secret references are absent;
it also rejects recognized placeholder values and short JWT secrets supplied
directly in values. Rendering does not verify the contents of an existing
Secret. Provision generated credentials before starting the pods.

```yaml
zap:
  config:
    existingSecret:
      name: mcp-zap-runtime
      apiKeyKey: ZAP_API_KEY

mcp:
  zapClient:
    existingSecret:
      name: mcp-zap-runtime
      apiKeyKey: ZAP_API_KEY
  security:
    existingSecret:
      name: mcp-zap-runtime
      apiKeyKey: MCP_API_KEY
      jwtSecretKey: JWT_SECRET
```

Your deployment pipeline should create `mcp-zap-runtime` before deployment and consume it through these references.

## Network Policy

The chart now ships with:

- ZAP ingress restricted to MCP pods by default via `networkPolicy.zap.enabled=true`
- ZAP egress restricted by default; add explicit target CIDRs/ports under `networkPolicy.zap.egress.extraEgress` for real scan traffic
- MCP ingress defaults to pods in the release namespace via `networkPolicy.mcp.enabled=true` and `networkPolicy.mcp.allowSameNamespace=true`
- MCP can reach ZAP and DNS by default; add Postgres, JWKS, or other operator-approved endpoints under `networkPolicy.mcp.egress.extraEgress`

Add permitted MCP ingress source peers under `networkPolicy.mcp.extraIngress`.
Set `networkPolicy.mcp.allowSameNamespace=false` to allow only those explicit
peers on the MCP service target port. With that setting and an empty or omitted
`extraIngress` list, the chart renders `ingress: []`, so this policy allows no
MCP ingress. Disabling the MCP NetworkPolicy omits it entirely.

NetworkPolicies are additive: another policy selecting the same MCP pods can
still allow traffic. Review all applicable policies when enforcing a deny-all
configuration. These rules require a cluster CNI that enforces Kubernetes
NetworkPolicy; rendering or installing the chart alone does not establish
network isolation.

Kubernetes [NetworkPolicy semantics](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
allow traffic to and from the node hosting a pod. CIDR exclusions therefore do
not establish isolation from that node's services; the kubelet can remain
reachable even when other private destinations are blocked. Verify
[kubelet authentication and authorization](https://kubernetes.io/docs/reference/access-authn-authz/kubelet-authn-authz/)
and the node's security configuration. If your threat model requires blocking
hosting-node services, add and verify appropriate host or CNI-specific controls
separately from this chart's NetworkPolicies.

Use the AWS and GCP reference overlays as the starting point for ingress-controller namespace, CIDR, and data-store egress allowlists.

### ZAP Web Egress and Background Requests

Prefer explicit authorized target CIDRs and ports. For public websites whose
addresses change, an operator may instead permit broader IPv4 HTTP/HTTPS egress:

```yaml
networkPolicy:
  zap:
    egress:
      extraEgress:
        - to:
            - ipBlock:
                cidr: 0.0.0.0/0
                except:
                  - 0.0.0.0/8
                  - 10.0.0.0/8
                  - 100.64.0.0/10
                  - 127.0.0.0/8
                  - 169.254.0.0/16
                  - 172.16.0.0/12
                  - 192.168.0.0/16
                  - 224.0.0.0/4
                  - 240.0.0.0/4
          ports:
            - protocol: TCP
              port: 80
            - protocol: TCP
              port: 443
```

This is broader web access, **not a per-domain allowlist** or a complete private
network boundary. Add your VPC, service, pod and node CIDRs to the exclusions when
they fall outside these ranges, and test the rules with your CNI. This example
does not grant IPv6 access. Keep MCP destination validation and target allowlists
enabled; ordinary Kubernetes NetworkPolicy cannot express permitted domain names.
An enforcing CNI, VPC routing and any outbound firewall must also permit the
intended traffic. Do not copy this policy merely to make a failing scan pass.

ZAP can make background version/update requests as well as scan-target requests.
For example, the **ZAP is Out of Date** passive rule can delay passive completion
while a blocked version check waits to time out. When a passive wait expires,
inspect ZAP's active passive rule and pending work before treating it as a target
failure or increasing the wait indefinitely. Account for approved background
endpoints in egress policy. For an offline deployment, provision the required
add-ons and review the applicable update/passive-rule configuration explicitly;
disabling all network controls is not a remedy.

### First Private EKS Deployment

If you need a new cluster, the optional
[EKS infrastructure starter](../../examples/aws-eks/) provides CloudFormation
and a walkthrough for a dedicated single-worker cluster in an existing VPC.
It creates AWS infrastructure separately from this chart. A live single-worker
trial verified bootstrap and authenticated crawl/report flows with a matched
prerelease image/chart; follow the walkthrough's checks for your own environment
and published application version.

This guidance assumes an existing EKS cluster. Start from
[values-aws.yaml](values-aws.yaml), keep both services private and select an
explicit MCP image containing the features you need. For a standard EKS cluster
using EC2 Linux workers, provision the
[EBS CSI driver and its IAM permissions](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html),
and select a working RWO StorageClass with `WaitForFirstConsumer` binding. MCP and
ZAP must fit on the same worker for the shared RWO claim; leave capacity for
cluster system pods. EKS Auto Mode has a different EBS provisioner; use the
storage configuration appropriate to your cluster.

Enable and verify [NetworkPolicy enforcement](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy.html)
in the chosen CNI before relying on the chart's policies. Replace the example
private client CIDR, provision the referenced Secret, and add only the outbound
rules required for the approved targets, add-ons and background requests. Use an
encrypted tunnel or authenticated TLS ingress to access MCP. The chart does not
create a cluster, IAM roles, storage drivers, VPC routes or a tunnel.

Review the Amazon VPC CNI's
[policy enforcement at pod startup](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html#cni-network-policy-configure-policy).
In `standard` mode, new pods initially allow traffic until their policies are
applied. In `strict` mode, they start with default deny; provide the necessary
policies for system workloads such as CoreDNS and storage drivers, including
their DNS and Kubernetes API access. This chart does not configure those system
policies. Verify DNS, EBS CSI and application startup before scanning; a passing
check after readiness does not establish that startup traffic was restricted.
Keep network-policy enforcement enabled while diagnosing cluster bootstrap.

Before expanding the deployment, verify allowed MCP access and blocked ZAP API
access from a separate workload, then crawl a controlled target, wait for passive
completion, generate and fully read a report, and run the optional import and
automation flows you enabled. Replace MCP and repeat report readback and a fresh
scan. A passing render or health check does not establish these end-to-end flows,
HA, workload capacity or persistence of an in-flight ZAP scan.

## Streamable MCP HA Exposure

Multi-replica `streamable-http` MCP is stateful per replica. If `mcp.replicaCount > 1` (without autoscaling) or enabled `mcp.autoscaling.maxReplicas > 1`, your Kubernetes Service, ingress, or load balancer must keep follow-up MCP requests on the same backend replica. The chart fails rendering when multi-replica MCP is configured without a supported affinity preset.

For example, after configuring shared PostgreSQL stores and migrations, add
this to the custom ingress values above:

```yaml
mcp:
  replicaCount: 3
  streamableHttp:
    sessionAffinity:
      enabled: true
      provider: ingress-nginx
```

Merge these settings into the existing `mcp` mapping. Affinity preserves MCP
transport sessions; it does not make in-memory queue, history, or JWT revocation
state shared. Configure the required shared stores and their network access
before using multiple replicas. When JWT support is enabled, the chart requires
`mcp.security.jwt.revocation.backend=postgres` and a PostgreSQL URL whenever
multiple replicas can run, including an HPA with minimum 1 and maximum above 1.
Use the typed `mcp.security.jwt.revocation.postgres` settings for URL, table name,
`failFast`, and optional credential Secret. URL-only PostgreSQL authentication
remains supported when no credential Secret is supplied.

Previously supplied `JWT_REVOCATION_STORE_*` entries in `mcp.env` must move to
these typed fields. The chart rejects explicit overrides of its security/JWT
variables, direct Spring aliases, and protected keys in literal
`SPRING_APPLICATION_JSON`. It also checks literal `JAVA_TOOL_OPTIONS`,
`JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` entries in `mcp.env` for `-D` overrides of
security and shared-workspace properties; configure those properties through the
typed chart fields. Unrelated bootstrap profiles and client registrations remain
supported. It cannot inspect external `envFrom` Secrets, custom images, or imported
Spring configuration. Operators must ensure those sources do not
replace the validated security settings. In-memory revocations in a single
replica still disappear on process replacement; choose PostgreSQL when they must
survive restart. Autoscaling remains disabled unless explicitly
enabled.

Supported OSS/local pattern:

- sticky sessions or equivalent client affinity at the ingress/load-balancer layer

Built-in chart presets:

- `mcp.streamableHttp.sessionAffinity.provider=aws-nlb`
- `mcp.streamableHttp.sessionAffinity.provider=ingress-nginx`
- `mcp.streamableHttp.sessionAffinity.provider=service-client-ip`

The selected preset must match the rendered exposure route. Nginx affinity
requires enabled ingress; AWS NLB affinity requires a LoadBalancer Service.
Nginx uses a stable client-address/user-agent hash, including the initialization
request before an MCP session ID exists. You may customize that stable hash.
Backend replacement or membership changes can still invalidate in-memory
transport sessions; test reconnect and rollout behaviour with your actual client.

AWS NLB TLS listeners do not support target-group stickiness. Do not combine
NLB TLS certificate annotations with the multi-replica source-IP affinity preset.
The HA example uses TLS ingress instead. Raw annotations remain operator-owned;
verify their effective behaviour with your controller. See the
[AWS NLB documentation](https://docs.aws.amazon.com/elasticloadbalancing/latest/network/edit-target-group-attributes.html).

Without that affinity, one MCP client can initialize on replica A and send follow-up requests to replica B, which does not have that in-memory transport session.

## Accessing the Service

### Local Kubernetes (kind/minikube)

```bash
# Only when installed with mcp.service.type=NodePort
kubectl get svc -n mcp-zap
export NODE_PORT=$(kubectl get svc mcp-zap-mcp-zap-server-mcp -n mcp-zap -o jsonpath='{.spec.ports[0].nodePort}')
export NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}')
echo "MCP Server: http://$NODE_IP:$NODE_PORT"

# Port forwarding (alternative)
kubectl port-forward -n mcp-zap svc/mcp-zap-mcp-zap-server-mcp 7456:7456
# Access at: http://localhost:7456
```

### Private Cloud Baseline

The AWS baseline uses ClusterIP and one MCP instance. Connect through an encrypted
operator-controlled tunnel to a loopback `kubectl port-forward`, or configure a
TLS ingress and permitted ingress sources. Never send credentials directly to a
non-local HTTP load-balancer address. A private/internal load balancer still needs
transport encryption. Custom LoadBalancer exposure requires controller-specific
TLS configuration and deployment testing; the chart does not create certificates.

### With Ingress

```bash
# Access via domain
curl https://mcp-zap.example.com/actuator/health
```

## Upgrading

Review the new storage/exposure defaults before an upgrade: AWS and secure-Secret
examples are single-replica/private, HA uses TLS ingress and existing RWX storage,
and raw JWT revocation environment entries move to typed chart fields.

### Preserve the workspace before changing claims

For a `0.13.0` to `0.14.0` upgrade, keeping the same chart-managed PVC does not
require `zap.persistence.existingClaim`: both pods now share that claim by default.
Moving to the HA reference's RWX claim, or setting `existingClaim` to the old
chart-managed claim, changes Helm's ownership of the old PVC. Follow this procedure
before either change.

The `0.13.0` chart did not annotate its PVC for retention. Setting `existingClaim`
removes that PVC from the new chart manifest, so Helm can delete it during the
upgrade. The `0.14.0` default `retainOnDelete=true` cannot protect a resource omitted
from that manifest. Add and verify the annotation on the **live old PVC before
changing the claim or its ownership**.

1. Identify the actual workspace claim from the running ZAP pod and inspect any
   MCP mounts. Use your release and namespace; do not infer the PVC name from an
   example or the release name. Record the current values, claim, bound PV, storage
   class, access modes, and PV reclaim policy before starting the cutover.

   ```bash
   HELM_RELEASE=mcp-zap
   RELEASE_NAMESPACE=mcp-zap
   kubectl get pods --namespace "$RELEASE_NAMESPACE" \
     --selector "app.kubernetes.io/instance=$HELM_RELEASE" \
     -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{range .spec.volumes[*]}{.name}{"="}{.persistentVolumeClaim.claimName}{" "}{end}{"\n"}{end}'

   # Set this to the zap-data claim shown for the running ZAP pod.
   OLD_WORKSPACE_CLAIM='<actual-workspace-claim>'
   kubectl get pvc "$OLD_WORKSPACE_CLAIM" --namespace "$RELEASE_NAMESPACE" -o yaml
   OLD_WORKSPACE_PV="$(kubectl get pvc "$OLD_WORKSPACE_CLAIM" \
     --namespace "$RELEASE_NAMESPACE" -o jsonpath='{.spec.volumeName}')"
   : "${OLD_WORKSPACE_PV:?The old workspace PVC must be bound}"
   kubectl get pv "$OLD_WORKSPACE_PV" \
     -o jsonpath='{.metadata.name}{"\t"}{.spec.persistentVolumeReclaimPolicy}{"\n"}'
   ```

2. Stop if the old PVC is already being deleted. Otherwise, preserve it and verify
   the live annotation before running a storage-changing Helm upgrade:

   ```bash
   (
     set -e
     OLD_WORKSPACE_DELETION_TIME="$(kubectl get pvc "$OLD_WORKSPACE_CLAIM" \
       --namespace "$RELEASE_NAMESPACE" -o jsonpath='{.metadata.deletionTimestamp}')"
     test -z "$OLD_WORKSPACE_DELETION_TIME"
     kubectl annotate pvc "$OLD_WORKSPACE_CLAIM" --namespace "$RELEASE_NAMESPACE" \
       helm.sh/resource-policy=keep --overwrite
     OLD_WORKSPACE_KEEP_POLICY="$(kubectl get pvc "$OLD_WORKSPACE_CLAIM" \
       --namespace "$RELEASE_NAMESPACE" \
       -o jsonpath='{.metadata.annotations.helm\.sh/resource-policy}')"
     test "$OLD_WORKSPACE_KEEP_POLICY" = keep
   )
   ```

   Continue only if both checks succeed. This prevents Helm's upgrade deletion of
   the old claim; it does not replace a backup.

3. Hold new scan submissions, drain or stop active work, and quiesce every process
   writing the workspace, including MCP, ZAP, external workers, and scheduled jobs.
   Ensure controllers cannot restart writers during migration. Take a consistent
   filesystem backup or supported storage snapshot and verify its restore before
   moving files. A workspace backup does not capture in-memory scans or external
   PostgreSQL state; preserve required database state separately.

4. For a move to RWX, provision a new claim in the release namespace and restore
   the required workspace data, including report and automation files, using a
   transfer procedure validated for your source and destination storage. Preserve
   the required file ownership and permissions, and verify read/write access with
   the actual MCP and ZAP UID/GID (both default to `1000:1000`). EFS access-point
   identity rules and CSI permissions require deployment-specific verification;
   this guide does not establish a tested EFS transfer procedure. Declaring
   `accessMode: ReadWriteMany` does not convert an existing RWO volume into RWX.

5. Set `zap.persistence.existingClaim` to the verified destination claim and
   `accessMode` to its real supported mode. Keep the old claim and backup. Review
   the rendered workloads and saved values, then perform the upgrade during the
   cutover window. When adopting the same old claim, keep its actual access mode;
   multiple MCP replicas still require genuine RWX storage.

6. Before resuming normal traffic, confirm both workloads mount the intended claim,
   can access the same files, and become ready. Verify existing report readback,
   generate and read a new report, and submit a representative automation plan.
   For HA, verify access from each MCP replica and test session routing. Keep the
   old volume until these checks and the recovery procedure have passed.

7. Retire the old claim only after confirming it has no remaining users and its
   data is no longer required. A PV reclaim policy of `Delete` can remove the
   backing storage after PVC deletion; `Retain` leaves storage for explicit
   recovery or cleanup. The Helm `keep` annotation does not protect a PVC from
   manual deletion or namespace deletion. Review the storage provider's actual
   reclaim behavior before either action.

### Database migrations and upgrade commands

When migrations are enabled, Helm creates the SQL ConfigMap as a weight -10
pre-install/pre-upgrade hook, then runs Flyway at weight 0. The SQL remains mounted
until the Job finishes and is refreshed before the next migration. Provision the
database credential Secret before installation; hook pods do not use the chart's
ordinary ServiceAccount. Test a clean install and an upgrade adding SQL against
a disposable database before production. The 0.14.0 application migrations remain
unchanged from 0.13.0.

When upgrading from a chart that managed the SQL ConfigMap as an ordinary resource,
the migration hook can complete successfully before Helm deletes that former
ordinary resource with the same name. The ConfigMap may therefore be absent after
the first upgrade to hooks; the next migration hook recreates it.

```bash
# Upgrade with new values
helm upgrade mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml

# Upgrade with specific image version
helm upgrade mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml \
  --set mcp.image.tag=v0.15.0
```

## Uninstalling

```bash
# Uninstall the release
helm uninstall mcp-zap --namespace mcp-zap

```

The chart retains its PVC by default (`zap.persistence.retainOnDelete=true`).
For a claim still managed by an older chart, check `helm get manifest` for its
stored `helm.sh/resource-policy: keep` annotation before uninstalling. Helm's
uninstall filtering uses the stored release manifest; an annotation added only
to the live PVC does not guarantee retention during uninstall. Complete the
preservation migration above before removing an older release whose manifest
does not retain the claim.
A separately provisioned `existingClaim` is not managed by this release; adopting
a formerly chart-managed claim requires the preservation steps above. Hook SQL
ConfigMaps can remain
until the next migration hook replaces them and are not normally removed by Helm
uninstall. The first upgrade from an ordinary SQL ConfigMap has the deletion caveat
described above. List the release's remaining PVC and migration ConfigMap,
back up required data, and delete them explicitly only when no longer needed.
Deleting the namespace removes its PVCs even when Helm retention is enabled;
backing-volume deletion then depends on the PV reclaim policy.

For reinstallation with retained data, set `zap.persistence.existingClaim` to
the retained claim. Retaining files does not make every in-memory ZAP scan durable;
verify backup/restore and engine recovery separately.

## Monitoring

MCP liveness checks its TCP listener independently of ZAP. Readiness uses aggregate
`/actuator/health`, so an engine outage stops normal traffic without itself forcing
MCP restarts. TCP liveness does not prove every application operation is healthy.
From MCP `v0.15.0`, health probes also reconcile ZAP's configured user-agent,
connection timeout and supported DNS TTL.
MCP remains unready if mandatory settings cannot be applied, and retries after
ZAP starts or restarts. Outbound scan, import, automation and authentication-test
starts perform the same reconciliation, including requests on an existing MCP
session. These settings affect ZAP's outbound traffic; MCP-to-ZAP API timeouts
remain separately controlled by `mcp.zapClient`.
If opting back into HTTP liveness, clear `mcp.livenessProbe.tcpSocket` and supply
an appropriate `httpGet`; avoid dependency health as a process restart trigger.

Check deployment status:

```bash
# Get all resources
kubectl get all -n mcp-zap

# Check pod logs
kubectl logs -n mcp-zap -l app.kubernetes.io/name=mcp-server
kubectl logs -n mcp-zap -l app.kubernetes.io/name=zap-proxy

# Check pod status
kubectl describe pod -n mcp-zap <pod-name>
```

## Troubleshooting

### ZAP Pod Not Starting

```bash
# Check ZAP logs
kubectl logs -n mcp-zap -l app.kubernetes.io/name=zap-proxy

# Check PVC status
kubectl get pvc -n mcp-zap

# Describe PVC for issues
kubectl describe pvc -n mcp-zap mcp-zap-mcp-zap-server-zap-pvc
```

### MCP Server Cannot Connect to ZAP

```bash
# Check MCP readiness, probe failures, and application diagnostics
kubectl get pods -n mcp-zap \
  -l app.kubernetes.io/name=mcp-server,app.kubernetes.io/instance=mcp-zap
kubectl describe pods -n mcp-zap \
  -l app.kubernetes.io/name=mcp-server,app.kubernetes.io/instance=mcp-zap
kubectl logs -n mcp-zap \
  -l app.kubernetes.io/name=mcp-server,app.kubernetes.io/instance=mcp-zap

# Verify the configured ZAP variable names without exposing secret values
MCP_DEPLOYMENT="$(kubectl get deployment -n mcp-zap \
  -l app.kubernetes.io/name=mcp-server,app.kubernetes.io/instance=mcp-zap \
  -o jsonpath='{.items[0].metadata.name}')"
kubectl get deployment -n mcp-zap "$MCP_DEPLOYMENT" \
  -o jsonpath='{range .spec.template.spec.containers[?(@.name=="mcp-server")].env[*]}{.name}{"\n"}{end}' \
  | grep '^ZAP_'

# Test service DNS and HTTP reachability from an existing MCP pod
MCP_POD="$(kubectl get pod -n mcp-zap \
  -l app.kubernetes.io/name=mcp-server,app.kubernetes.io/instance=mcp-zap \
  -o jsonpath='{.items[0].metadata.name}')"
ZAP_SERVICE="$(kubectl get service -n mcp-zap \
  -l app.kubernetes.io/name=zap-proxy,app.kubernetes.io/instance=mcp-zap \
  -o jsonpath='{.items[0].metadata.name}')"
kubectl exec -n mcp-zap "$MCP_POD" -c mcp-server -- \
  /usr/local/bin/http-healthcheck "http://${ZAP_SERVICE}:8090/" \
  && echo "ZAP service is reachable from the MCP pod"
```

The MCP runtime is distroless and has no in-container shell, `curl`, or `env`
executable. The last command executes only the image's built-in static HTTP
probe; it prints a diagnostic and exits non-zero when DNS, TCP, or HTTP fails.
If your Helm release or namespace is not `mcp-zap`, update the instance labels
and namespace in these commands.

### Horizontal Pod Autoscaler Not Working

```bash
# Check HPA status
kubectl get hpa -n mcp-zap

# Check metrics server
kubectl top pods -n mcp-zap

# If metrics server is not installed:
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

## Examples

### Example 1: Local Development (kind)

```bash
# Create kind cluster
kind create cluster --name mcp-dev
```

Then follow [Prepare Credentials](#prepare-credentials) and the
[local installation](#installation) above.
The local path keeps API-key authentication enabled.

### Example 2: AWS EKS + RDS (HA Queue Coordinator + JWT Revocation Store)

Provision an RDS database and supply its credentials and generated runtime
secrets through your deployment environment before running these commands.
Replace the database placeholders, ingress hostname/controller namespace and
database CIDRs in the copied values file, and add authorized scan-target egress.
Provision the TLS Secret and an RWX PVC matching `zap.persistence.existingClaim`.
The database, ingress controller, certificates, filesystem and network
infrastructure are not created by this chart.

```bash
# Refuse empty deployment credentials
: "${RDS_USERNAME:?Set the database username}"
: "${RDS_PASSWORD:?Set the database password}"
: "${ZAP_API_KEY:?Set a generated ZAP API key}"
: "${MCP_API_KEY:?Set a generated MCP API key}"
: "${JWT_SECRET:?Set a generated JWT signing secret}"

# 1) Create runtime secret for RDS credentials used by Flyway + MCP shared Postgres access
kubectl create namespace mcp-zap-prod
kubectl create secret generic mcp-zap-rds \
  --namespace mcp-zap-prod \
  --from-literal=RDS_USERNAME="${RDS_USERNAME}" \
  --from-literal=RDS_PASSWORD="${RDS_PASSWORD}"

# 2) Copy and edit HA values file (replace <rds-endpoint> and <db>)
cp ./helm/mcp-zap-server/values-ha.yaml /tmp/mcp-zap-values-ha.yaml
$EDITOR /tmp/mcp-zap-values-ha.yaml

# values-ha.yaml uses TLS nginx ingress and a stable client hash.
# Provision the TLS Secret and RWX workspace claim before installation.

# 3) Deploy chart with HA reference values + runtime secrets
kubectl create secret generic mcp-zap-runtime \
  --namespace mcp-zap-prod \
  --from-literal=ZAP_API_KEY="${ZAP_API_KEY}" \
  --from-literal=MCP_API_KEY="${MCP_API_KEY}" \
  --from-literal=JWT_SECRET="${JWT_SECRET}"

helm upgrade --install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap-prod \
  --values /tmp/mcp-zap-values-ha.yaml

# 4) Validate that the Flyway migration hook completed and MCP leadership is healthy
kubectl get jobs -n mcp-zap-prod
kubectl get pods -n mcp-zap-prod -l app.kubernetes.io/name=mcp-server
kubectl logs -n mcp-zap-prod -l app.kubernetes.io/name=mcp-server | grep -E "leadership acquired|leadership released"
```

### Example 3: GKE with Ingress + TLS

Provision an ingress-nginx controller and a TLS Secret for your hostname, then
use the [custom values example](#custom-values-file). That example includes
runtime Secret references and permits the ingress-controller namespace through
the MCP NetworkPolicy. Configure your certificate issuer separately if you use
cert-manager.

```bash
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml
```

## Security Best Practices

1. **Provision generated API keys** before installation
2. **Enable JWT authentication** for production deployments
3. **Use TLS/SSL** for external access (via Ingress or LoadBalancer)
4. **Enable sticky ingress or equivalent client affinity** for multi-replica OSS/local streamable MCP
5. **Keep ZAP private** and only expose the MCP endpoint
6. **Restrict network access** using NetworkPolicies or security groups
7. **Use Kubernetes secrets** for sensitive data instead of values.yaml
8. **Pin image tags** and review ZAP release updates regularly
9. **Verify image signatures and provenance** before recommending a release build
10. **Run the production checklist** before each rollout

## Support

For issues and questions:
- [GitHub issues](https://github.com/dtkmn/mcp-zap-server/issues)
- [Documentation](https://danieltse.org/mcp-zap-server/)
