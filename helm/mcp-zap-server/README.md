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

# Install with default values (no security)
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set mcp.service.type=NodePort
```

### Production Deployment

```bash
# Install with JWT authentication
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --set mcp.security.mode=jwt \
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
| `mcp.security.mode` | Authentication mode (none/api-key/jwt) | `none` |
| `mcp.service.type` | Kubernetes service type | `LoadBalancer` |
| `mcp.autoscaling.enabled` | Enable horizontal pod autoscaler | `true` |
| `mcp.autoscaling.maxReplicas` | Maximum replicas for autoscaling | `10` |
| `zap.replicaCount` | Number of ZAP replicas | `1` |
| `zap.config.apiKey` | ZAP API key | `changeme-zap-api-key` |
| `zap.persistence.enabled` | Enable persistent storage for ZAP | `true` |
| `zap.persistence.size` | Size of ZAP persistent volume | `10Gi` |

See [values.yaml](values.yaml) for all available options.

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

### Cloud Kubernetes (AWS/GCP/Azure)

```bash
# Get LoadBalancer external IP
kubectl get svc -n mcp-zap mcp-zap-mcp

# Access via LoadBalancer IP
export LB_IP=$(kubectl get svc mcp-zap-mcp -n mcp-zap -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
echo "MCP Server: http://$LB_IP:7456"
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
  --set mcp.image.tag=0.2.1
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

### Example 2: AWS EKS with LoadBalancer

```bash
# Install with AWS NLB
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap --create-namespace \
  --set mcp.service.type=LoadBalancer \
  --set mcp.service.annotations."service\.beta\.kubernetes\.io/aws-load-balancer-type"=nlb \
  --set mcp.security.mode=jwt \
  --set mcp.security.jwt.enabled=true \
  --set mcp.security.jwt.secret="${JWT_SECRET}"
```

### Example 3: GKE with Ingress + TLS

```bash
# Install cert-manager first
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.0/cert-manager.yaml

# Install chart with ingress
helm install mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap --create-namespace \
  --set mcp.ingress.enabled=true \
  --set mcp.ingress.className=gce \
  --set mcp.ingress.hosts[0].host=mcp-zap.example.com \
  --set mcp.ingress.tls[0].secretName=mcp-zap-tls \
  --set mcp.ingress.tls[0].hosts[0]=mcp-zap.example.com
```

## Security Best Practices

1. **Always change default API keys** in production
2. **Enable JWT authentication** for production deployments
3. **Use TLS/SSL** for external access (via Ingress or LoadBalancer)
4. **Restrict network access** using NetworkPolicies
5. **Use Kubernetes secrets** for sensitive data instead of values.yaml
6. **Enable Pod Security Policies** or Pod Security Standards
7. **Regularly update** ZAP and MCP server images

## Support

For issues and questions:
- GitHub: https://github.com/dtkmn/mcp-zap-server/issues
- Documentation: https://github.com/dtkmn/mcp-zap-server
