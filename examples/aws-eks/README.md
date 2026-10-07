# AWS EKS infrastructure starter

This example creates a new, dedicated **standard EKS cluster** in your existing
VPC, then hands application installation to the existing Helm chart. It targets
the chart and MCP image for `v0.15.0`; confirm release-image publication before
installing the application. CloudFormation does not install MCP, ZAP, application
credentials, target access rules or a database.

A live evaluation of this template on EKS `1.36` with one `m7i.xlarge` worker
verified cluster bootstrap, all five pinned add-ons, operator access and
encrypted EBS provisioning. Using a matched prerelease MCP image/chart, it also
verified authenticated crawl/passive/report flows, unchanged report readback
and a fresh scan after MCP replacement, and permitted/denied network paths
after policies settled.

This single-worker evaluation does not establish HA, production capacity,
deny-at-startup isolation or protection from hosting-node access. Optional import
and automation flows were outside its scope. Verify the published image and
complete the checks below in your own environment.

| File | Purpose |
| --- | --- |
| [cloudformation.yaml](cloudformation.yaml) | Cluster, one managed worker, IAM roles/access, five explicitly versioned add-ons and control-plane logs. |
| [storage-class.yaml](storage-class.yaml) | Encrypted `gp3` EBS storage with deferred binding, expansion and a `Retain` reclaim policy; apply after the cluster is ready. |
| [Helm deployment guide](../../helm/mcp-zap-server/README.md#first-private-eks-deployment) | Application credentials, storage, network rules, installation and verification. |

For a first AWS installation without an existing need for Kubernetes, consider
the [private EC2 example](../aws-ec2/) instead.

## 1. Check network, permissions and cost

Provide an existing IPv4 VPC with DNS support and DNS hostnames enabled, and at
least two cluster subnets in different supported availability zones. The private
worker subnet must be one of those subnets. Check free subnet addresses, EC2/EKS
quotas and instance availability in the worker's zone. Existing routes, NAT and
network rules must already allow worker access to required AWS services and
public image registries. This template creates no VPC, NAT gateway, endpoints,
VPN or operator host.

The cluster API has private access enabled and public access limited to your
required `OperatorCidr`; use your current public IPv4 address with `/32` where
possible. The template rejects `/0`. Private API access requires connectivity
to the VPC and suitable security-group access; it is not provided by a kubeconfig
file. See [EKS endpoint access](https://docs.aws.amazon.com/eks/latest/userguide/cluster-endpoint.html).
This is a private application baseline, not an Internet-disconnected cluster.

Keep two permission boundaries separate:

- The provisioning identity needs CloudFormation and the relevant EKS, EC2,
  IAM, Logs and add-on permissions, including role passing and any required
  service-linked-role creation. `CAPABILITY_IAM` acknowledges IAM creation;
  it does not grant these permissions.
- `OperatorPrincipalArn` is an existing **permanent IAM role ARN**, not an STS
  session ARN. The stack grants that role Kubernetes cluster-admin access through
  an EKS access entry. It grants no automatic cluster-creator admin access and
  does not create the operator role. Your credentials must be permitted to assume
  the role; kubeconfig creation also needs `eks:DescribeCluster`.

The node role uses `AmazonEKSWorkerNodePolicy`,
`AmazonEC2ContainerRegistryPullOnly` and `AmazonEKS_CNI_Policy` so stock networking
can bootstrap before the Pod Identity agent is ready. Moving CNI permissions to
a dedicated identity is a separate hardening step. EBS CSI uses its own Pod
Identity role, restricted in its trust policy to this cluster, `kube-system` and
`ebs-csi-controller-sa`. The worker requires IMDSv2 with hop limit `1`; assess
node and host-network access separately rather than treating this as complete
workload isolation.

Budget for the EKS control plane, one on-demand worker, its encrypted 40 GiB root
disk, application EBS storage, logs and network traffic. Existing NAT resources
keep their own charges. Check current regional prices before creating the stack.
The default `m7i.xlarge` is a starting configuration, not a measured scan-capacity
requirement. `c7i.2xlarge` and `m7i.2xlarge` are also selectable.

The worker group stays at one node in one subnet to keep a retained EBS workspace
in an attachable availability zone. This is not an HA deployment. The stack
enables all five control-plane log types with 14-day retention. Kubernetes
defaults to `1.36`; choose a version available in standard support in your Region.
The standard-support policy allows automatic upgrades when standard support
ends, so plan version and add-on maintenance.

## 2. Select compatible add-on versions

Run the commands from the repository root with AWS CLI v2, Python 3, `jq`, Helm
and a compatible `kubectl`. Use your normal AWS credential provider; do not put
credentials in parameter files. Replace every `REPLACE` value:

```bash
export MCP_EKS_REGION=us-east-1
export MCP_EKS_STACK=mcp-zap-eks-evaluation
export MCP_EKS_CLUSTER=mcp-zap-evaluation
export MCP_EKS_KUBERNETES_VERSION=1.36
export MCP_EKS_VPC_ID=vpc-REPLACE
export MCP_EKS_SUBNET_IDS=subnet-REPLACE-A,subnet-REPLACE-B
export MCP_EKS_WORKER_SUBNET_ID=subnet-REPLACE-A
export MCP_EKS_OPERATOR_ROLE_ARN=arn:aws:iam::ACCOUNT_ID:role/ROLE_NAME
export MCP_EKS_OPERATOR_CIDR=REPLACE/32
export MCP_EKS_INSTANCE_TYPE=m7i.xlarge
export MCP_EKS_OPERATOR_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mcp-zap-eks.XXXXXX")"

for addon in vpc-cni coredns kube-proxy eks-pod-identity-agent aws-ebs-csi-driver; do
  aws eks describe-addon-versions \
    --region "$MCP_EKS_REGION" \
    --kubernetes-version "$MCP_EKS_KUBERNETES_VERSION" \
    --addon-name "$addon" \
    --query 'addons[].{Name:addonName,Versions:addonVersions[].{Version:addonVersion,Architecture:architecture,Compatibility:compatibilities}}' \
    --output json
done
```

Choose an explicit compatible `amd64` version for each add-on from these regional
results. An empty result is a reason to stop and revisit the Kubernetes version
or Region. Do not copy a version from another deployment or assume the newest
version is compatible. See [add-on version discovery](https://docs.aws.amazon.com/cli/latest/reference/eks/describe-addon-versions.html).

```bash
export MCP_EKS_VPC_CNI_VERSION=REPLACE
export MCP_EKS_CORE_DNS_VERSION=REPLACE
export MCP_EKS_KUBE_PROXY_VERSION=REPLACE
export MCP_EKS_POD_IDENTITY_AGENT_VERSION=REPLACE
export MCP_EKS_EBS_CSI_VERSION=REPLACE

aws eks describe-addon-configuration \
  --region "$MCP_EKS_REGION" \
  --addon-name vpc-cni \
  --addon-version "$MCP_EKS_VPC_CNI_VERSION" \
  --query configurationSchema --output text > "${MCP_EKS_OPERATOR_DIR}/vpc-cni-schema.json"

jq '.. | objects | select(has("enableNetworkPolicy") or has("NETWORK_POLICY_ENFORCING_MODE"))' \
  "${MCP_EKS_OPERATOR_DIR}/vpc-cni-schema.json"

aws eks describe-addon-configuration \
  --region "$MCP_EKS_REGION" \
  --addon-name aws-ebs-csi-driver \
  --addon-version "$MCP_EKS_EBS_CSI_VERSION" \
  --query '{Schema:configurationSchema,PodIdentity:podIdentityConfiguration}' \
  --output json
```

Check the returned [configuration schema](https://docs.aws.amazon.com/cli/latest/reference/eks/describe-addon-configuration.html)
accepts the template's CNI configuration:
`{"enableNetworkPolicy":"true","env":{"NETWORK_POLICY_ENFORCING_MODE":"standard"}}`.
Confirm the selected EBS add-on supports Pod Identity for
`ebs-csi-controller-sa`. Save the chosen versions and schema alongside your
operator records; the managed node's AL2023 AMI release is selected by EKS, so
record its actual release after creation too.

## 3. Create the dedicated infrastructure

Write a parameter file from the selected values. It contains infrastructure
identifiers and versions, not application secrets. Generated schemas, parameters
and application values go in `MCP_EKS_OPERATOR_DIR`, outside the checkout.

```bash
python3 - <<'PY'
import json
import os
from pathlib import Path

names = {
    "ClusterName": "MCP_EKS_CLUSTER",
    "KubernetesVersion": "MCP_EKS_KUBERNETES_VERSION",
    "VpcId": "MCP_EKS_VPC_ID",
    "SubnetIds": "MCP_EKS_SUBNET_IDS",
    "WorkerSubnetId": "MCP_EKS_WORKER_SUBNET_ID",
    "OperatorPrincipalArn": "MCP_EKS_OPERATOR_ROLE_ARN",
    "OperatorCidr": "MCP_EKS_OPERATOR_CIDR",
    "InstanceType": "MCP_EKS_INSTANCE_TYPE",
    "VpcCniVersion": "MCP_EKS_VPC_CNI_VERSION",
    "CoreDnsVersion": "MCP_EKS_CORE_DNS_VERSION",
    "KubeProxyVersion": "MCP_EKS_KUBE_PROXY_VERSION",
    "PodIdentityAgentVersion": "MCP_EKS_POD_IDENTITY_AGENT_VERSION",
    "EbsCsiVersion": "MCP_EKS_EBS_CSI_VERSION",
}
parameters = []
for name, variable in names.items():
    value = os.environ[variable].strip()
    if not value or any(marker in value for marker in ("REPLACE", "ACCOUNT_ID", "ROLE_NAME")):
        raise SystemExit(f"Set {variable} before creating the stack")
    parameters.append({"ParameterKey": name, "ParameterValue": value})
Path(os.environ["MCP_EKS_OPERATOR_DIR"], "parameters.json").write_text(
    json.dumps(parameters, indent=2) + "\n"
)
PY

aws cloudformation create-stack \
  --region "$MCP_EKS_REGION" \
  --stack-name "$MCP_EKS_STACK" \
  --template-body file://examples/aws-eks/cloudformation.yaml \
  --parameters "file://${MCP_EKS_OPERATOR_DIR}/parameters.json" \
  --capabilities CAPABILITY_IAM

aws cloudformation wait stack-create-complete \
  --region "$MCP_EKS_REGION" --stack-name "$MCP_EKS_STACK"

aws cloudformation describe-stacks \
  --region "$MCP_EKS_REGION" --stack-name "$MCP_EKS_STACK" \
  --query 'Stacks[0].Outputs' --output table
```

The template validates subnet/VPC membership, worker-subnet membership and
multiple cluster availability zones. It retains stock networking during worker
bootstrap, then adopts it with managed add-ons before application installation.
`CREATE_COMPLETE` establishes stack creation, not application readiness. If a
waiter times out or creation fails, inspect stack events and resource status;
do not create a second stack to bypass an unresolved failure.

Record the outputs: `ClusterName`, `NodegroupName`, `WorkerRoleArn`,
`EbsCsiRoleArn`, `ClusterSecurityGroupId`, `WorkerSubnetId`,
`UpdateKubeconfigCommand` and `SetupGuide`. The node-group output is the plain
name to use with the EKS API.

## 4. Verify access, add-ons and storage

Use the operator role configured in the access entry for Kubernetes
authentication. [AWS's kubeconfig guide](https://docs.aws.amazon.com/eks/latest/userguide/create-kubeconfig.html)
explains the `--role-arn` authentication option. The commands below give this
cluster an explicit context, avoiding accidental operations on another cluster.
If your AWS credential profile already uses the same operator role configured in
the access entry, omit `--role-arn` instead of making it assume itself. Do this
only when the active role identity matches that access entry.

```bash
aws eks update-kubeconfig \
  --region "$MCP_EKS_REGION" --name "$MCP_EKS_CLUSTER" \
  --role-arn "$MCP_EKS_OPERATOR_ROLE_ARN" --alias "$MCP_EKS_CLUSTER"

kubectl --context "$MCP_EKS_CLUSTER" wait --for=condition=Ready nodes --all --timeout=300s
kubectl --context "$MCP_EKS_CLUSTER" get nodes -o wide
kubectl --context "$MCP_EKS_CLUSTER" get pods -n kube-system

for addon in vpc-cni coredns kube-proxy eks-pod-identity-agent aws-ebs-csi-driver; do
  aws eks describe-addon \
    --region "$MCP_EKS_REGION" --cluster-name "$MCP_EKS_CLUSTER" \
    --addon-name "$addon" \
    --query 'addon.{Name:addonName,Version:addonVersion,Status:status,Issues:health.issues}' \
    --output json
done

aws eks describe-addon \
  --region "$MCP_EKS_REGION" --cluster-name "$MCP_EKS_CLUSTER" \
  --addon-name vpc-cni --query addon.configurationValues --output text | jq .

kubectl --context "$MCP_EKS_CLUSTER" apply -f examples/aws-eks/storage-class.yaml
kubectl --context "$MCP_EKS_CLUSTER" get storageclass mcp-zap-gp3-retain -o yaml
kubectl --context "$MCP_EKS_CLUSTER" get csidriver ebs.csi.aws.com
```

Require one Ready worker, healthy system pods and all five add-ons `ACTIVE`
without health issues at the selected versions. Confirm the actual CNI
configuration enables policy enforcement in `standard` mode. Check the EBS Pod
Identity association and controller/node readiness if storage fails; do not
grant the application the node's IAM permissions to work around a CSI failure.
See [EBS CSI requirements](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html).

The StorageClass uses `ebs.csi.aws.com`, encrypted `gp3`, `WaitForFirstConsumer`,
`Retain` and volume expansion. A claim can remain Pending until its first pod is
scheduled; verify it binds and mounts after the Helm install. This configuration
is for standard EKS, not EKS Auto Mode's different storage provisioner.

Encryption uses the account's regional default EBS encryption key. If that
default is a customer-managed KMS key, separately review the EBS CSI driver's
permissions and the key's required grants. This template does not provision
those permissions or grants; do not replace that review with broad KMS access.

In CNI [standard mode](https://docs.aws.amazon.com/eks/latest/userguide/cni-network-policy-configure.html),
new pods initially allow traffic until their policies are applied. This starter
does not establish deny-at-startup isolation. Do not switch to `strict` merely
to resolve an application failure: first provide and verify the system-workload
policies, including CoreDNS and storage dependencies. NetworkPolicy also does
not by itself block every hosting-node service.

## 5. Install and prove the application

Continue with the [first private EKS deployment](../../helm/mcp-zap-server/README.md#first-private-eks-deployment)
and copy [values-aws.yaml](../../helm/mcp-zap-server/values-aws.yaml) outside the
checkout:

```bash
cp helm/mcp-zap-server/values-aws.yaml "$MCP_EKS_OPERATOR_DIR/values.yaml"
```

Edit that file before installing. Set `zap.persistence.storageClass` to
`mcp-zap-gp3-retain`; keep one MCP replica, one ZAP engine and both services on
`ClusterIP`. Preserve the chart's shared RWO storage placement, and leave worker
capacity for system pods. Infrastructure creation does not prove scan capacity.

Provision the existing Secret required by the chart, replace the private client
CIDR placeholder, and configure the exact authorized target CIDRs/ports and
matching URL-validation settings. Review add-on and background HTTP egress too;
the default ZAP policy allows DNS only. Keep policy enforcement enabled while
diagnosing connectivity. Use Kubernetes port forwarding over its authenticated
connection for initial MCP access, or configure authenticated TLS ingress before
remote exposure. Never expose the ZAP API as a client endpoint.

Use a chart and application image from the same **published** release. Verify the
image workflow and registry digest; a version in this README or a stack output
does not publish an image. The current chart defaults to `v0.15.0`. To pin MCP,
set `mcp.image.repository` to the verified registry repository and
`mcp.image.tag` to `v0.15.0@sha256:` followed by its actual 64-character digest.
For ZAP, set the chart's separate `zap.image.digest`. Record the rendered image
references and actual running image IDs; image pinning does not pin add-ons
installed later or restored from persistent ZAP state.

After provisioning the referenced Secret through the Helm guide and reviewing
storage, secrets, network rules and image references in your values file, install:

```bash
helm upgrade --install mcp-zap ./helm/mcp-zap-server \
  --kube-context "$MCP_EKS_CLUSTER" \
  --namespace mcp-zap --create-namespace \
  --values "$MCP_EKS_OPERATOR_DIR/values.yaml" \
  --wait --timeout 15m
```

Before real target scans, complete the Helm guide and
[production checklist](../../docs/src/content/docs/operations/production-checklist.md):
verify allowed MCP access and blocked ZAP API access from a separate workload,
verify approved target traffic and denied destinations, crawl an owned target,
wait for passive completion, generate and fully read a report, and exercise any
import/automation flow you enabled. Replace MCP and repeat report readback and a
fresh scan. Health, add-on status and successful storage binding alone do not
prove these application flows or HA.

## 6. Clean up resources by ownership

While the cluster and CSI controller still run, stop new work, drain scans and
export needed reports. Inventory the release's PVCs, PVs, EBS volume IDs and any
load balancers you created. Uninstall the application and remove its ingress or
LoadBalancer services before deleting the cluster; allow their controllers to
finish cleanup. AWS documents the [cluster deletion order](https://docs.aws.amazon.com/eks/latest/userguide/delete-cluster.html).

The chart can retain its PVC on uninstall, and this StorageClass retains the
underlying PV/EBS volume even after a PVC is deleted. These dynamically created
application volumes are **not CloudFormation-owned**. After exporting data and
confirming each intended volume is detached and unused, explicitly decide
whether to retain it or manually delete that exact volume. Do not delete volumes
by a broad name/tag filter or assume stack deletion removes them. Retained
volumes and snapshots continue to incur charges.

Then delete only this dedicated infrastructure stack:

```bash
aws cloudformation delete-stack \
  --region "$MCP_EKS_REGION" --stack-name "$MCP_EKS_STACK"
aws cloudformation wait stack-delete-complete \
  --region "$MCP_EKS_REGION" --stack-name "$MCP_EKS_STACK"
```

Verify the cluster, managed worker, root volume and stack-owned IAM resources
are removed. The stack's control-plane log group is deleted with the stack;
export required logs first rather than relying on its 14-day retention after
deletion. Check for retained application EBS volumes, snapshots and manually
created load balancers, then review costs. The existing VPC, subnets, operator
role, NAT gateways and routes remain outside this stack; do not remove shared
network resources as part of this example's cleanup.

## Offline authoring checks

Run [cfn-lint](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/cfn-lint.html)
against `examples/aws-eks/cloudformation.yaml`, and lint/render the Helm chart
with your intended values. Inspect the rendered image references, credentials
references, storage and network rules before applying them. Schema checks catch
template authoring problems; they do not verify AWS permissions, regional add-on
availability, working routes, quota, runtime policy enforcement or cleanup.
