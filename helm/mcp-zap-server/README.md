# MCP ZAP Server Helm Chart

This Helm chart deploys the MCP ZAP Server (Model Context Protocol server for OWASP ZAP) on Kubernetes.

## Architecture

This chart deploys two main components in **separate pods**:

1. **ZAP Proxy Pod** (1 replica, stateful)
   - OWASP ZAP security scanner
   - Handles all security scanning operations
   - Persistent storage for scan data
   - Resource-intensive (2-4GB RAM)

2. **MCP Server Pods** (3+ replicas, stateless)
   - REST API gateway to ZAP
   - Horizontally scalable
   - Lightweight (512MB-1GB RAM)
   - Auto-scaling enabled

## Prerequisites

- Kubernetes 1.23+
- Helm 3.8+
- PV provisioner support in the underlying infrastructure (for ZAP persistence)

## Installation

### Quick Start (Local Development - kind/minikube)

```bash
# Create namespace
kubectl create namespace mcp-zap

# Install with default values (API key security enabled)
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set mcp.service.type=NodePort
```

### Production Deployment

```bash
# Install with JWT authentication and an explicit external exposure choice
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set mcp.service.type=LoadBalancer \
  --set mcp.security.mode=jwt \
  --set mcp.security.allowPlaceholderApiKey=false \
  --set mcp.security.jwt.enabled=true \
  --set mcp.security.jwt.secret="your-secret-key-here" \
  --set zap.config.apiKey="your-zap-api-key" \
  --set mcp.zapClient.apiKey="your-zap-api-key"
```

### Custom Values File

Create `custom-values.yaml`:

```yaml
mcp:
  replicaCount: 5
  security:
    mode: api-key
    allowPlaceholderApiKey: false
    apiKey: "my-secure-api-key"
  
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
    apiKey: "my-zap-api-key"
  persistence:
    size: 20Gi
```

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
| `mcp.replicaCount` | Number of MCP server replicas | `3` |
| `mcp.security.mode` | Authentication mode (none/api-key/jwt) | `api-key` |
| `mcp.security.apiKey` | MCP API key when not using `mcp.security.existingSecret` | `""` |
| `mcp.service.type` | Kubernetes service type | `ClusterIP` |
| `mcp.autoscaling.enabled` | Enable horizontal pod autoscaler | `true` |
| `mcp.autoscaling.maxReplicas` | Maximum replicas for autoscaling | `10` |
| `mcp.security.allowPlaceholderApiKey` | Allow placeholder MCP API keys instead of failing startup | `false` |
| `mcp.streamableHttp.sessionAffinity.provider` | Sticky-session preset for OSS multi-replica streamable MCP | `""` |
| `mcp.image.tag` | MCP image tag | chart `appVersion` |
| `mcp.zapClient.url` | ZAP API hostname/service | chart-managed service (`<release>-mcp-zap-server-zap`) |
| `mcp.security.existingSecret.name` | Existing Secret for MCP API key / JWT secret | `""` |
| `mcp.zapClient.existingSecret.name` | Existing Secret for the ZAP API key used by MCP | `""` |
| `mcp.zapClient.apiKey` | ZAP API key override used by MCP when not using `mcp.zapClient.existingSecret` | `""` |
| `zap.replicaCount` | Number of ZAP replicas | `1` |
| `zap.image.tag` | ZAP image tag | `2.17.0` |
| `zap.config.apiKey` | ZAP API key | `""` |
| `zap.config.existingSecret.name` | Existing Secret for the ZAP API key | `""` |
| `zap.config.api.allowedAddrRegex` | ZAP API source allowlist regex | loopback + RFC1918 |
| `zap.config.addons` | ZAP addons installed at startup | `["ajaxSpider", "graphql", "soap", "automation"]` |
| `zap.persistence.enabled` | Enable persistent storage for ZAP | `true` |
| `zap.persistence.size` | Size of ZAP persistent volume | `10Gi` |

See [values.yaml](values.yaml) for all available options.
For an AWS/EKS multi-replica baseline, see [values-ha.yaml](values-ha.yaml).
For opinionated cloud overlays, see [values-aws.yaml](values-aws.yaml) and [values-gcp.yaml](values-gcp.yaml).
Before exposing the service broadly, run the [production checklist](../../docs/operator/PRODUCTION_CHECKLIST.md).

Automation Framework note:

- `zap_automation_*` tools require the ZAP `automation` add-on and a shared workspace path that both the MCP pod and the ZAP pod can read/write.
- This chart installs the add-on by default, but it does not provision a shared RWX workspace automatically.
- For Kubernetes deployments, mount a shared volume into both pods and pass matching `ZAP_AUTOMATION_LOCAL_DIRECTORY` and `ZAP_AUTOMATION_ZAP_DIRECTORY` values through `mcp.env`.

## Secret Management

Production deployments should use secret references instead of committing runtime credentials in values files.

The chart intentionally fails to render if you leave required runtime secrets blank or on placeholder values. Set explicit keys in your values file for non-secret demos, or use `existingSecret` references for real deployments.

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

The private CI/CD workflow now creates `mcp-zap-runtime` before deployment and consumes it through these references.

## Network Policy

The chart now ships with:

- ZAP ingress restricted to MCP pods by default via `networkPolicy.zap.enabled=true`
- optional MCP ingress restriction through `networkPolicy.mcp.enabled` and `networkPolicy.mcp.extraIngress`

Use the AWS and GCP reference overlays as the starting point for ingress-controller namespace or CIDR allowlists.

## Streamable MCP HA Exposure

For the current OSS/local HA path, multi-replica `streamable-http` MCP is stateful per replica. If `mcp.replicaCount > 1`, your ingress or load balancer must keep follow-up MCP requests on the same backend replica.

Supported OSS/local pattern:

- sticky sessions or equivalent client affinity at the ingress/load-balancer layer

Built-in chart presets:

- `mcp.streamableHttp.sessionAffinity.provider=aws-nlb`
- `mcp.streamableHttp.sessionAffinity.provider=ingress-nginx`

The chart still lets you add or override raw `mcp.service.annotations` and `mcp.ingress.annotations` if your controller needs different settings.

Without that affinity, one MCP client can initialize on replica A and send follow-up requests to replica B, which does not have that in-memory transport session.

Edition difference:

- OSS/local HA: this chart documents the current sticky-session/client-affinity operating model for multi-replica `streamable-http` MCP
- Enterprise enhancement path: targets cloud-native transport/session handling so any MCP request can be served by any healthy replica without ingress stickiness as a correctness requirement

## Accessing the Service

### Local Kubernetes (kind/minikube)

```bash
# Using NodePort
kubectl get svc -n mcp-zap
export NODE_PORT=$(kubectl get svc mcp-zap-mcp -n mcp-zap -o jsonpath='{.spec.ports[0].nodePort}')
export NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}')
echo "MCP Server: http://$NODE_IP:$NODE_PORT"

# Port forwarding (alternative)
kubectl port-forward -n mcp-zap svc/mcp-zap-mcp 7456:7456
# Access at: http://localhost:7456
```

### Cloud Kubernetes (AWS/GCP/Azure, if `mcp.service.type=LoadBalancer`)

```bash
# Get LoadBalancer IP or hostname
kubectl get svc -n mcp-zap mcp-zap-mcp

# Access via LoadBalancer address
export LB_ADDR=$(kubectl get svc mcp-zap-mcp -n mcp-zap -o jsonpath='{.status.loadBalancer.ingress[0].hostname}{.status.loadBalancer.ingress[0].ip}')
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
  --set mcp.image.tag=0.6.0
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
kubectl describe pvc -n mcp-zap mcp-zap-zap-pvc
```

### MCP Server Cannot Connect to ZAP

```bash
# Check if ZAP service is accessible
kubectl exec -n mcp-zap deployment/mcp-zap-mcp -- curl http://mcp-zap-zap:8090

# Verify environment variables
kubectl exec -n mcp-zap deployment/mcp-zap-mcp -- env | grep ZAP
```

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

# Install chart
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap --create-namespace \
  --set mcp.service.type=NodePort \
  --set mcp.security.mode=none

# Access service
kubectl port-forward -n mcp-zap svc/mcp-zap-mcp 7456:7456
```

### Example 2: AWS EKS + RDS (HA Queue Coordinator + JWT Revocation Store)

```bash
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

```bash
# Install cert-manager first
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Install chart with ingress. For multi-replica streamable MCP, add an
# ingress-nginx affinity preset so follow-up MCP requests stay on one replica.
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap --create-namespace \
  --set mcp.ingress.enabled=true \
  --set mcp.ingress.className=nginx \
  --set mcp.streamableHttp.sessionAffinity.enabled=true \
  --set mcp.streamableHttp.sessionAffinity.provider=ingress-nginx \
  --set mcp.ingress.hosts[0].host=mcp-zap.example.com \
  --set mcp.ingress.tls[0].secretName=mcp-zap-tls \
  --set mcp.ingress.tls[0].hosts[0]=mcp-zap.example.com
```

## Security Best Practices

1. **Always change default API keys** in production
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
- GitHub: https://github.com/dtkmn/mcp-zap-server/issues
- Documentation: https://dtkmn.github.io/mcp-zap-server/
