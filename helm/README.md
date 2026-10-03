# Helm Charts

This directory contains Helm charts for deploying MCP ZAP Server to Kubernetes.

Use the chart from the same release as your MCP image. Its `appVersion` sets
the default image tag. Before installing, confirm image availability in
[GitHub Releases](https://github.com/dtkmn/mcp-zap-server/releases) and the
corresponding release workflow. See
[chart and image versions](mcp-zap-server/README.md#chart-and-image-versions).

## Quick Start

### Prerequisites

- Kubernetes cluster (kind, minikube, EKS, GKE, AKS)
- Helm 3.8+
- kubectl configured

### Installation

Follow the chart's [credential preparation and installation guide](mcp-zap-server/README.md#installation).
It contains the complete API-key and JWT examples, including the required
Kubernetes Secret references. Its commands run from the repository root and
use `./helm/mcp-zap-server` as the chart path.

The default service is `ClusterIP`; use the documented port-forward for local
access to `http://localhost:7456/mcp`. For cloud deployments, configure a
controlled TLS ingress and the required network rules before exposing the
service. A JWT mode selection alone does not configure exposure or credentials.

## Architecture

The Helm chart deploys:

1. **ZAP Proxy** (1 pod)
   - Stateful deployment
   - Persistent volume for scan data
   - 2-4GB RAM

2. **MCP Server** (1 pod by default)
   - Streamable MCP sessions are stored in memory per replica
   - Additional replicas require session affinity and appropriate shared stores
   - Auto-scaling is disabled by default
   - 512Mi memory request and 1Gi limit by default

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

Use the chart's [custom values example](mcp-zap-server/README.md#custom-values-file)
to keep credential references, ingress, and network policy together. Read the
[multi-replica requirements](mcp-zap-server/README.md#streamable-mcp-ha-exposure)
before changing the replica count or enabling autoscaling.

## Upgrading

Read the [workspace preservation procedure](mcp-zap-server/README.md#preserve-the-workspace-before-changing-claims)
before moving a `0.13.0` chart-managed PVC to `existingClaim` or the HA reference's
RWX storage. Protect the actual live old claim before changing ownership, back up
with writers quiesced, and verify restored data and both workloads before retiring
the old volume. Setting `retainOnDelete=true` during the same upgrade does not
protect a PVC omitted from the new manifest.

```bash
# Run from the repository root with the values used for your deployment
helm upgrade mcp-zap ./helm/mcp-zap-server \
  --namespace mcp-zap \
  --values custom-values.yaml
```

## Uninstalling

```bash
helm uninstall mcp-zap --namespace mcp-zap
```

Review the stored Helm release manifest and backup before uninstalling, especially
for older charts. A keep annotation added only to the live PVC does not establish
uninstall retention; use the detailed guide's preservation procedure first.
Namespace deletion is a separate, destructive cleanup step: it
removes PVCs even when Helm retention is enabled, and a PV reclaim policy of
`Delete` can also remove backing storage. Keep the namespace until its remaining
data and resources can be retired deliberately; follow the
[storage and uninstall guidance](mcp-zap-server/README.md#uninstalling).
