# Helm Charts

This directory contains Helm charts for deploying MCP ZAP Server to Kubernetes.

## Quick Start

### Prerequisites

- Kubernetes cluster (kind, minikube, EKS, GKE, AKS)
- Helm 3.8+
- kubectl configured

### Install on Local Kubernetes (kind)

```bash
# 1. Create kind cluster
kind create cluster --name mcp-dev

# 2. Install MCP ZAP Server
helm install mcp-zap ./mcp-zap-server \
  --namespace mcp-zap \
  --create-namespace \
  --set mcp.service.type=NodePort

# 3. Access the service
kubectl port-forward -n mcp-zap svc/mcp-zap-mcp 7456:7456
```

Access at: http://localhost:7456

### Install on Cloud Kubernetes

```bash
# AWS EKS
helm install mcp-zap ./mcp-zap-server \
  --namespace mcp-zap \
  --create-namespace \
  --set mcp.security.mode=jwt \
  --set mcp.security.jwt.secret="$(openssl rand -base64 32)"

# Get LoadBalancer IP
kubectl get svc -n mcp-zap mcp-zap-mcp
```

## Architecture

The Helm chart deploys:

1. **ZAP Proxy** (1 pod)
   - Stateful deployment
   - Persistent volume for scan data
   - 2-4GB RAM

2. **MCP Server** (3+ pods)
   - Stateless deployment
   - Auto-scaling enabled
   - 512MB RAM per pod

## Documentation

See [mcp-zap-server/README.md](mcp-zap-server/README.md) for detailed documentation.

## Chart Structure

```
mcp-zap-server/
├── Chart.yaml              # Chart metadata
├── values.yaml             # Default configuration values
├── templates/
│   ├── _helpers.tpl        # Template helpers
│   ├── zap-deployment.yaml # ZAP proxy deployment
│   ├── zap-service.yaml    # ZAP service
│   ├── zap-pvc.yaml        # ZAP persistent volume claim
│   ├── mcp-deployment.yaml # MCP server deployment
│   ├── mcp-service.yaml    # MCP service
│   ├── mcp-hpa.yaml        # Horizontal pod autoscaler
│   ├── mcp-ingress.yaml    # Ingress (optional)
│   ├── configmap.yaml      # Configuration
│   └── serviceaccount.yaml # Service account
└── README.md               # Detailed documentation
```

## Customization

Create `custom-values.yaml`:

```yaml
mcp:
  replicaCount: 5
  security:
    mode: api-key
    apiKey: "my-secure-key"
  
  autoscaling:
    maxReplicas: 20

zap:
  persistence:
    size: 50Gi
```

Install with custom values:

```bash
helm install mcp-zap ./mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml
```

## Upgrading

```bash
helm upgrade mcp-zap ./mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml
```

## Uninstalling

```bash
helm uninstall mcp-zap --namespace mcp-zap
kubectl delete namespace mcp-zap
```
