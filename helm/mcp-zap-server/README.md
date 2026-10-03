# MCP ZAP Server Helm Chart

This Helm chart deploys the MCP ZAP Server (Model Context Protocol server for ZAP) on Kubernetes.

## Chart And Image Versions

Chart `0.14.0` defaults to MCP image `v0.14.0`. Use the chart from the same release
as your image, and confirm that the corresponding release workflow has published
the image before installing. [GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases)
is the source for publication status. Release preparation does not publish images.
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
startup, readiness, and liveness checks are Kubernetes-native HTTP probes; they
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
| `mcp.security.allowPlaceholderApiKey` | Opt in to placeholder MCP API keys; in development/unreleased builds, requires nonempty active Spring profiles all among `local`, `dev`, and `test` | `false` |
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
| `zap.persistence.size` | Size of ZAP persistent volume | `10Gi` |

See [values.yaml](values.yaml) for all available options.
For an AWS/EKS multi-replica baseline, see [values-ha.yaml](values-ha.yaml).
For opinionated cloud overlays, see [values-aws.yaml](values-aws.yaml) and [values-gcp.yaml](values-gcp.yaml).
Before exposing the service broadly, run the [production checklist](../../docs/src/content/docs/operations/production-checklist.md).

Automation Framework note:

- `zap_automation_*` tools require the ZAP `automation` add-on and a shared workspace path that both the MCP pod and the ZAP pod can read/write.
- This chart installs the add-on by default, but it does not provision a shared RWX workspace automatically.
- For Kubernetes deployments, mount a shared volume into both pods and pass matching `ZAP_AUTOMATION_LOCAL_DIRECTORY` and `ZAP_AUTOMATION_ZAP_DIRECTORY` values through `mcp.env`.

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

Use the AWS and GCP reference overlays as the starting point for ingress-controller namespace, CIDR, and data-store egress allowlists.

## Streamable MCP HA Exposure

Multi-replica `streamable-http` MCP is stateful per replica. If `mcp.replicaCount > 1` or `mcp.autoscaling.minReplicas > 1`, your Kubernetes Service, ingress, or load balancer must keep follow-up MCP requests on the same backend replica. The chart fails rendering when multi-replica MCP is configured without a supported affinity preset.

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
before using multiple replicas. Autoscaling remains disabled unless explicitly
enabled.

Supported OSS/local pattern:

- sticky sessions or equivalent client affinity at the ingress/load-balancer layer

Built-in chart presets:

- `mcp.streamableHttp.sessionAffinity.provider=aws-nlb`
- `mcp.streamableHttp.sessionAffinity.provider=ingress-nginx`
- `mcp.streamableHttp.sessionAffinity.provider=service-client-ip`

The chart still lets you add or override raw `mcp.service.annotations` and `mcp.ingress.annotations` if your controller needs different settings.

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

### Cloud Kubernetes (AWS/GCP/Azure, if `mcp.service.type=LoadBalancer`)

```bash
# Get LoadBalancer IP or hostname
kubectl get svc -n mcp-zap mcp-zap-mcp-zap-server-mcp

# Access via LoadBalancer address
export LB_ADDR=$(kubectl get svc mcp-zap-mcp-zap-server-mcp -n mcp-zap -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{.status.loadBalancer.ingress[0].ip}')
echo "MCP Server: http://$LB_ADDR:7456"
```

### With Ingress

```bash
# Access via domain
curl https://mcp-zap.example.com/actuator/health
```

## Upgrading

```bash
# Upgrade with new values
helm upgrade mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml

# Upgrade with specific image version
helm upgrade mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml \
  --set mcp.image.tag=v0.14.0
```

## Uninstalling

```bash
# Uninstall the release
helm uninstall mcp-zap --namespace mcp-zap

# Delete the namespace (optional)
kubectl delete namespace mcp-zap
```

## Monitoring

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
Replace the database placeholders and the ingress/database CIDRs in the copied
values file, and add the authorized scan-target egress rules. The database and
network infrastructure are not created by this chart.

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

# values-ha.yaml already enables NLB source-IP affinity for the current
# OSS/local streamable MCP transport contract. Preserve equivalent
# client-affinity behavior if you replace the exposure model.

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
