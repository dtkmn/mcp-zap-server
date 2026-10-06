# Private EC2 example

This example runs published MCP ZAP Server v0.14.0 and ZAP 2.17.0 on a dedicated
Amazon Linux 2023 x86_64 instance. It serves one trusted MCP client through AWS
Session Manager. It publishes no container ports. The included `smoke-target`
is an owned API for checking crawling, OpenAPI import, automation and reports
without granting scanning containers Internet access.

The [AWS deployment walkthrough](../../docs/operator/runbooks/AWS_EC2_COMPOSE_GUIDE.md)
is the canonical guide for IAM, EC2, secrets, private client access, verification
and cleanup.

| File | Purpose |
| --- | --- |
| `compose.yaml` | Pinned images, isolated network, memory limits and aligned workspace paths. |
| `.env.example` | Required separate MCP/ZAP API keys; intentionally empty. |
| `setup-host.sh` | Root-only host preparation for Amazon Linux 2023 x86_64. |
| `firewall.sh` | Bridge-scoped host/forward rules, installed persistently by host setup. |
| `smoke-api.py` | Minimal internal scan fixture; never expose its port. |
| `automation-plan.yaml` | Bounded requestor/spider/passive-wait/JSON-report plan for the internal fixture. |

Run `sudo bash ./setup-host.sh` from this directory. The script installs Docker
and a checksum-verified Compose binary, prepares the writable UID/GID 1000
workspace, and applies firewall rules before Docker starts and after each Docker
restart. It does not create AWS resources, generate credentials, or start
containers. It assumes a fresh, dedicated host using Docker's default iptables
backend; do not use it to reconfigure a shared Docker host.

Host setup also copies `automation-plan.yaml` to
`workspace/workspaces/ec2-operator/automation/example-plan.yaml`, owned by
UID/GID 1000 with mode 0640. Both containers see this plan at
`/zap/wrk/workspaces/ec2-operator/automation/example-plan.yaml`. Submit that
absolute path to `zap_automation_plan_run` using its `planPath` argument, then
follow the walkthrough's status, artifact and report-read checks. Re-running
host setup restores the example plan from this source file.

The client identity `ec2-operator` is fixed in Compose. If you change it, change
both automation directories to the matching `workspaces/<client-id>/automation`
path. Use the current MCP container IP for SSM remote-host forwarding; Docker
may assign a different address when a container is recreated.

Stop the application with `sudo docker compose down` from this directory. The
host workspace and `.env` remain on disk. For a temporary trial, terminate the
dedicated EC2 instance and verify its root volume is deleted, then remove only
the IAM and security-group resources created for it. Stopping EC2 alone does not
remove its storage charges. Firewall files are host configuration and disappear
when that dedicated instance is terminated.
