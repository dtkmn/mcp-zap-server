#!/usr/bin/env bash
# Host preparation only: no AWS resources, secrets or containers are created here.
set -euo pipefail
umask 077

if [[ $(id -u) != 0 ]]; then
  echo "Run this script as root on a dedicated Amazon Linux 2023 EC2 instance." >&2
  exit 1
fi
if [[ $(uname -m) != x86_64 ]]; then
  echo "This example requires an x86_64 host." >&2
  exit 1
fi
# shellcheck disable=SC1091
source /etc/os-release
if [[ ${ID:-} != amzn || ${VERSION_ID:-} != 2023 ]]; then
  echo "This example requires Amazon Linux 2023." >&2
  exit 1
fi

example_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd -- "$example_dir"
for required in aws curl sha256sum python3 ip; do
  if ! command -v "$required" >/dev/null; then
    echo "Missing required host command: $required. Use the standard Amazon Linux 2023 AMI." >&2
    exit 1
  fi
done

# Refuse an overlap with the EC2/VPC routes. The existing example bridge is safe
# on a repeated invocation; changing the subnet requires updating Compose too.
python3 - <<'PY'
import ipaddress
import json
import subprocess

candidate = ipaddress.ip_network("172.30.250.0/24")
for row in json.loads(subprocess.check_output(["ip", "-j", "route", "show"])):
    destination = row.get("dst", "default")
    if destination == "default":
        continue
    network = ipaddress.ip_network(destination, strict=False)
    if row.get("dev") == "br-mcpzap" and network == candidate:
        # Only accept the running network owned by this Compose project. A
        # bridge with the same name belonging to another stack is a collision.
        result = subprocess.run(
            ["docker", "network", "inspect", "mcp-zap-ec2_isolated"],
            capture_output=True, text=True,
        )
        if result.returncode == 0:
            existing = json.loads(result.stdout)[0]
            if (
                existing.get("Driver") == "bridge"
                and existing.get("Internal") is True
                and existing.get("EnableIPv6") is False
                and existing.get("Options", {}).get("com.docker.network.bridge.name") == "br-mcpzap"
                and existing.get("Labels", {}).get("com.docker.compose.project") == "mcp-zap-ec2"
                and existing.get("IPAM", {}).get("Config", [{}])[0].get("Subnet") == str(candidate)
            ):
                continue
    if network.version == 4 and candidate.overlaps(network):
        raise SystemExit("Example subnet overlaps a host route; select another subnet before proceeding.")
PY

# AWS CLI is included in this AMI; awscli2 is not an Amazon Linux RPM name.
dnf install -y docker iptables
compose_version=v5.5.1
compose_sha256=db1889184726840f75c4f9c001048430d4f25b3be3cb084d3ddd762bc0aed576
compose_destination=/usr/local/lib/docker/cli-plugins/docker-compose
install -d -m0755 /usr/local/lib/docker/cli-plugins
compose_download=$(mktemp)
trap 'rm -f -- "$compose_download"' EXIT
curl --fail --silent --show-error --location \
  "https://github.com/docker/compose/releases/download/$compose_version/docker-compose-linux-x86_64" \
  --output "$compose_download"
printf '%s  %s\n' "$compose_sha256" "$compose_download" | sha256sum -c - >/dev/null
install -m0755 "$compose_download" "$compose_destination"

install -d -o1000 -g1000 -m0750 workspace workspace/workspaces \
  workspace/workspaces/ec2-operator workspace/workspaces/ec2-operator/automation
install -o1000 -g1000 -m0640 automation-plan.yaml \
  workspace/workspaces/ec2-operator/automation/example-plan.yaml
chmod 0644 smoke-api.py
install -m0755 firewall.sh /usr/local/sbin/mcp-zap-ec2-firewall
cat > /etc/systemd/system/mcp-zap-ec2-firewall.service <<'UNIT'
[Unit]
Description=Network boundary for the private MCP ZAP EC2 example
Before=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/mcp-zap-ec2-firewall
RemainAfterExit=yes

[Install]
WantedBy=multi-user.target
UNIT

install -d -m0755 /etc/systemd/system/docker.service.d
cat > /etc/systemd/system/docker.service.d/mcp-zap-ec2-firewall.conf <<'UNIT'
[Unit]
Requires=mcp-zap-ec2-firewall.service
After=mcp-zap-ec2-firewall.service

[Service]
ExecStartPost=/usr/local/sbin/mcp-zap-ec2-firewall
UNIT

systemctl daemon-reload
systemctl enable --now mcp-zap-ec2-firewall.service
# An already-active oneshot is not rerun by enable --now. Apply the same atomic
# update on repeated setup calls without restarting a dependency of Docker.
/usr/local/sbin/mcp-zap-ec2-firewall
systemctl enable --now docker.service
echo "Host preparation complete. Configure private .env keys, then start Compose as documented."
